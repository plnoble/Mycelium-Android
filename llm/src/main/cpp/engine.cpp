#include <jni.h>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <unistd.h>
#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <map>
#include <array>
#include <algorithm>
#include "llama.h"
#include "json.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"

namespace {
std::atomic<bool> cancelled{false};
std::once_flag backend_init;
struct Engine {
    FILE * file = nullptr;
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    // The tokens of the last prompt that was read to its end (P2-1c). The KV cache holds that prompt,
    // so a later round whose prompt starts with the same tokens can keep them and read only the new
    // tail. Kept next to the context so that anything which frees the context forgets it too.
    std::vector<llama_token> cachedPrompt;
    // 默认关思考（与之前写死的 false 一致）；只有真机测例会改它，用来对比模板认不认 enable_thinking。
    bool thinking = false;
    // P2-1d：prefill 跨过 checkpointPos 时存下的循环层状态，等 Kotlin 这一轮结束后取走。
    // 状态只能前进，读过去就回不去，所以**必须在 prefill 循环里**存，而不是生成结束再补。
    std::vector<uint8_t> pendingCheckpoint;
    int pendingCheckpointPos = -1;
    // P2-1d（2026-10-23 夜，任务书第一段第 2 条）：检查点的字节**归原生**，Kotlin 只拿句柄与元数据。
    // 好处：不必为了交给 Kotlin 再拷一份 JByteArray（真机实测那次拷贝 13-31 ms，而且让托管堆峰值
    // 多出一份几十 MB）；恢复时也直接从原生内存装回，省掉反向拷贝与 Kotlin 侧那一份分配。
    std::map<int, std::vector<uint8_t>> checkpointStates;
    int nextCheckpointHandle = 1;
    // 只给真机诊断：预填一次提交多少 token。改小就能让'整段读'分成与'恢复后读'相同的批次形状，
    // 用来区分温度 0 的差异到底是批次形状造成的浮点差异，还是状态恢复得不完全一致。
    int promptBatch = 0;
    // 固定切法（任务书 2026-10-24 第一节第 2 条）：断批只由 token 序列决定（落点处必断、段内每
    // n_batch 个一批），**与「这一轮存不存检查点」无关**——这样开、关、第一轮在共同前缀上的批次
    // 形状完全一致，浮点末位对齐，温度 0 才可能逐字相同。这个标志只管「落点上要不要真的存」。
    bool checkpointSaving = false;
    // 固定切法的强制断批位置（-1 = 不切）。与 checkpointSaving 无关：关、开、第一轮都切。
    int checkpointCut = -1;
    // P2-1d 诊断计时（毫秒）：0 清空序列、1 set_data_ext、2 get_size_ext、3 get_data_ext、4 取字节的 JNI 拷贝。
    // 只用来回答「恢复一份 65 MB 状态为什么这么慢」，不改任何行为。
    long timing[5] = {0, 0, 0, 0, 0};
    // 上一次恢复失败在哪一步（0 = 成功，1 = set_data_ext 短读，2 = seq_rm 返回 false）。
    // 只给诊断用：真机上「为什么没走检查点」要靠它归因。
    int lastRestoreStep = -1;
    ~Engine() { clear(); }
    void clear() {
        cachedPrompt.clear();
        // 全量审必修项 3：clear() 必须彻底释放 vector 容量，不能只把 size 变 0 却保留几十 MB 原生内存。
        std::vector<uint8_t>().swap(pendingCheckpoint);
        pendingCheckpointPos = -1;
        checkpointStates.clear();
        if (context) llama_free(context);
        if (model) llama_model_free(model);
        if (file) fclose(file);
        context = nullptr; model = nullptr; file = nullptr;
    }
} engine;

using Clock = std::chrono::steady_clock;
// 预填一次提交多少个 token（与 cp.n_batch 一致；越大提示吞吐越高）。
constexpr int PROMPT_BATCH = 512;
long elapsed(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}
bool abort_decode(void *) { return cancelled.load(); }
void fail(JNIEnv * env, const char * message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string bytes(JNIEnv * env, jbyteArray input) {
    std::string value(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
std::string piece(const llama_vocab * vocab, llama_token token, bool special) {
    if (token == LLAMA_TOKEN_NULL) return "";
    std::vector<char> buffer(128);
    int n = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, special);
    if (n < 0) {
        buffer.resize(-n);
        n = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, special);
    }
    if (n < 0) throw std::runtime_error("Token conversion failed");
    return std::string(buffer.data(), n);
}
std::string prompt(const std::string & messages, bool add_generation_prompt = true) {
    const char * source = llama_model_chat_template(engine.model, nullptr);
    if (!source || !*source) throw std::runtime_error("The model has no chat template");
    const auto * vocab = llama_model_get_vocab(engine.model);
    auto variables = common_json::object();
    variables["messages"] = common_json::parse(messages);
    variables["add_generation_prompt"] = add_generation_prompt;
    variables["enable_thinking"] = engine.thinking;
    variables["bos_token"] = piece(vocab, llama_vocab_bos(vocab), true);
    variables["eos_token"] = piece(vocab, llama_vocab_eos(vocab), true);
    jinja::lexer lexer;
    auto program = jinja::parse_from_tokens(lexer.tokenize(source));
    jinja::context context(source);
    jinja::global_from_json(context, variables, false);
    jinja::runtime runtime(context);
    return jinja::runtime::gather_string_parts(runtime.execute(program))->as_string().str();
}
// The tokens of a formatted prompt. Both the JVM-side cache decision and the decoding below go
// through this one function, so the two always describe the same sequence.
std::vector<llama_token> prompt_tokens(const std::string & formatted) {
    const auto * vocab = llama_model_get_vocab(engine.model);
    const int count = -llama_tokenize(vocab, formatted.data(), formatted.size(), nullptr, 0, true, true);
    if (count <= 0) throw std::runtime_error("Tokenization failed");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, formatted.data(), formatted.size(), tokens.data(), count, true, true) != count)
        throw std::runtime_error("Tokenization failed");
    return tokens;
}
// How many leading tokens the two sequences share. This is the only thing that may be kept: the KV
// entries of a token that is not identical would be wrong state, and a wrong state answers wrongly.
int shared_prefix(const std::vector<llama_token> & a, const std::vector<llama_token> & b) {
    const int limit = static_cast<int>(std::min(a.size(), b.size()));
    int shared = 0;
    while (shared < limit && a[shared] == b[shared]) shared++;
    return shared;
}
// 除非拥有它的那一轮把原生读数交回调用方，否则把记住的提示丢掉，并把原生内存一起清掉：只在
// token 向量上作废、把内存留给下一轮，等于让下一轮去复用一份没人担保的状态（全量审必修 3）。
struct PromptGuard {
    bool committed = false;
    ~PromptGuard() {
        if (committed) return;
        engine.cachedPrompt.clear();
        if (engine.context) llama_memory_clear(llama_get_memory(engine.context), true);
    }
};
}

extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_begin(JNIEnv *, jobject) { cancelled.store(false); }
extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_setThinking(JNIEnv *, jobject, jboolean thinking) {
    // 只给真机测例用（P2-1c 的隐藏段三项）：默认关思考，测例要对比时才打开。
    engine.thinking = thinking;
}
extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_stop(JNIEnv *, jobject) { cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_unload(JNIEnv *, jobject) { engine.clear(); }

extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_load(JNIEnv * env, jobject, jint fd, jint threads, jint context) {
    try {
        engine.clear();
        if (threads < 1 || threads > 16 || context < 512 || context > 32768)
            throw std::runtime_error("Invalid engine settings");
        std::call_once(backend_init, [] { llama_backend_init(); });
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.progress_callback = [](float, void *) { return !cancelled.load(); };
        const int duplicate = dup(fd);
        if (duplicate < 0) throw std::runtime_error("Cannot duplicate model descriptor");
        engine.file = fdopen(duplicate, "rb");
        if (!engine.file) { close(duplicate); throw std::runtime_error("Cannot open model stream"); }
        if (fseek(engine.file, 0, SEEK_SET) != 0) throw std::runtime_error("Model must be seekable");
        engine.model = llama_model_load_from_file_ptr(engine.file, mp);
        if (!engine.model) throw std::runtime_error(cancelled ? "Loading stopped" : "GGUF load failed");
        if (cancelled) throw std::runtime_error("Loading stopped");
        auto cp = llama_context_default_params();
        cp.n_ctx = context;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        // P2-1d 吞吐修复（2026-10-23 真机实测：提示读取只有 16-33 token/秒，比同类硬件低一个数量级）。
        // 128 太小：预填阶段每批只能塞 128 个 token，批越大吞吐越高。llama.cpp 默认是 2048/512。
        cp.n_batch = 512;
        cp.n_ubatch = 512;
        // 不请求循环状态回退快照（`n_rs_seq`，P2-1c 全量审阻断 1、2）：
        //  - `llama-batch.cpp` 里 `split_equal()` 断言 `n_ubatch > n_rs_seq + 1`，按窗口配批次会直接 abort；
        //  - 更要紧的是：这个版本的快照按 ubatch 写，而生成阶段每次只解一个 token，较老的槽不会随
        //    已生成的 token 整体后移；`seq_rm()` 只核对回退距离是否超过窗口，核不出槽里的状态是否对应
        //    那个位置。也就是说窗口内的「成功回退」也可能恢复到位置不对的循环状态，而错误状态会答错。
        // 所以这里保持 0：纯注意力模型仍能按位置精确裁剪 KV（复用照常提速），混合状态模型（qwen35
        // 这类）的循环侧会让 seq_rm 返回 false，于是整段重读——正确但拿不到提速，交统筹决定是否单开
        // 一个工作包做「按位置可恢复的检查点」。
        // 长上下文时注意力最吃 flash attention；AUTO 让它按后端能力自己决定（不支持时自动退回）。
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cp.offload_kqv = false;
        cp.op_offload = false;
        cp.abort_callback = abort_decode;
        engine.context = llama_init_from_model(engine.model, cp);
        if (!engine.context || cancelled) throw std::runtime_error("Context initialization failed or stopped");
    } catch (const std::exception &) {
        engine.clear();
        fail(env, cancelled ? "Loading stopped" : "GGUF load failed; check model and available memory");
    } catch (...) {
        engine.clear(); fail(env, "Native loading failed");
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_tokenize(JNIEnv * env, jobject, jbyteArray messages) {
    try {
        if (!engine.context) throw std::runtime_error("Load a model first");
        // Same formatting as generate() below: the caller compares this sequence with the one it
        // held, so a different formatting here would make the comparison mean nothing.
        auto tokens = prompt_tokens(prompt(bytes(env, messages)));
        auto result = env->NewIntArray(static_cast<jsize>(tokens.size()));
        if (!result) return nullptr;
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(tokens.size()),
            reinterpret_cast<const jint *>(tokens.data()));
        return result;
    } catch (const std::exception &) {
        fail(env, "Tokenization failed; check the model chat template and context");
    } catch (...) { fail(env, "Native tokenization failed"); }
    return nullptr;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_generate(
    JNIEnv * env, jobject, jbyteArray messages, jint maximum, jfloat temperature,
    jfloat top_p, jint seed, jint reuseTokens, jint saveCheckpointAt, jboolean checkpointSave, jint restoredTo, jobject callback) {
    // 恢复位置本轮不参与读取决策（见下面 reuse 的计算）：保留参数是为了让「恢复与否」在原生这一侧
    // 有据可查，也为了将来连注意力 KV 一起存之后可以直接接上。
    (void) restoredTo;
    try {
        if (!engine.context) throw std::runtime_error("Load a model first");
        if (maximum < 1 || maximum > 8192) throw std::runtime_error("Invalid token limit");
        PromptGuard guard;
        auto formatted = prompt(bytes(env, messages));
        auto callback_class = env->GetObjectClass(callback);
        auto on_start = env->GetMethodID(callback_class, "onStart", "(Z)V");
        auto on_token = env->GetMethodID(callback_class, "onToken", "([B)Z");
        if (!on_start || !on_token) return nullptr;
        const auto open = formatted.rfind("<think>");
        const auto close = formatted.rfind("</think>");
        const bool thinking = open != std::string::npos && (close == std::string::npos || close < open);
        env->CallVoidMethod(callback, on_start, thinking);
        if (env->ExceptionCheck()) return nullptr;
        const auto * vocab = llama_model_get_vocab(engine.model);
        auto tokens = prompt_tokens(formatted);
        const int count = static_cast<int>(tokens.size());
        if (static_cast<uint64_t>(count) + maximum > llama_n_ctx(engine.context))
            throw std::runtime_error("Messages and token limit exceed context length");
        // Keep as much of the leading run as the caller asked for AND the engine itself still holds:
        // the second half is what makes a wrong number from the caller unable to read KV state that
        // belongs to different tokens. A round always reads at least one token.
        // The clamp the plan review asked to pin down: never negative, never past the end of the
        // prompt, never past what the engine itself still holds (its own prefix is the only state
        // that may be reused), and always at least one token to read.
        const int requested = reuseTokens < 0 ? 0 : reuseTokens;
        // P2-1d（2026-10-23 统筹裁定）：这一轮如果已经从**整份状态**恢复好了（restoredTo >= 0），就直接
        // 从那里接着读：状态里既有注意力 KV 也有循环层，内容与位置都对，跳过 P2-1c 的「共享前缀 + 裁尾」
        // 那条路（它会因为混合模型退不回去而整段重读，把刚恢复好的状态白丢）。
        // 只存循环层的时候这么干会答错（注意力那半边是旧内容），所以旧路径已废止。
        int reuse = restoredTo >= 0
            ? std::min(restoredTo, count - 1)
            : std::min(requested, shared_prefix(engine.cachedPrompt, tokens));
        if (reuse > count - 1) reuse = count - 1;
        if (reuse < 0) reuse = 0;
        // Reported to the caller so "why did this round still read everything?" has an answer:
        // 1 = nothing was asked for, 2 = nothing shareable was held, 3 = this build refused to drop
        // only a suffix (the model's state cannot be rolled back that far).
        jlong fallback = 0;
        auto * memory = llama_get_memory(engine.context);
        bool trimmed = false;
        if (restoredTo >= 0) {
            // 刚从整份状态恢复：序列里已经是 [0, restoredTo)，**不裁也不清**。
            trimmed = true;
        } else if (reuse > 0) {
            trimmed = llama_memory_seq_rm(memory, 0, reuse, -1);
        }
        if (!trimmed) {
            // Nothing to reuse, or this build cannot drop only a suffix: read the whole prompt, which
            // is what every round did before this cache existed.
            llama_memory_clear(memory, true);
            fallback = requested == 0 ? 1 : (reuse == 0 ? 2 : 3);
            reuse = 0;
        }
        auto start = Clock::now();
        int read = 0;
        engine.pendingCheckpoint.clear();
        engine.pendingCheckpointPos = -1;
        // **固定切法**（任务书 2026-10-24 第一节第 2 条）：断批只由 token 序列决定——落点
        // （`checkpointCut`，「用户消息结束、助手头之前」）处必断，段内每 n_batch 个一批。
        // 开、关、第一轮都用这套规则，共同前缀上的批次形状完全一致；**存不存检查点**由
        // `checkpointSaving` 另行决定，与切法解耦。
        for (int pos = reuse; pos < count && !cancelled;) {
            const int cutPos = engine.checkpointCut > 0 ? engine.checkpointCut : saveCheckpointAt;
            const bool cutAhead = cutPos > pos && cutPos < count;
            const bool saving = checkpointSave == JNI_TRUE && saveCheckpointAt > pos &&
                saveCheckpointAt < count && engine.pendingCheckpoint.empty();
            const int chunk = engine.promptBatch > 0 ? engine.promptBatch : PROMPT_BATCH;
            int length = std::min(chunk, count - pos);
            if (cutAhead && pos + length > cutPos) {
                // 本批会越过落点：先只读到落点为止。
                length = cutPos - pos;
            }
            if (length <= 0) { pos += 1; continue; }
            auto batch = llama_batch_get_one(tokens.data() + pos, length);
            if (llama_decode(engine.context, batch) != 0) {
                if (cancelled) break;
                engine.cachedPrompt.clear();
                throw std::runtime_error("Prompt evaluation failed");
            }
            read += length;
            pos += length;
            // 状态只能前进，读过去就回不去了：正好停在落点上的这一刻必须存（整份状态，含注意力 KV）。
            if (saving && pos == saveCheckpointAt) {
                const auto t_size = Clock::now();
                const size_t size = llama_state_seq_get_size_ext(
                    engine.context, 0, LLAMA_STATE_SEQ_FLAGS_NONE);
                engine.timing[2] = elapsed(t_size);
                // 全量审必修项 3：分配前按 300 MiB 准入——单份就超上限的直接不分配，避免打爆原生堆。
                const size_t MAX_CHECKPOINT_SIZE = 300ULL * 1024 * 1024;
                if (size > 0 && size <= MAX_CHECKPOINT_SIZE) {
                    // 全量审 P1-4：**保存快照失败只是跳过优化，绝不能把生成拖垮**。原来直接
                    // `std::vector<uint8_t> buffer(size)`：几十 MB 的分配一旦抛 bad_alloc，就会被当成
                    // 整轮生成的失败抛出 JNI 边界（Android 明确要求 C++ 异常不得越界）。这里单独接住。
                    std::vector<uint8_t> buffer;
                    bool allocated = true;
                    try {
                        buffer.resize(size);
                    } catch (...) {
                        allocated = false;
                    }
                    if (!allocated) {
                        engine.pendingCheckpointPos = -1;
                    } else {
                        const auto t_data = Clock::now();
                        const size_t written = llama_state_seq_get_data_ext(
                            engine.context, buffer.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_NONE);
                        engine.timing[3] = elapsed(t_data);
                        // 方案审必修项 2：判「等于」，不是判非零。
                        if (written == size) {
                            engine.pendingCheckpoint = std::move(buffer);
                            engine.pendingCheckpointPos = saveCheckpointAt;
                        }
                    }
                }
            }
        }
        long prompt_ms = elapsed(start);
        // Only a prompt that was read to its end may be kept: a half-read prompt is not the state the
        // next round would think it is.
        if (read == count - reuse) engine.cachedPrompt = tokens;
        else engine.cachedPrompt.clear();
        auto params = llama_sampler_chain_default_params();
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(params), llama_sampler_free);
        if (temperature <= 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(static_cast<uint32_t>(seed)));
        }
        int generated = 0;
        start = Clock::now();
        while (generated < maximum && !cancelled) {
            llama_token token = llama_sampler_sample(sampler.get(), engine.context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            auto text = piece(vocab, token, true);
            jbyteArray data = env->NewByteArray(text.size());
            if (!data) return nullptr;
            env->SetByteArrayRegion(data, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
            bool keep_going = env->CallBooleanMethod(callback, on_token, data);
            env->DeleteLocalRef(data);
            if (env->ExceptionCheck()) return nullptr;
            generated++;
            if (!keep_going) { cancelled.store(true); break; }
            // Decode the final token as well, so timing includes every generated token.
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(engine.context, batch) != 0) {
                // 生成中途 decode 失败：状态不明，这一轮必须以失败收场——若把它当成正常读数交回去，
                // Kotlin 会因为「提示已读完」把它登记成可复用的缓存（方案审 r2 必修 1）。
                throw std::runtime_error("Generation failed");
            }
        }
        // {prompt tokens, generated, prompt ms (the part actually read), generation ms, stopped,
        //  tokens reused from the cache, tokens actually read, why a reuse did not happen,
        //  P2-1d：这一轮存下的检查点位置（-1 = 没存）, 这一轮存下的检查点字节数（0 = 没存）}
        jlong values[] = {count, generated, prompt_ms, elapsed(start), cancelled ? 1 : 0, reuse, read, fallback,
                          engine.pendingCheckpoint.empty() ? -1 : engine.pendingCheckpointPos,
                          static_cast<jlong>(engine.pendingCheckpoint.size())};
        auto result = env->NewLongArray(10);
        if (!result) return nullptr;                       // 读数没回去：guard 清缓存与内存
        env->SetLongArrayRegion(result, 0, 10, values);
        if (env->ExceptionCheck()) return nullptr;         // 同上
        // 读数真的交到调用方手里了，这一轮的缓存才算数。
        guard.committed = true;
        return result;
    } catch (const std::exception & e) {
        // A failed round leaves a prompt that may be half read: forget it instead of letting the next
        // round reuse a state nobody can vouch for.
        engine.cachedPrompt.clear();
        // Deliberately exclude template/message excerpts from error output.
        const std::string message = e.what();
        fail(env, message == "Messages and token limit exceed context length" ? message.c_str() :
            "Generation failed; check the model chat template and context");
    } catch (...) { engine.cachedPrompt.clear(); fail(env, "Native generation failed"); }
    return nullptr;
}

/*
 * P2-1d 的 S2：把这一刻的**循环层状态**存下来（方案 2.2 / 方案审必修项 2）。
 *
 * 只存 PARTIAL_ONLY：混合模型的普通注意力 KV 不在里面，恢复时必须保证同一前缀的 KV 本来就是对的
 * （所以恢复位置绝不能越过本轮的公共前缀）。返回 null 表示「这一份没法存」，调用方据此不记账。
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_saveCheckpoint(JNIEnv * env, jobject) {
    try {
        if (!engine.context) return nullptr;
        // 取大小与取字节必须用**同一组参数**，否则拿到的长度与实际字节对不上。
        const size_t size = llama_state_seq_get_size_ext(engine.context, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        if (size == 0) return nullptr;
        std::vector<uint8_t> buffer(size);
        const size_t written = llama_state_seq_get_data_ext(
            engine.context, buffer.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        // 方案审必修项 2：这两个接口返回的是**字节数**，要判「等于」，不是判非零。
        if (written != size) return nullptr;
        auto out = env->NewByteArray(static_cast<jsize>(written));
        if (!out) return nullptr;
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(written), reinterpret_cast<const jbyte *>(buffer.data()));
        if (env->ExceptionCheck()) return nullptr;
        return out;
    } catch (...) { return nullptr; }
}

/*
 * P2-1d 的 S3：恢复检查点并裁到恢复位置（只恢复循环层部分）。
 *
 * 方案审必修项 2 定的顺序**全在这一个函数里**完成，调用方不需要（也不该）自己先 seq_rm：
 *   1. `set_data_ext(..., PARTIAL_ONLY)`，返回值必须**等于字节数**（短读 = 这一份不可信）；
 *   2. 恢复之后循环状态的最后位置是 `pos - 1`，再 `llama_memory_seq_rm(memory, 0, pos, -1)`，
 *      这一步必须返回 true，否则位置与状态对不上；
 *   3. 只有两步都成，才允许从 `pos` 接着读。
 * 任何一步失败都返回 false，调用方据此清理不可信状态、从 0 重读并报原因 6。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_restoreCheckpointHandle(
    JNIEnv *, jobject, jint handle, jint pos) {
    try {
        if (!engine.context || pos < 0) return JNI_FALSE;
        const auto found = engine.checkpointStates.find(handle);
        if (found == engine.checkpointStates.end()) return JNI_FALSE;
        // 直接读原生里那一份，不再从 Kotlin 拷一份 ByteArray 进来。
        const auto & buffer = found->second;
        if (buffer.empty()) return JNI_FALSE;
        auto * memory = llama_get_memory(engine.context);
        // 统筹裁定的三步恢复（2026-10-23；取代第 7 节「禁止先删」那条旧指令）：
        // 1) 整段清空序列 0 —— 不留任何尾巴，绕开循环层「rollback >= 1」的限制；失败就不许往下装。
        const auto t_clear = Clock::now();
        if (!llama_memory_seq_rm(memory, 0, -1, -1)) { engine.lastRestoreStep = 4; return JNI_FALSE; }
        engine.timing[0] = elapsed(t_clear);
        // 2) 整份装回（注意力 KV + 循环层），返回值必须**等于字节数**。
        const auto t_apply = Clock::now();
        const size_t read = llama_state_seq_set_data_ext(
            engine.context, buffer.data(), buffer.size(), 0, LLAMA_STATE_SEQ_FLAGS_NONE);
        engine.timing[1] = elapsed(t_apply);
        if (read != buffer.size()) { engine.lastRestoreStep = 1; return JNI_FALSE; }
        // 3) 从 pos 接着读由 generate 那条路做（restoredTo = pos，跳过 P2-1c 的裁尾与清空）。
        engine.lastRestoreStep = 0;
        return JNI_TRUE;
    } catch (...) { return JNI_FALSE; }
}

/*
 * P2-1d：这个模型能不能走检查点路径（方案 2.3）。
 *
 * 只有混合 / 循环模型才存循环层状态；纯注意力模型走 P2-1c 的既有路径，行为完全不变。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_canCheckpoint(JNIEnv *, jobject) {
    if (!engine.model) return JNI_FALSE;
    return (llama_model_is_hybrid(engine.model) || llama_model_is_recurrent(engine.model))
        ? JNI_TRUE : JNI_FALSE;
}

/**
 * P2-1d：把刚才那一轮存下的检查点**留在原生内存**里，返回一个句柄（取不到给 -1）。
 *
 * 与旧版 `takeCheckpoint()` 的区别：不再新建 JByteArray，字节直接进 `checkpointStates`，Kotlin 只
 * 拿这个整数。这样既省掉一次几十 MB 的拷贝，也让托管堆峰值少一份（任务书第一段第 2 条）。
 * 句柄由 Kotlin 的检查点仓库保管，作废/淘汰时必须调 `releaseCheckpointHandle()` 释放。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_takeCheckpointHandle(JNIEnv *, jobject) {
    if (engine.pendingCheckpoint.empty()) return -1;
    const int handle = engine.nextCheckpointHandle++;
    // 全量审 P1-4：登记句柄要建 map 节点，也可能抛 bad_alloc。异常绝不能越过 JNI 边界，所以这里接住：
    // 登记失败就当「这一轮没存下」，把在途缓冲丢掉，生成照常继续。
    try {
        engine.checkpointStates[handle] = std::move(engine.pendingCheckpoint);
    } catch (...) {
        std::vector<uint8_t>().swap(engine.pendingCheckpoint);
        engine.pendingCheckpointPos = -1;
        return -1;
    }
    // move 之后 pending 的内容已归新那份，这里只把容量还回去，避免留下空壳占着内存。
    std::vector<uint8_t>().swap(engine.pendingCheckpoint);
    engine.pendingCheckpointPos = -1;
    return handle;
}

/** 某个句柄现在占多少字节（元数据用；句柄不存在给 -1）。 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_checkpointHandleBytes(JNIEnv *, jobject, jint handle) {
    const auto found = engine.checkpointStates.find(handle);
    if (found == engine.checkpointStates.end()) return -1;
    return static_cast<jlong>(found->second.size());
}

/** 释放一个句柄占的原生内存（作废、淘汰、卸载都要调）。 */
extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_releaseCheckpointHandle(JNIEnv *, jobject, jint handle) {
    engine.checkpointStates.erase(handle);
}

/**
 * 丢掉待取的检查点（全量审 R2 必修项 2）。
 *
 * 与 `takeCheckpoint()` 的区别：**不新建 JByteArray、不抛异常**。清理路径（异常收尾、低内存丢弃）
 * 必须用它——在「正要处理异常」时再去做一次几十 MB 的分配，可能把原来的异常替换成 OOM。
 */
/** 编译时选定的指令集变体（Kotlin 侧用来确认到底加载了哪一份）。 */
/** 只给真机诊断：限制预填批次大小（0 = 默认 512）。 */
extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_setCheckpointCut(JNIEnv *, jobject, jint cut) {
    engine.checkpointCut = cut;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_setPromptBatch(JNIEnv *, jobject, jint size) {
    engine.promptBatch = size > 0 ? size : 0;
}

/** 全量审 P2-8：返回 [存活句柄数, 句柄持有总字节]——让测例能看见原生侧的泄漏。 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_handlesAlive(JNIEnv * env, jobject) {
    long bytes = 0;
    for (const auto & entry : engine.checkpointStates) bytes += static_cast<long>(entry.second.size());
    const jlong values[] = {static_cast<jlong>(engine.checkpointStates.size()), bytes};
    auto result = env->NewLongArray(2);
    if (!result) return nullptr;
    env->SetLongArrayRegion(result, 0, 2, values);
    if (env->ExceptionCheck()) return nullptr;
    return result;
}

/** 编译时选定的指令集变体（Kotlin 侧用来确认到底加载了哪一份）。 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_archVariant(JNIEnv * env, jobject) {
    return env->NewStringUTF(MYCELIUM_ARCH_VARIANT);
}

/** P2-1d 诊断：返回 [清空序列 ms, set_data_ext ms, get_size_ext ms, get_data_ext ms, 取字节 JNI 拷贝 ms]。 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_checkpointTimings(JNIEnv * env, jobject) {
    auto result = env->NewLongArray(5);
    if (!result) return nullptr;
    env->SetLongArrayRegion(result, 0, 5, engine.timing);
    if (env->ExceptionCheck()) return nullptr;
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_discardCheckpoint(JNIEnv *, jobject) {
    std::vector<uint8_t>().swap(engine.pendingCheckpoint);
    engine.pendingCheckpointPos = -1;
}

/**
 * 这一次待取的检查点对应哪个位置；没有就是 -1。
 *
 * 判据是 `pendingCheckpointPos`，**不是** `pendingCheckpoint.empty()`：字节被 `takeCheckpoint()`
 * 取走之后位置就该失效，所以谁先被调用都不影响结果（第一版判据用错，Kotlin 先取字节再问位置，
 * 位置永远是 -1，检查点因此永远进不了仓库）。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_takeCheckpointPos(JNIEnv *, jobject) {
    return engine.pendingCheckpointPos;
}

/*
 * P2-1d 的落点计算（方案 2.1）：返回 [带 generation prompt 的 token 数, 不带 generation prompt 的 token 数]。
 *
 * 两者之差就是模板给助手头加的那几个 token。落点 = **不带**的那个数：状态停在「用户这一轮结束、
 * 生成脚手架之前」，正好与下一轮的真实公共前缀对齐。**不写死常数**，换模型/换模板都对。
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_tokenizeBoundary(
    JNIEnv * env, jobject, jbyteArray messages) {
    try {
        if (!engine.model) throw std::runtime_error("Load a model first");
        const auto payload = bytes(env, messages);
        const int withGeneration = static_cast<int>(prompt_tokens(prompt(payload, true)).size());
        const int withoutGeneration = static_cast<int>(prompt_tokens(prompt(payload, false)).size());
        const jlong values[] = {withGeneration, withoutGeneration};
        auto result = env->NewLongArray(2);
        if (!result) return nullptr;
        env->SetLongArrayRegion(result, 0, 2, values);
        if (env->ExceptionCheck()) return nullptr;
        return result;
    } catch (...) { return nullptr; }
}

/** 上一次恢复失败在哪一步：0 成功、1 `set_data_ext` 短读、2 `seq_rm` 返回 false、-1 没试过。 */
extern "C" JNIEXPORT jint JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_lastRestoreStep(JNIEnv *, jobject) {
    return engine.lastRestoreStep;
}

/*
 * P2-1d 第 0 步的**只做测量**探针（统筹 2026-10-23 裁定）。
 *
 * 回答一个问题：把检查点从「只存循环层（PARTIAL_ONLY）」改成「整份状态（NONE，注意力 KV + 循环层）」，
 * 体积与耗时各是多少。方案里「约 24 倍」的推算统筹不认，要以实测为准。
 *
 * 做法：对**当前**的序列 0 状态（调用方刚读完一段提示之后）分别取两份大小、各存一次、各恢复一次，
 * 记录耗时。**最后一定把整份状态装回去**，所以探针跑完引擎状态不变，不影响后续生成。
 *
 * 返回 10 项：
 *  0 size(NONE)      1 size(PARTIAL_ONLY)
 *  2 存 NONE 毫秒     3 存 PARTIAL_ONLY 毫秒
 *  4 恢复 NONE 毫秒   5 恢复 PARTIAL_ONLY 毫秒
 *  6 written(NONE)   7 written(PARTIAL_ONLY)
 *  8 read(NONE)      9 read(PARTIAL_ONLY)   —— 与对应 written 不等就说明这一侧没成
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_measureCheckpoints(JNIEnv * env, jobject) {
    try {
        if (!engine.context) return nullptr;
        auto * ctx = engine.context;
        const size_t size_none = llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_NONE);
        const size_t size_partial = llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        std::vector<uint8_t> full(size_none > 0 ? size_none : 1);
        std::vector<uint8_t> partial(size_partial > 0 ? size_partial : 1);

        const auto t_save_full = Clock::now();
        const size_t written_full = llama_state_seq_get_data_ext(
            ctx, full.data(), full.size(), 0, LLAMA_STATE_SEQ_FLAGS_NONE);
        const long ms_save_full = elapsed(t_save_full);

        const auto t_save_partial = Clock::now();
        const size_t written_partial = llama_state_seq_get_data_ext(
            ctx, partial.data(), partial.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        const long ms_save_partial = elapsed(t_save_partial);

        // 先恢复小的那份，最后恢复整份：跑完之后引擎状态与进来时一致。
        const auto t_restore_partial = Clock::now();
        const size_t read_partial = llama_state_seq_set_data_ext(
            ctx, partial.data(), written_partial, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
        const long ms_restore_partial = elapsed(t_restore_partial);

        const auto t_restore_full = Clock::now();
        const size_t read_full = llama_state_seq_set_data_ext(
            ctx, full.data(), written_full, 0, LLAMA_STATE_SEQ_FLAGS_NONE);
        const long ms_restore_full = elapsed(t_restore_full);

        const jlong values[] = {
            static_cast<jlong>(size_none), static_cast<jlong>(size_partial),
            ms_save_full, ms_save_partial, ms_restore_full, ms_restore_partial,
            static_cast<jlong>(written_full), static_cast<jlong>(written_partial),
            static_cast<jlong>(read_full), static_cast<jlong>(read_partial),
        };
        auto result = env->NewLongArray(10);
        if (!result) return nullptr;
        env->SetLongArrayRegion(result, 0, 10, values);
        if (env->ExceptionCheck()) return nullptr;
        return result;
    } catch (...) { return nullptr; }
}

/*
 * 把一串 token id 还原成文本（方案审必修项 1）。
 *
 * 只给「边界到底落在哪」的取证用：真机日志里要能看到 1116 与 1120 各自解码出什么，才能说清
 * 那个位置是「助手角色头之前」还是「助手头之后、思考段之前」——光比长度证明不了这件事。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_decodeTokens(JNIEnv * env, jobject, jintArray ids) {
    try {
        if (!engine.model || !ids) return nullptr;
        const jsize n = env->GetArrayLength(ids);
        std::vector<jint> raw(static_cast<size_t>(n));
        env->GetIntArrayRegion(ids, 0, n, raw.data());
        if (env->ExceptionCheck()) return nullptr;
        const auto * vocab = llama_model_get_vocab(engine.model);
        std::string text;
        for (jsize i = 0; i < n; ++i) {
            std::array<char, 8> buf = {};
            // special=true：角色头这类标记是**特殊 token**，special=false 时会被吞成空串，
            // 那样就看不出「边界到底在助手头之前还是之后」——取证必须留下它们。
            // 全量审 P2-11：缓冲不足时 llama 返回**负的所需长度**，固定 8 字节会把长特殊 token
            // 静默吞掉（边界取证因此被污染）。不足即按所需长度扩容重试。
            int written = llama_token_to_piece(
                vocab, static_cast<llama_token>(raw[i]), buf.data(), static_cast<int>(buf.size()), 0, true);
            if (written < 0) {
                std::string big(static_cast<size_t>(-written) + 1, '\0');
                written = llama_token_to_piece(
                    vocab, static_cast<llama_token>(raw[i]), big.data(), static_cast<int>(big.size()), 0, true);
                if (written > 0) text.append(big.data(), static_cast<size_t>(written));
            } else if (written > 0) {
                text.append(buf.data(), static_cast<size_t>(written));
            }
        }
        return env->NewStringUTF(text.c_str());
    } catch (...) { return nullptr; }
}




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
    ~Engine() { clear(); }
    void clear() {
        cachedPrompt.clear();
        if (context) llama_free(context);
        if (model) llama_model_free(model);
        if (file) fclose(file);
        context = nullptr; model = nullptr; file = nullptr;
    }
} engine;

using Clock = std::chrono::steady_clock;

// P2-1c：一轮最多能把循环状态回退多远（见 load() 与 generate()）。要回退得比这更多的一轮，
// 直接整段重读。目标模型（qwen35 这类混合状态模型）没有这个窗口就完全没法裁尾：llama.cpp 的
// 循环内存注释写着「Mamba / RWKV 这类模型的状态无法在序列末尾被部分擦除」，于是每一轮都会
// 退回整段重读。窗口取值要盖住「上一轮生成出来的 token 数 + 模板多出来的那几个」；默认生成
// 预算（maxTokens）是 256。
constexpr uint32_t ROLLBACK_TOKENS = 320;
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
std::string prompt(const std::string & messages) {
    const char * source = llama_model_chat_template(engine.model, nullptr);
    if (!source || !*source) throw std::runtime_error("The model has no chat template");
    const auto * vocab = llama_model_get_vocab(engine.model);
    auto variables = common_json::object();
    variables["messages"] = common_json::parse(messages);
    variables["add_generation_prompt"] = true;
    variables["enable_thinking"] = false;
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
// 除非拥有它的那一轮正常走到结尾，否则把记住的提示丢掉。所有提前 return 与异常出口都会经过
// 这个析构，所以没有任何一条出路能把「引擎无法担保的状态」留给下一轮（方案审必修第 4 条）。
struct PromptGuard {
    bool committed = false;
    ~PromptGuard() { if (!committed) engine.cachedPrompt.clear(); }
};
}

extern "C" JNIEXPORT void JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_begin(JNIEnv *, jobject) { cancelled.store(false); }
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
        cp.n_batch = ROLLBACK_TOKENS + 1;
        cp.n_ubatch = ROLLBACK_TOKENS + 1;
        cp.n_rs_seq = ROLLBACK_TOKENS;
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
    jfloat top_p, jint seed, jint reuseTokens, jobject callback) {
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
        int reuse = std::min(requested, shared_prefix(engine.cachedPrompt, tokens));
        if (reuse > count - 1) reuse = count - 1;
        if (reuse < 0) reuse = 0;
        // Reported to the caller so "why did this round still read everything?" has an answer:
        // 1 = nothing was asked for, 2 = nothing shareable was held, 3 = this build refused to drop
        // only a suffix (the rollback window is too small for this round).
        jlong fallback = 0;
        auto * memory = llama_get_memory(engine.context);
        bool trimmed = false;
        if (reuse > 0) trimmed = llama_memory_seq_rm(memory, 0, reuse, -1);
        if (!trimmed) {
            // Nothing to reuse, or this build cannot drop only a suffix: read the whole prompt, which
            // is what every round did before this cache existed.
            llama_memory_clear(memory, true);
            fallback = requested == 0 ? 1 : (reuse == 0 ? 2 : 3);
            reuse = 0;
        }
        auto start = Clock::now();
        int read = 0;
        for (int pos = reuse; pos < count && !cancelled; pos += 128) {
            const int length = std::min(128, count - pos);
            auto batch = llama_batch_get_one(tokens.data() + pos, length);
            if (llama_decode(engine.context, batch) != 0) {
                if (cancelled) break;
                engine.cachedPrompt.clear();
                throw std::runtime_error("Prompt evaluation failed");
            }
            read += length;
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
                if (cancelled) break;
                throw std::runtime_error("Generation failed");
            }
        }
        // {prompt tokens, generated, prompt ms (the part actually read), generation ms, stopped,
        //  tokens reused from the cache, tokens actually read} — the caller records the last two.
        // {prompt tokens, generated, prompt ms (the part actually read), generation ms, stopped,
        //  tokens reused from the cache, tokens actually read, why a reuse did not happen}
        guard.committed = true;
        jlong values[] = {count, generated, prompt_ms, elapsed(start), cancelled ? 1 : 0, reuse, read, fallback};
        auto result = env->NewLongArray(8);
        if (result) env->SetLongArrayRegion(result, 0, 8, values);
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




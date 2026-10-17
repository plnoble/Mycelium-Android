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
    ~Engine() { clear(); }
    void clear() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
        if (file) fclose(file);
        context = nullptr; model = nullptr; file = nullptr;
    }
} engine;

using Clock = std::chrono::steady_clock;
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
        cp.n_batch = 128;
        cp.n_ubatch = 128;
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xavierxia_mycelium_llm_NativeBridge_generate(
    JNIEnv * env, jobject, jbyteArray messages, jint maximum, jfloat temperature,
    jfloat top_p, jint seed, jobject callback) {
    try {
        if (!engine.context) throw std::runtime_error("Load a model first");
        if (maximum < 1 || maximum > 8192) throw std::runtime_error("Invalid token limit");
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
        int count = -llama_tokenize(vocab, formatted.data(), formatted.size(), nullptr, 0, true, true);
        if (count <= 0 || static_cast<uint64_t>(count) + maximum > llama_n_ctx(engine.context))
            throw std::runtime_error("Messages and token limit exceed context length");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, formatted.data(), formatted.size(), tokens.data(), count, true, true) != count)
            throw std::runtime_error("Tokenization failed");
        llama_memory_clear(llama_get_memory(engine.context), true);
        auto start = Clock::now();
        for (int pos = 0; pos < count && !cancelled; pos += 128) {
            const int length = std::min(128, count - pos);
            auto batch = llama_batch_get_one(tokens.data() + pos, length);
            if (llama_decode(engine.context, batch) != 0) {
                if (cancelled) break;
                throw std::runtime_error("Prompt evaluation failed");
            }
        }
        long prompt_ms = elapsed(start);
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
        jlong values[] = {count, generated, prompt_ms, elapsed(start), cancelled ? 1 : 0};
        auto result = env->NewLongArray(5);
        if (result) env->SetLongArrayRegion(result, 0, 5, values);
        return result;
    } catch (const std::exception & e) {
        // Deliberately exclude template/message excerpts from error output.
        const std::string message = e.what();
        fail(env, message == "Messages and token limit exceed context length" ? message.c_str() :
            "Generation failed; check the model chat template and context");
    } catch (...) { fail(env, "Native generation failed"); }
    return nullptr;
}




// Minimal JNI bridge over the llama.cpp C API: load a GGUF model and run
// greedy completion for a fully formatted prompt.
#include <jni.h>
#include <android/log.h>

#include <memory>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "StenoLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LlamaSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;

    ~LlamaSession() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }
};

struct SamplerDeleter {
    void operator()(llama_sampler *s) const { llama_sampler_free(s); }
};

std::string jstringToStd(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int32_t n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, true, true) < 0) {
        return {};
    }
    return tokens;
}

std::string tokenToPiece(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, false);
    return n > 0 ? std::string(buf, n) : std::string();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_hexteam_stenographer_llm_LlamaNative_nativeLoad(
        JNIEnv *env, jobject, jstring jpath, jint nCtx, jint nThreads) {
    llama_backend_init();
    const std::string path = jstringToStd(env, jpath);

    auto session = std::make_unique<LlamaSession>();
    llama_model_params mparams = llama_model_default_params();
    session->model = llama_model_load_from_file(path.c_str(), mparams);
    if (!session->model) {
        LOGE("failed to load model %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_batch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    session->ctx = llama_init_from_model(session->model, cparams);
    if (!session->ctx) {
        LOGE("failed to create context");
        return 0;
    }
    LOGI("model loaded: %s", path.c_str());
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_hexteam_stenographer_llm_LlamaNative_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jint maxTokens, jfloat repeatPenalty) {
    auto *session = reinterpret_cast<LlamaSession *>(handle);
    if (!session) return nullptr;

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    llama_memory_clear(llama_get_memory(session->ctx), true);

    std::vector<llama_token> prompt = tokenize(vocab, jstringToStd(env, jprompt));
    const int32_t nCtx = (int32_t) llama_n_ctx(session->ctx);
    if (prompt.empty() || (int32_t) prompt.size() + maxTokens > nCtx) {
        LOGE("prompt too long: %zu tokens, ctx %d", prompt.size(), nCtx);
        return nullptr;
    }

    const int32_t batchSize = 512;
    for (size_t i = 0; i < prompt.size(); i += batchSize) {
        int32_t n = (int32_t) std::min<size_t>(batchSize, prompt.size() - i);
        if (llama_decode(session->ctx, llama_batch_get_one(prompt.data() + i, n)) != 0) {
            LOGE("prompt decode failed");
            return nullptr;
        }
    }

    std::unique_ptr<llama_sampler, SamplerDeleter> sampler(
            llama_sampler_chain_init(llama_sampler_chain_default_params()));
    if (repeatPenalty > 1.0f) {
        // Greedy decoding of small models loops easily; penalize recent repeats.
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab), 256, repeatPenalty, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());

    std::string output;
    for (int i = 0; i < maxTokens; ++i) {
        llama_token token = llama_sampler_sample(sampler.get(), session->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        output += tokenToPiece(vocab, token);
        if (llama_decode(session->ctx, llama_batch_get_one(&token, 1)) != 0) {
            LOGE("generation decode failed");
            break;
        }
    }

    // Return raw UTF-8 bytes: NewStringUTF expects modified UTF-8.
    jbyteArray result = env->NewByteArray((jsize) output.size());
    env->SetByteArrayRegion(result, 0, (jsize) output.size(),
                            reinterpret_cast<const jbyte *>(output.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_hexteam_stenographer_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<LlamaSession *>(handle);
}

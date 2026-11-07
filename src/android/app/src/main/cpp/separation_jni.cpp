// Vocal/accompaniment separation (UVR MDX-Net) through the sherpa-onnx C API.
// The sherpa-onnx AAR ships libsherpa-onnx-c-api.so but no Kotlin binding for
// source separation, so the needed functions are resolved with dlopen/dlsym.
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

#include <cstdint>
#include <vector>

#define LOG_TAG "StenoSeparation"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Mirrors of the structs in sherpa-onnx/c-api/c-api.h (v2.0.1).
struct SpleeterConfig {
    const char *vocals;
    const char *accompaniment;
};
struct UvrConfig {
    const char *model;
};
struct ModelConfig {
    SpleeterConfig spleeter;
    UvrConfig uvr;
    int32_t num_threads;
    int32_t debug;
    const char *provider;
};
struct Config {
    ModelConfig model;
};
struct Stem {
    float **samples;
    int32_t num_channels;
    int32_t n;
};
struct Output {
    const Stem *stems;
    int32_t num_stems;
    int32_t sample_rate;
};

using CreateFn = const void *(*)(const Config *);
using DestroyFn = void (*)(const void *);
using ProcessFn = const Output *(*)(const void *, const float *const *, int32_t, int32_t, int32_t);
using DestroyOutputFn = void (*)(const Output *);

struct Api {
    CreateFn create = nullptr;
    DestroyFn destroy = nullptr;
    ProcessFn process = nullptr;
    DestroyOutputFn destroyOutput = nullptr;

    bool load() {
        if (create) return true;
        void *lib = dlopen("libsherpa-onnx-c-api.so", RTLD_NOW);
        if (!lib) {
            LOGE("dlopen failed: %s", dlerror());
            return false;
        }
        create = reinterpret_cast<CreateFn>(dlsym(lib, "SherpaOnnxCreateOfflineSourceSeparation"));
        destroy = reinterpret_cast<DestroyFn>(dlsym(lib, "SherpaOnnxDestroyOfflineSourceSeparation"));
        process = reinterpret_cast<ProcessFn>(dlsym(lib, "SherpaOnnxOfflineSourceSeparationProcess"));
        destroyOutput = reinterpret_cast<DestroyOutputFn>(dlsym(lib, "SherpaOnnxDestroySourceSeparationOutput"));
        return create && destroy && process && destroyOutput;
    }
};

Api api;

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ru_hexteam_stenographer_audio_VocalSeparatorNative_nativeCreate(
        JNIEnv *env, jobject, jstring jmodel, jint threads) {
    if (!api.load()) return 0;
    const char *model = env->GetStringUTFChars(jmodel, nullptr);
    Config config{};
    config.model.uvr.model = model;
    config.model.num_threads = threads;
    config.model.provider = "cpu";
    const void *separator = api.create(&config);
    env->ReleaseStringUTFChars(jmodel, model);
    if (!separator) LOGE("failed to create separator");
    return reinterpret_cast<jlong>(separator);
}

/**
 * Separates one stereo chunk (two planar channels) and returns the vocals stem
 * downmixed to mono, prefixed with its sample rate: [rate, s0, s1, ...].
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_ru_hexteam_stenographer_audio_VocalSeparatorNative_nativeProcess(
        JNIEnv *env, jobject, jlong handle, jfloatArray jleft, jfloatArray jright, jint sampleRate) {
    const void *separator = reinterpret_cast<const void *>(handle);
    if (!separator) return nullptr;

    const jsize frames = env->GetArrayLength(jleft);
    std::vector<float> left(frames), right(frames);
    env->GetFloatArrayRegion(jleft, 0, frames, left.data());
    env->GetFloatArrayRegion(jright, 0, frames, right.data());
    const float *planes[2] = {left.data(), right.data()};

    const Output *out = api.process(separator, planes, 2, frames, sampleRate);
    jfloatArray result = nullptr;
    if (out && out->num_stems > 0) {
        const Stem &vocals = out->stems[0];  // UVR vocal models: stem 0 is the target (vocals)
        std::vector<float> mono(vocals.n + 1);
        mono[0] = static_cast<float>(out->sample_rate);
        for (int32_t i = 0; i < vocals.n; ++i) {
            float sum = 0.f;
            for (int32_t c = 0; c < vocals.num_channels; ++c) sum += vocals.samples[c][i];
            mono[i + 1] = sum / vocals.num_channels;
        }
        result = env->NewFloatArray(static_cast<jsize>(mono.size()));
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(mono.size()), mono.data());
    } else {
        LOGE("separation failed");
    }
    if (out) api.destroyOutput(out);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_ru_hexteam_stenographer_audio_VocalSeparatorNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    if (handle) api.destroy(reinterpret_cast<const void *>(handle));
}

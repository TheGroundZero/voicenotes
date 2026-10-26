// SPDX-License-Identifier: GPL-3.0-or-later
#include <android/log.h>
#include <asm/hwcap.h>
#include <sys/auxv.h>
#include <jni.h>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"
#include "whisper_core.h"

// The transcription itself lives in whisper_core.cpp, shared with the benchmark tool. Text goes back to Kotlin as UTF-8
// bytes (a JSON object): segment boundaries can split multi-byte characters, which NewStringUTF() does not tolerate.
namespace {
void log_to_android(ggml_log_level level, const char *text, void *) {
    // Only warnings and errors by default; informational lines (including which processor variant was loaded) at INFO.
    const int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                       : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                                       : ANDROID_LOG_INFO;
    __android_log_print(priority, "ggml", "%s", text);
}
}  // namespace

extern "C" {

// Loads the processor-specific CPU backend that suits this phone. ggml can choose by itself
// (ggml_backend_load_all_from_path), but on the Pixel 10 it picks the SVE2 variant, which is about twice as slow as
// the plain NEON variant with the i8mm instructions (the SVE vectors are only 128 bits wide there). So the choice is
// made here, from the capabilities the kernel reports, in this order of preference, and the first library that loads
// is used. The baseline variant runs on every 64-bit ARM phone.
JNIEXPORT void JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeLoadBackends(JNIEnv *env, jobject, jstring dir) {
    whisper_log_set(log_to_android, nullptr);
    ggml_log_set(log_to_android, nullptr);

    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    const bool dotprod = hwcap & HWCAP_ASIMDDP;
    const bool fp16 = hwcap & HWCAP_ASIMDHP;
    const bool i8mm = hwcap2 & HWCAP2_I8MM;
    __android_log_print(ANDROID_LOG_INFO, "voicenotes", "cpu: dotprod=%d fp16=%d i8mm=%d", dotprod, fp16, i8mm);

    std::vector<const char *> order;
    if (dotprod && fp16 && i8mm) order.push_back("android_armv8.6_1");
    if (dotprod && fp16) order.push_back("android_armv8.2_2");
    if (dotprod) order.push_back("android_armv8.2_1");
    order.push_back("android_armv8.0_1");

    const char *base = env->GetStringUTFChars(dir, nullptr);
    for (const char *variant : order) {
        const std::string path = std::string(base) + "/libggml-cpu-" + variant + ".so";
        if (ggml_backend_load(path.c_str()) != nullptr) {
            __android_log_print(ANDROID_LOG_INFO, "voicenotes", "CPU backend: %s", variant);
            break;
        }
    }
    env->ReleaseStringUTFChars(dir, base);
}

JNIEXPORT jlong JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

// Transcribes one note (16 kHz mono floats): speech check, window sizing, language detection among `preferred`, beam
// search. `vadModelPath` may be null to skip the speech check. Returns vn::to_json(result) as UTF-8 bytes.
JNIEXPORT jbyteArray JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeTranscribeNote(JNIEnv *env, jobject, jlong handle, jfloatArray samples,
                                                      jstring language, jobjectArray preferred,
                                                      jstring vadModelPath, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    vn::Options options;
    options.threads = threads;
    auto to_string = [&](jstring s) {
        const char *c = env->GetStringUTFChars(s, nullptr);
        std::string out(c);
        env->ReleaseStringUTFChars(s, c);
        return out;
    };
    options.language = to_string(language);
    const jsize count = env->GetArrayLength(preferred);
    for (jsize i = 0; i < count; i++) {
        auto code = (jstring) env->GetObjectArrayElement(preferred, i);
        options.preferred.push_back(to_string(code));
        env->DeleteLocalRef(code);
    }
    if (vadModelPath != nullptr) options.vad_model_path = to_string(vadModelPath);

    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const std::vector<float> pcm(data, data + env->GetArrayLength(samples));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    const std::string json = vn::to_json(vn::run(ctx, pcm, options));
    jbyteArray out = env->NewByteArray(static_cast<jsize>(json.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(json.size()), reinterpret_cast<const jbyte *>(json.data()));
    return out;
}

JNIEXPORT void JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

}

// SPDX-License-Identifier: GPL-3.0-or-later
#include <android/log.h>
#include <asm/hwcap.h>
#include <sys/auxv.h>
#include <jni.h>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

// Returns the transcript as UTF-8 bytes: segment boundaries can split multi-byte
// characters, which NewStringUTF() does not tolerate.
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

JNIEXPORT jbyteArray JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject, jlong handle,
                                                  jfloatArray samples, jstring language,
                                                  jint threads, jint audioCtx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const jsize n = env->GetArrayLength(samples);

    // Beam search, not greedy: in the M0 benchmark the large models silently dropped the first 30 s
    // of a noisy clip with greedy decoding; beam size 5 fixed it (beam search was found necessary in benchmarks on large models).
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
    params.language = lang;
    params.n_threads = threads;
    params.audio_ctx = audioCtx; // 0 = the full 30 s window; short notes use a shorter one (much faster)
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    std::string text;
    if (whisper_full(ctx, params, data, n) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) text += whisper_full_get_segment_text(ctx, i);
    }

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

// Probability of each candidate language code, in the order given (0 for codes whisper does not know).
// Only the first ~30 s of audio are used. Restricting the choice to candidates is done by the caller.
JNIEXPORT jfloatArray JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeDetectLanguage(JNIEnv *env, jobject, jlong handle,
                                                      jfloatArray samples, jobjectArray codes,
                                                      jint threads, jint audioCtx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const jsize n = env->GetArrayLength(samples);

    // Needs the Voice Notes patch to whisper.cpp (third_party/patches): upstream detection always encodes 30 s.
    whisper_set_audio_ctx(ctx, audioCtx);
    std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
    bool ok = whisper_pcm_to_mel(ctx, data, n, threads) == 0 &&
              whisper_lang_auto_detect(ctx, 0, threads, probs.data()) >= 0;
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    const jsize count = env->GetArrayLength(codes);
    jfloatArray out = env->NewFloatArray(count);
    std::vector<float> scores(count, 0.0f);
    for (jsize i = 0; i < count && ok; i++) {
        auto code = (jstring) env->GetObjectArrayElement(codes, i);
        const char *c = env->GetStringUTFChars(code, nullptr);
        const int id = whisper_lang_id(c);
        if (id >= 0) scores[i] = probs[id];
        env->ReleaseStringUTFChars(code, c);
        env->DeleteLocalRef(code);
    }
    env->SetFloatArrayRegion(out, 0, count, scores.data());
    return out;
}

// Seconds of speech Silero VAD finds in the audio (segments of at least 250 ms), or -1 if the check could
// not run. Used only to decide whether a note is worth transcribing at all.
JNIEXPORT jfloat JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeSpeechSeconds(JNIEnv *env, jobject, jstring modelPath,
                                                     jfloatArray samples, jfloat threshold,
                                                     jint threads) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_vad_context_params cparams = whisper_vad_default_context_params();
    cparams.use_gpu = false;
    cparams.n_threads = threads;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (vctx == nullptr) return -1.0f;

    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const jsize n = env->GetArrayLength(samples);
    whisper_vad_params vparams = whisper_vad_default_params();
    vparams.threshold = threshold;
    whisper_vad_segments *segments = whisper_vad_segments_from_samples(vctx, vparams, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    float seconds = -1.0f;
    if (segments != nullptr) {
        seconds = 0.0f;
        for (int i = 0; i < whisper_vad_segments_n_segments(segments); i++) {
            // Segment times are in centiseconds.
            seconds += (whisper_vad_segments_get_segment_t1(segments, i) -
                        whisper_vad_segments_get_segment_t0(segments, i)) / 100.0f;
        }
        whisper_vad_free_segments(segments);
    }
    whisper_vad_free(vctx);
    return seconds;
}

JNIEXPORT void JNICALL
Java_be_sequr_voicenotes_WhisperBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

}

// SPDX-License-Identifier: GPL-3.0-or-later
#include "whisper_core.h"

#include <algorithm>
#include <chrono>

namespace vn {
namespace {
double seconds_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
}

std::string trim(const std::string &s) {
    const char *space = " \t\r\n\f\v";
    const auto begin = s.find_first_not_of(space);
    if (begin == std::string::npos) return "";
    return s.substr(begin, s.find_last_not_of(space) - begin + 1);
}
}  // namespace

float speech_seconds(const std::string &vad_model_path, const float *samples, std::size_t count, float threshold, int threads) {
    whisper_vad_context_params cparams = whisper_vad_default_context_params();
    cparams.use_gpu = false;
    cparams.n_threads = threads;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(vad_model_path.c_str(), cparams);
    if (vctx == nullptr) return -1.0f;

    whisper_vad_params vparams = whisper_vad_default_params();
    vparams.threshold = threshold;
    whisper_vad_segments *segments = whisper_vad_segments_from_samples(vctx, vparams, samples, static_cast<int>(count));
    float seconds = -1.0f;
    if (segments != nullptr) {
        seconds = 0.0f;
        for (int i = 0; i < whisper_vad_segments_n_segments(segments); i++) {
            // Segment times are in centiseconds.
            seconds += (whisper_vad_segments_get_segment_t1(segments, i) - whisper_vad_segments_get_segment_t0(segments, i)) / 100.0f;
        }
        whisper_vad_free_segments(segments);
    }
    whisper_vad_free(vctx);
    return seconds;
}

std::vector<float> language_scores(whisper_context *ctx, const float *samples, std::size_t count,
                                   const std::vector<std::string> &codes, int threads, int audio_ctx) {
    // Needs the Voice Notes patch to whisper.cpp (third_party/patches): upstream detection always encodes 30 s.
    whisper_set_audio_ctx(ctx, audio_ctx);
    std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
    const bool ok = whisper_pcm_to_mel(ctx, samples, static_cast<int>(count), threads) == 0 &&
                    whisper_lang_auto_detect(ctx, 0, threads, probs.data()) >= 0;
    if (!ok) return {};
    std::vector<float> scores(codes.size(), 0.0f);
    for (std::size_t i = 0; i < codes.size(); i++) {
        const int id = whisper_lang_id(codes[i].c_str());
        if (id >= 0) scores[i] = probs[id];
    }
    return scores;
}

bool transcribe(whisper_context *ctx, const float *samples, std::size_t count, const std::string &language, int threads,
                int audio_ctx, std::string *text) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
    params.language = language.c_str();
    params.n_threads = threads;
    params.audio_ctx = audio_ctx;  // 0 = the full 30 s window; short notes use a shorter one (much faster)
    params.translate = false;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;

    text->clear();
    if (whisper_full(ctx, params, samples, static_cast<int>(count)) != 0) return false;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) *text += whisper_full_get_segment_text(ctx, i);
    return true;
}

Result run(whisper_context *ctx, const std::vector<float> &samples, const Options &options) {
    Result result;
    const bool detect = options.language == "auto";
    const std::string fallback = options.preferred.empty() ? std::string("en") : options.preferred.front();

    if (!options.vad_model_path.empty()) {
        std::vector<float> for_check = samples;
        std::fill(for_check.begin(), for_check.begin() + std::min(for_check.size(), kStartBeepSamples), 0.0f);
        const auto started = std::chrono::steady_clock::now();
        result.speech_seconds = speech_seconds(options.vad_model_path, for_check.data(), for_check.size(), options.vad_threshold, options.vad_threads);
        result.vad_seconds = seconds_since(started);
        if (result.speech_seconds == 0.0f) {  // a failed check (-1) fails open: the note is transcribed as usual
            result.language = detect ? fallback : options.language;
            result.no_speech = true;
            return result;
        }
    }

    result.audio_ctx = options.audio_ctx >= 0 ? options.audio_ctx : audio_context_for_samples(samples.size());
    result.detected = detect;
    const auto detect_started = std::chrono::steady_clock::now();
    if (!detect) {
        result.language = options.language;
    } else if (options.preferred.size() <= 1) {
        result.language = fallback;
    } else {
        // Whisper scores every language it knows; only the preferred ones are looked at, so a short or noisy clip cannot
        // come back as an unrelated language. If even the best preferred score is weak, the first preferred language is used.
        const int detect_ctx = options.detect_audio_ctx == -2 ? result.audio_ctx
                             : options.detect_audio_ctx == -1 ? audio_context_for_samples(samples.size()) : options.detect_audio_ctx;
        const std::vector<float> scores = language_scores(ctx, samples.data(), samples.size(), options.preferred, options.threads, detect_ctx);
        for (std::size_t i = 0; i < scores.size(); i++) result.language_scores.emplace_back(options.preferred[i], scores[i]);
        std::size_t best = 0;
        for (std::size_t i = 1; i < scores.size(); i++) if (scores[i] > scores[best]) best = i;
        result.language = (!scores.empty() && scores[best] >= options.min_language_confidence) ? options.preferred[best] : fallback;
    }
    result.detect_seconds = seconds_since(detect_started);

    const auto transcribe_started = std::chrono::steady_clock::now();
    std::string text;
    result.failed = !transcribe(ctx, samples.data(), samples.size(), result.language, options.threads, result.audio_ctx, &text);
    result.text = trim(text);
    result.transcribe_seconds = seconds_since(transcribe_started);
    return result;
}

}  // namespace vn

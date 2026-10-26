// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <string>
#include <vector>

#include "audio_context.h"
#include "vn_result.h"
#include "whisper.h"

// The transcription of one note, used by the app (through JNI) and by the benchmark tool, so that both do exactly the
// same thing: speech check, window sizing, language detection among the preferred languages, beam search. No Android
// types in here.
namespace vn {

struct Options {
    std::string language = "auto";           // an ISO 639-1 code, or "auto" to detect it among `preferred`
    std::vector<std::string> preferred;      // the first one is the fallback when the detection is not confident
    std::string vad_model_path;              // empty: no speech check
    int threads = 4;                         // 8 was not faster in the M0 benchmark
    int vad_threads = 2;
    float vad_threshold = 0.35f;             // more sensitive than the library default (0.5), so quiet speech is less likely missed
    float min_language_confidence = 0.15f;   // probability (out of ~99 languages) the best preferred language must reach
};

// The app's own start beep is recorded at the start of every note. The speech check ignores it (it sees the audio with
// the first 300 ms silenced), but Whisper gets the audio as recorded: silencing the start for Whisper made it repeat a
// one-word note 11 times, while the beep does no harm.
constexpr std::size_t kStartBeepSamples = kSampleRate * 3 / 10;

// Seconds of speech Silero VAD finds in the audio (segments of at least 250 ms), or -1 if the check could not run.
float speech_seconds(const std::string &vad_model_path, const float *samples, std::size_t count, float threshold, int threads);

// Probability of each language code, in the order given (0 for codes whisper does not know); empty on failure.
std::vector<float> language_scores(whisper_context *ctx, const float *samples, std::size_t count,
                                   const std::vector<std::string> &codes, int threads, int audio_ctx);

// The text of the note, beam search (size 5). Beam search, not greedy: in the M0 benchmark the large models silently
// dropped the first 30 s of a noisy clip with greedy decoding.
bool transcribe(whisper_context *ctx, const float *samples, std::size_t count, const std::string &language, int threads,
                int audio_ctx, std::string *text);

// The whole pipeline for 16 kHz mono float samples.
Result run(whisper_context *ctx, const std::vector<float> &samples, const Options &options);

}  // namespace vn

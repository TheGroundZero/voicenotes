// SPDX-License-Identifier: GPL-3.0-or-later
// Tests for the parts of the shared transcription core that need no whisper.cpp: the audio-window sizing and the JSON
// the app and the benchmark tool exchange. Build and run with scripts/check.sh (or: g++ -std=c++17 -I app/src/main/cpp ...).
#include <cstdio>
#include <string>

#include "audio_context.h"
#include "vn_result.h"

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)

static std::size_t samples(double seconds) { return static_cast<std::size_t>(seconds * vn::kSampleRate); }

int main() {
    // The same table as the Kotlin test this replaced (AudioContextTest).
    CHECK(vn::audio_context_for_samples(0) == vn::kMinWindow);
    CHECK(vn::audio_context_for_samples(samples(0.5)) == vn::kMinWindow);
    CHECK(vn::audio_context_for_samples(samples(5.0)) == 5 * 50 + 100);
    CHECK(vn::audio_context_for_samples(samples(20.0)) == 20 * 50 + 100);
    CHECK(vn::audio_context_for_samples(samples(25.0)) == 25 * 50 + 100);  // exactly 25 s is still a short note
    CHECK(vn::audio_context_for_samples(samples(25.1)) == 0);
    CHECK(vn::audio_context_for_samples(samples(600.0)) == 0);
    int previous = 0;
    for (int tenths = 0; tenths <= 250; tenths++) {
        const int window = vn::audio_context_for_samples(samples(tenths / 10.0));
        CHECK(window >= 1 && window <= vn::kFullWindow);
        CHECK(window >= previous);  // never shrinks as the note gets longer
        previous = window;
    }

    // JSON escaping: quotes, backslashes, control characters; UTF-8 passes through.
    CHECK(vn::json_escape("a\"b\\c\nd\te") == "a\\\"b\\\\c\\nd\\te");
    CHECK(vn::json_escape(std::string("x\x01y")) == "x\\u0001y");
    CHECK(vn::json_escape("café \xE2\x80\x99") == "café \xE2\x80\x99");

    vn::Result speech;
    speech.text = "Hello \"world\"";
    speech.language = "en";
    speech.detected = true;
    speech.speech_seconds = 7.88f;
    speech.audio_ctx = 651;
    speech.language_scores = {{"en", 0.9f}, {"nl", 0.05f}};
    const std::string json = vn::to_json(speech);
    CHECK(json.find("\"text\":\"Hello \\\"world\\\"\"") != std::string::npos);
    CHECK(json.find("\"detected\":true") != std::string::npos);
    CHECK(json.find("\"noSpeech\":false") != std::string::npos);
    CHECK(json.find("\"speechSeconds\":7.8800") != std::string::npos);
    CHECK(json.find("\"audioCtx\":651") != std::string::npos);
    CHECK(json.find("\"languageScores\":{\"en\":0.9000,\"nl\":0.0500}") != std::string::npos);

    vn::Result silent;
    silent.no_speech = true;
    silent.language = "nl";
    CHECK(vn::to_json(silent).find("\"noSpeech\":true") != std::string::npos);
    CHECK(vn::to_json(silent).find("\"languageScores\":{}") != std::string::npos);

    std::printf(failures == 0 ? "core tests passed\n" : "core tests FAILED\n");
    return failures == 0 ? 0 : 1;
}

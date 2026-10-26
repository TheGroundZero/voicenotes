// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

namespace vn {

// What the pipeline produced for one note.
struct Result {
    std::string text;
    std::string language;
    bool detected = false;      // the language was detected, not given
    bool no_speech = false;     // the speech check found nothing, so nothing was transcribed
    bool failed = false;        // whisper itself reported an error
    float speech_seconds = -1;  // from the speech check; -1 if it did not run
    int audio_ctx = 0;
    double vad_seconds = 0, detect_seconds = 0, transcribe_seconds = 0;
    std::vector<std::pair<std::string, float>> language_scores;  // only when the detection ran
};

inline std::string json_escape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 2);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) { char buf[8]; std::snprintf(buf, sizeof buf, "\\u%04x", c); out += buf; }
                else out += static_cast<char>(c);  // UTF-8 bytes pass through unchanged
        }
    }
    return out;
}

// One JSON object, UTF-8. The app reads this through JNI and the benchmark tool prints it, so both see the same fields.
inline std::string to_json(const Result &r) {
    char num[64];
    auto number = [&](double v) { std::snprintf(num, sizeof num, "%.4f", v); return std::string(num); };
    std::string j = "{\"text\":\"" + json_escape(r.text) + "\",\"language\":\"" + json_escape(r.language) + "\"";
    j += std::string(",\"detected\":") + (r.detected ? "true" : "false");
    j += std::string(",\"noSpeech\":") + (r.no_speech ? "true" : "false");
    j += std::string(",\"failed\":") + (r.failed ? "true" : "false");
    j += ",\"speechSeconds\":" + number(r.speech_seconds);
    j += ",\"audioCtx\":" + std::to_string(r.audio_ctx);
    j += ",\"vadSeconds\":" + number(r.vad_seconds) + ",\"detectSeconds\":" + number(r.detect_seconds) +
         ",\"transcribeSeconds\":" + number(r.transcribe_seconds);
    j += ",\"languageScores\":{";
    for (std::size_t i = 0; i < r.language_scores.size(); i++) {
        if (i) j += ",";
        j += "\"" + json_escape(r.language_scores[i].first) + "\":" + number(r.language_scores[i].second);
    }
    return j + "}}";
}

}  // namespace vn

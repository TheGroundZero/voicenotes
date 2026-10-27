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

// Whisper can cut a multi-byte character in two at a token boundary, so its text is not always valid UTF-8. Every invalid byte
// becomes U+FFFD, which is what the app's own decoding showed before, and a JSON consumer never meets broken bytes.
inline std::string sanitize_utf8(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    const std::size_t n = s.size();
    for (std::size_t i = 0; i < n;) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if (c < 0x80) { out += static_cast<char>(c); i++; continue; }
        const std::size_t len = (c >= 0xC2 && c <= 0xDF) ? 2 : (c >= 0xE0 && c <= 0xEF) ? 3 : (c >= 0xF0 && c <= 0xF4) ? 4 : 0;
        bool ok = len != 0 && i + len <= n;
        for (std::size_t k = 1; ok && k < len; k++) ok = (static_cast<unsigned char>(s[i + k]) & 0xC0) == 0x80;
        if (ok && len >= 3) {
            const unsigned char c1 = static_cast<unsigned char>(s[i + 1]);
            if ((c == 0xE0 && c1 < 0xA0) || (c == 0xED && c1 >= 0xA0) || (c == 0xF0 && c1 < 0x90) || (c == 0xF4 && c1 >= 0x90)) ok = false;
        }
        if (ok) { out.append(s, i, len); i += len; }
        else { out += "\xEF\xBF\xBD"; i++; }
    }
    return out;
}

inline std::string json_escape(const std::string &raw) {
    const std::string s = sanitize_utf8(raw);
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

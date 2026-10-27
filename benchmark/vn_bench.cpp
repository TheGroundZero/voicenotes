// SPDX-License-Identifier: GPL-3.0-or-later
// Command-line benchmark tool: runs 16 kHz mono WAV files through the same pipeline as the app (whisper_core.cpp) and
// prints one JSON object per line. Build with scripts/benchmark-build.sh; see docs/BENCHMARK.md.
//
//   vn-bench --model ggml-small.bin [--vad ggml-silero-v5.1.2.bin] [--language auto|en|nl|fr] [--preferred en,nl,fr]
//            [--threads 4] [--audio-ctx auto|full|N] clip.wav...
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "whisper_core.h"

namespace {

// Reads a PCM16 mono 16 kHz WAV (any chunk layout) into floats, as the app does. Returns an error message or "".
std::string read_wav(const std::string &path, std::vector<float> *out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return "cannot open the file";
    std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    auto u16 = [&](std::size_t at) { return static_cast<unsigned>(static_cast<unsigned char>(bytes[at]) | (static_cast<unsigned char>(bytes[at + 1]) << 8)); };
    auto u32 = [&](std::size_t at) { return u16(at) | (u16(at + 2) << 16); };
    if (bytes.size() < 12 || bytes.compare(0, 4, "RIFF") != 0 || bytes.compare(8, 4, "WAVE") != 0) return "not a WAV file";
    bool have_format = false;
    for (std::size_t at = 12; at + 8 <= bytes.size();) {
        const std::string id = bytes.substr(at, 4);
        const std::size_t size = u32(at + 4), body = at + 8;
        if (id == "fmt " && body + 16 <= bytes.size()) {
            have_format = true;
            if (u16(body) != 1 || u16(body + 2) != 1 || u32(body + 4) != 16000 || u16(body + 14) != 16) return "expected PCM16 mono at 16 kHz";
        } else if (id == "data") {
            if (!have_format) return "data before format";
            const std::size_t n = std::min(size, bytes.size() - body) / 2;
            out->resize(n);
            for (std::size_t i = 0; i < n; i++) (*out)[i] = static_cast<short>(u16(body + 2 * i)) / 32768.0f;
            return "";
        }
        at = body + size + (size & 1);
    }
    return "no data chunk";
}

long peak_memory_kb() {
    std::ifstream status("/proc/self/status");
    for (std::string line; std::getline(status, line);)
        if (line.rfind("VmHWM:", 0) == 0) return std::strtol(line.c_str() + 6, nullptr, 10);
    return -1;
}

std::vector<std::string> split(const std::string &s, char sep) {
    std::vector<std::string> parts;
    std::stringstream stream(s);
    for (std::string part; std::getline(stream, part, sep);) if (!part.empty()) parts.push_back(part);
    return parts;
}

}  // namespace

int main(int argc, char **argv) {
    std::string model, vad;
    bool fresh_context = true;                                // the app makes a new engine context for every note
    vn::Options options;
    options.preferred = {"en", "nl", "fr"};
    std::vector<std::string> clips;
    for (int i = 1; i < argc; i++) {
        const std::string arg = argv[i];
        auto value = [&]() -> std::string { if (i + 1 >= argc) { std::fprintf(stderr, "missing value for %s\n", arg.c_str()); std::exit(2); } return argv[++i]; };
        if (arg == "--model") model = value();
        else if (arg == "--vad") vad = value();
        else if (arg == "--language") options.language = value();
        else if (arg == "--preferred") options.preferred = split(value(), ',');
        else if (arg == "--threads") options.threads = std::atoi(value().c_str());
        else if (arg == "--reuse-context") fresh_context = false;   // faster, but state can leak from one clip into the next
        else if (arg == "--detect-audio-ctx") {        // the window of the language detection alone: same (as the app), auto, full or N
            const std::string v = value();
            options.detect_audio_ctx = v == "same" ? -2 : v == "auto" ? -1 : v == "full" ? 0 : std::atoi(v.c_str());
        }
        else if (arg == "--audio-ctx") {               // auto (as the app), full (the whole 30 s window) or a number of frames
            const std::string v = value();
            options.audio_ctx = v == "auto" ? -1 : v == "full" ? 0 : std::atoi(v.c_str());
        }
        else if (arg.rfind("--", 0) == 0) { std::fprintf(stderr, "unknown option %s\n", arg.c_str()); return 2; }
        else clips.push_back(arg);
    }
    if (model.empty() || clips.empty()) {
        std::fprintf(stderr, "usage: vn-bench --model ggml-X.bin [--vad silero.bin] [--language auto|en|nl|fr] [--preferred en,nl,fr] [--threads N] [--audio-ctx auto|full|N] [--detect-audio-ctx same|auto|full|N] [--reuse-context] clip.wav...\n");
        return 2;
    }
    options.vad_model_path = vad;

    auto load = [&]() {
        whisper_context_params cparams = whisper_context_default_params();
        cparams.use_gpu = false;
        return whisper_init_from_file_with_params(model.c_str(), cparams);
    };
    const auto load_started = std::chrono::steady_clock::now();
    whisper_context *ctx = load();
    if (ctx == nullptr) { std::fprintf(stderr, "could not load %s\n", model.c_str()); return 1; }
    const double load_seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - load_started).count();
    std::printf("{\"type\":\"model\",\"path\":\"%s\",\"loadSeconds\":%.3f,\"threads\":%d,\"freshContext\":%s}\n",
                vn::json_escape(model).c_str(), load_seconds, options.threads, fresh_context ? "true" : "false");

    int errors = 0;
    for (const std::string &clip : clips) {
        std::vector<float> samples;
        const std::string error = read_wav(clip, &samples);
        if (!error.empty()) {
            std::printf("{\"type\":\"clip\",\"file\":\"%s\",\"error\":\"%s\"}\n", vn::json_escape(clip).c_str(), vn::json_escape(error).c_str());
            errors++;
            continue;
        }
        if (fresh_context && clip != clips.front()) {              // a new context for every clip, as the app has for every note
            whisper_free(ctx);
            ctx = load();
            if (ctx == nullptr) { std::fprintf(stderr, "could not load %s\n", model.c_str()); return 1; }
        }
        const vn::Result result = vn::run(ctx, samples, options);
        std::printf("{\"type\":\"clip\",\"file\":\"%s\",\"audioSeconds\":%.3f,\"result\":%s}\n", vn::json_escape(clip).c_str(),
                    samples.size() / 16000.0, vn::to_json(result).c_str());
        std::fflush(stdout);
    }
    std::printf("{\"type\":\"summary\",\"clips\":%zu,\"errors\":%d,\"peakMemoryKb\":%ld}\n", clips.size(), errors, peak_memory_kb());
    whisper_free(ctx);
    return errors == 0 ? 0 : 1;
}

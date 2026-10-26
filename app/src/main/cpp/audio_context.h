// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>

namespace vn {

constexpr int kSampleRate = 16000;

// Whisper always encodes a full 30 s window (1500 frames, 50 per second), however short the note is. For short notes a
// window just longer than the audio gives the same text and language at a fraction of the cost (measured on the
// Pixel 10: 8.7 s to 1.9 s of language detection, about 3.5 times less processor time for transcription).
constexpr int kFramesPerSecond = 50;
constexpr int kFullWindow = 1500;
constexpr int kMinWindow = 200;       // 4 s
constexpr int kMarginFrames = 100;    // 2 s of slack after the audio
constexpr double kMaxShortSeconds = 25.0;

// The encoder window to use for sample_count samples at 16 kHz; 0 means the full window (long notes).
inline int audio_context_for_samples(std::size_t sample_count) {
    const double seconds = static_cast<double>(sample_count) / kSampleRate;
    if (seconds > kMaxShortSeconds) return 0;
    return std::min(kFullWindow, std::max(kMinWindow, static_cast<int>(std::ceil(seconds * kFramesPerSecond)) + kMarginFrames));
}

}  // namespace vn

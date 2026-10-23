// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import kotlin.math.ceil

/**
 * Whisper always encodes a full 30 s window (1500 frames, 50 per second), however short the note is. For short notes a
 * window just longer than the audio gives the same text and language at a fraction of the cost (measured on the
 * Pixel 10: 8.7 s to 1.9 s of language detection, about 3.5 times less processor time for transcription).
 */
object AudioContext {
    const val SAMPLE_RATE = 16_000
    private const val FRAMES_PER_SECOND = 50
    const val FULL = 1500
    const val MIN = 200 // 4 s
    private const val MARGIN_FRAMES = 100 // 2 s of slack after the audio
    private const val MAX_SHORT_SECONDS = 25.0

    /** The encoder window to use for [sampleCount] samples at 16 kHz; 0 means the full window (long notes). */
    fun forSamples(sampleCount: Int): Int {
        val seconds = sampleCount / SAMPLE_RATE.toDouble()
        if (seconds > MAX_SHORT_SECONDS) return 0
        return minOf(FULL, maxOf(MIN, ceil(seconds * FRAMES_PER_SECOND).toInt() + MARGIN_FRAMES))
    }
}

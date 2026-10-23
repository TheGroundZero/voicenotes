// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.io.File

/** What an engine produced: the text and the language it was transcribed in. */
data class TranscriptionResult(
    val text: String,
    val language: String,
    val detected: Boolean,
    /** The speech check found no speech, so nothing was transcribed. */
    val noSpeech: Boolean = false,
)

/** A speech-to-text engine. whisper.cpp is the first implementation; others can be added later. */
interface Transcriber : AutoCloseable {
    /**
     * Transcribes a 16 kHz mono PCM16 WAV. [language] is an ISO 639-1 code, or [AUTO] to detect it,
     * restricted to [preferred] (the first entry is the fallback when detection is not confident).
     */
    fun transcribe(wav: File, language: String, preferred: List<String>): TranscriptionResult

    companion object {
        const val AUTO = "auto"
    }
}

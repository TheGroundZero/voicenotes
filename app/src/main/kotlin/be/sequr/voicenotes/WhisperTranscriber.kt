// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [speechSeconds], if given, runs first; a result of zero seconds means the note has no speech and is not transcribed.
 */
class WhisperTranscriber(modelFile: File, private val speechSeconds: ((FloatArray) -> Float?)? = null) : Transcriber {
    private val handle = WhisperBridge.nativeInit(modelFile.path)

    init {
        check(handle != 0L) { "Could not load model ${modelFile.name}" }
    }

    override fun transcribe(wav: File, language: String, preferred: List<String>): TranscriptionResult {
        val samples = readSamples(wav)
        // The app's own start beep is recorded at the start of every note. The speech check ignores it (it is
        // evaluated on audio with the first 300 ms silenced), but Whisper gets the audio as recorded: silencing
        // the start for Whisper made it repeat a one-word note 11 times, while the beep does no harm.
        val forSpeechCheck = samples.copyOf().also { it.fill(0f, 0, minOf(it.size, START_BEEP_SAMPLES)) }
        if (speechSeconds?.invoke(forSpeechCheck) == 0f) {
            return TranscriptionResult("", language.takeIf { it != Transcriber.AUTO } ?: preferred.first(), detected = false, noSpeech = true)
        }
        val audioCtx = AudioContext.forSamples(samples.size)
        val detected = language == Transcriber.AUTO
        val t0 = System.nanoTime()
        val lang = if (detected) detect(samples, preferred, audioCtx) else language
        val t1 = System.nanoTime()
        val text = String(WhisperBridge.nativeTranscribe(handle, samples, lang, THREADS, audioCtx), Charsets.UTF_8).trim()
        // Timings only, never content.
        Log.i(TAG, "audioCtx=$audioCtx detect=%.1fs transcribe=%.1fs".format((t1 - t0) / 1e9, (System.nanoTime() - t1) / 1e9))
        return TranscriptionResult(text, lang, detected)
    }

    /**
     * Whisper scores every language it knows; we only look at the preferred ones, so a short or
     * noisy clip cannot come back as an unrelated language. If even the best preferred score is
     * weak, fall back to the first preferred language.
     */
    private fun detect(samples: FloatArray, preferred: List<String>, audioCtx: Int): String {
        require(preferred.isNotEmpty()) { "No preferred languages" }
        if (preferred.size == 1) return preferred.first()
        val scores = WhisperBridge.nativeDetectLanguage(handle, samples, preferred.toTypedArray(), THREADS, audioCtx)
        val best = scores.indices.maxBy { scores[it] }
        return if (scores[best] >= MIN_CONFIDENCE) preferred[best] else preferred.first()
    }

    private fun readSamples(wav: File): FloatArray {
        val bytes = wav.readBytes()
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER_SIZE, bytes.size - WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get().toFloat() / 32768f }
    }

    override fun close() = WhisperBridge.nativeFree(handle)

    companion object {
        private const val TAG = "Transcription"
        private const val WAV_HEADER_SIZE = 44
        private const val START_BEEP_SAMPLES = AudioContext.SAMPLE_RATE * 3 / 10 // first 300 ms
        private const val THREADS = 4 // 8 was not faster in the M0 benchmark

        /** Probability (out of all ~99 languages) the best preferred language must reach to be trusted. */
        private const val MIN_CONFIDENCE = 0.15f

        /** Installed model ids, the default first. */
        fun installedModels(context: Context): List<String> = ModelStore.ids(context)

        /** [name] if given and installed, otherwise the default or best installed model. */
        fun findModel(context: Context, name: String? = null): Pair<String, File>? =
            ModelStore.resolve(context, name)?.let { it.id to ModelStore.file(context, it.id) }
    }
}

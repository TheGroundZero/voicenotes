// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * The Whisper engine. The transcription steps (speech check, window sizing, language detection among the preferred
 * languages, beam search) run in native code that is shared with the benchmark tool (whisper_core.cpp), so the benchmark
 * measures what the app does. [vadModel], if given, is the speech-check model: a note without speech is not transcribed.
 */
class WhisperTranscriber(modelFile: File, private val vadModel: File? = null) : Transcriber {
    private val handle = WhisperBridge.nativeInit(modelFile.path)

    init {
        check(handle != 0L) { "Could not load model ${modelFile.name}" }
    }

    override fun transcribe(wav: File, language: String, preferred: List<String>): TranscriptionResult {
        require(preferred.isNotEmpty()) { "No preferred languages" }
        val samples = readSamples(wav)
        val json = JSONObject(String(WhisperBridge.nativeTranscribeNote(handle, samples, language, preferred.toTypedArray(), vadModel?.path, THREADS), Charsets.UTF_8))
        // Timings only, never content.
        Log.i(TAG, "speech=%.2fs audioCtx=%d detect=%.1fs transcribe=%.1fs".format(
            json.getDouble("speechSeconds"), json.getInt("audioCtx"), json.getDouble("detectSeconds"), json.getDouble("transcribeSeconds")))
        return TranscriptionResult(
            text = json.getString("text"),
            language = json.getString("language"),
            detected = json.getBoolean("detected"),
            noSpeech = json.getBoolean("noSpeech"),
        )
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
        private const val THREADS = 4 // 8 was not faster in the M0 benchmark

        /** Installed model ids, the default first. */
        fun installedModels(context: Context): List<String> = ModelStore.ids(context)

        /** [name] if given and installed, otherwise the default or best installed model. */
        fun findModel(context: Context, name: String? = null): Pair<String, File>? =
            ModelStore.resolve(context, name)?.let { it.id to ModelStore.file(context, it.id) }
    }
}

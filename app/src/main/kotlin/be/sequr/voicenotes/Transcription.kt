// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.util.Log
import java.io.File

/** A finished transcription plus the model that produced it. */
data class TranscriptionOutcome(val result: TranscriptionResult, val model: String)

object Transcription {
    private const val TAG = "Transcription"

    /**
     * Blocking. [language] is a code or [Transcriber.AUTO]; [modelName] null means the best installed one.
     * [checkForSpeech] first skips notes with no speech at all (see [SpeechGate]).
     * Returns null if there is no model or it failed; the audio is never touched.
     */
    fun transcribe(context: Context, wav: File, language: String, modelName: String?, checkForSpeech: Boolean): TranscriptionOutcome? {
        val (model, file) = WhisperTranscriber.findModel(context, modelName) ?: run {
            Log.w(TAG, "No model imported, skipping transcription")
            return null
        }
        return try {
            WhisperBridge.ensureBackends(context) // picks the processor variant that suits this phone
            val start = System.nanoTime()
            WhisperTranscriber(file, vadModel = if (checkForSpeech) SpeechGate.modelFile(context) else null).use { TranscriptionOutcome(it.transcribe(wav, language, Settings.preferredLanguages(context)), model) }
                .also {
                    val audioSeconds = (wav.length() - 44) / 32_000.0 // 16 kHz mono PCM16
                    Log.i(TAG, "model=$model audio=%.1fs took=%.1fs".format(audioSeconds, (System.nanoTime() - start) / 1e9))
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Transcription failed for ${wav.name}", e) // never log transcript content
            null
        }
    }
}

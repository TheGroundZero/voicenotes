// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Decides whether a note contains any speech before Whisper is asked about it. Whisper does not say
 * "nothing here" on silence or background noise: it invents text ("You", "Thank you.", rows of dots)
 * and can spend 30+ seconds doing it. Silero VAD (a ~1 MB model bundled in the app) is fast and cheap.
 *
 * The check can only SKIP a note, never delete it, and it fails open: if the detector cannot run, the
 * note is transcribed as usual. The check itself runs in native code (whisper_core.cpp, threshold 0.35, more sensitive
 * than the library default of 0.5 so quiet speech is less likely to be missed); this object only provides the model file.
 */
object SpeechGate {
    private const val TAG = "SpeechGate"
    private const val ASSET = "ggml-silero-v5.1.2.bin"
    private const val SIZE = 885_098L
    private const val SHA256 = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"

    /** The native code needs a real file path, so the bundled model is copied out once and verified. */
    fun modelFile(context: Context): File? {
        val file = File(File(context.filesDir, "vad").apply { mkdirs() }, ASSET)
        if (file.exists() && file.length() == SIZE) return file
        return try {
            context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
            if (sha256(file) == SHA256) file else { file.delete(); Log.e(TAG, "Bundled VAD model failed its checksum"); null }
        } catch (e: Exception) {
            Log.e(TAG, "Could not prepare the VAD model", e)
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

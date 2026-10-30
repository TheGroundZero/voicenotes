// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Decides whether a note contains any speech before Whisper is asked about it. Whisper does not say
 * "nothing here" on silence or background noise: it invents text ("You", "Thank you.", rows of dots)
 * and can spend 30+ seconds doing it. Silero VAD (an 885 KB model) is fast and cheap.
 *
 * The check can only SKIP a note, never delete it, and it fails open: if the model is not on the phone or cannot run, the
 * note is transcribed as usual. The check itself runs in native code (whisper_core.cpp, threshold 0.35, more sensitive
 * than the library default of 0.5 so quiet speech is less likely to be missed); this object only provides the model file.
 * The model is not part of the app: it is downloaded with the first speech model or imported, like the others
 * ([ModelCatalog.speechCheck]), and phones that ran version 0.5.1 or older keep the copy those versions extracted.
 */
object SpeechGate {
    private const val TAG = "SpeechGate"

    /** The model file if it is on the phone and matches the pinned size and SHA-256, otherwise null (and the check is skipped). */
    fun modelFile(context: Context): File? = verified(ModelStore.speechCheckFile(context))

    /** The last file that passed the checksum, by path, size and modification time: hashing it again on every call is not needed. */
    @Volatile private var lastGood: Triple<String, Long, Long>? = null

    /** [file] if it has the pinned size and SHA-256, otherwise null. */
    fun verified(file: File): File? {
        val spec = ModelCatalog.speechCheck
        if (!file.exists() || file.length() != spec.sizeBytes) return null
        val key = Triple(file.path, file.length(), file.lastModified())
        if (key == lastGood) return file
        if (sha256(file) != spec.sha256) { Log.e(TAG, "The speech check model does not match its checksum; it is ignored"); return null }
        lastGood = key
        return file
    }

    /** A quick look, without the checksum, for the first picture of the Models screen; [isInstalled] has the final word. */
    fun looksInstalled(file: File): Boolean = file.exists() && file.length() == ModelCatalog.speechCheck.sizeBytes

    fun looksInstalled(context: Context): Boolean = looksInstalled(ModelStore.speechCheckFile(context.filesDir))

    fun isInstalled(context: Context) = modelFile(context) != null

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

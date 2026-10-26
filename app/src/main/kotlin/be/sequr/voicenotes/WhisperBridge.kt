// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

/** Thin JNI wrapper around whisper.cpp. Everything else talks to [Transcriber]. */
object WhisperBridge {
    private var backendsLoaded = false

    init {
        System.loadLibrary("whisper_jni")
    }

    /** Loads the processor variant that suits this phone; call before the first model is opened. Repeating it is free. */
    @Synchronized
    fun ensureBackends(context: android.content.Context) {
        if (backendsLoaded) return
        nativeLoadBackends(context.applicationInfo.nativeLibraryDir)
        backendsLoaded = true
    }

    private external fun nativeLoadBackends(dir: String)

    /** Returns a context handle, or 0 if the model could not be loaded. */
    external fun nativeInit(modelPath: String): Long
    /**
     * The whole transcription of one note in native code (shared with the benchmark tool): speech check (skipped when
     * [vadModelPath] is null), window sizing, language detection among [preferred], beam search. [language] is a code or
     * "auto". Returns a JSON object as UTF-8 bytes, see vn_result.h.
     */
    external fun nativeTranscribeNote(handle: Long, samples: FloatArray, language: String, preferred: Array<String>, vadModelPath: String?, threads: Int): ByteArray
    external fun nativeFree(handle: Long)
}

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
    external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String, threads: Int, audioCtx: Int): ByteArray
    external fun nativeDetectLanguage(handle: Long, samples: FloatArray, codes: Array<String>, threads: Int, audioCtx: Int): FloatArray
    external fun nativeSpeechSeconds(modelPath: String, samples: FloatArray, threshold: Float, threads: Int): Float
    external fun nativeFree(handle: Long)
}

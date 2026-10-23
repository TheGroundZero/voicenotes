// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

/**
 * Records 16 kHz mono PCM16 to a WAV file, the format whisper.cpp expects. [device] is a Bluetooth microphone to
 * record from, or null for the phone's own.
 */
class WavRecorder(private val file: File, private val device: AudioDeviceInfo? = null) {
    @Volatile private var recording = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // VOICE_COMMUNICATION is what Android routes to a Bluetooth hands-free microphone.
        val source = if (device != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val record = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 4)
        device?.let { record.preferredDevice = it }
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
        recording = true
        thread = Thread {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(HEADER_SIZE)) // placeholder, patched in stop
                val buf = ByteArray(minBuf)
                record.startRecording()
                var bytes = 0L
                while (recording) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        out.write(buf, 0, n)
                        bytes += n
                    }
                }
                record.stop()
                record.release()
                out.seek(0)
                out.write(wavHeader(bytes))
            }
        }.also { it.start() }
    }

    /** Stops recording and blocks until the WAV header is finalised. */
    fun stop() {
        recording = false
        thread?.join()
        thread = null
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val byteRate = SAMPLE_RATE * 2
        return java.nio.ByteBuffer.allocate(HEADER_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(1) // PCM, mono
            putInt(SAMPLE_RATE); putInt(byteRate); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val HEADER_SIZE = 44
    }
}

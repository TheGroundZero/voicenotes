// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The only class in the app that talks to the network (the build fails if any other file does;
 * see `verifyNetworkIsolation` in app/build.gradle.kts). It downloads one catalog model (or the small speech check) on request.
 *
 * Rules: HTTPS only, hosts limited to Hugging Face, no cookies or identifiers, resumable, and the
 * file is moved into place only after its size and SHA-256 match the catalog.
 */
class ModelDownloader(private val context: Context) {
    sealed interface Result {
        data object Done : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    fun download(spec: ModelSpec, onProgress: (done: Long, total: Long) -> Unit, isCancelled: () -> Boolean): Result {
        val target = ModelStore.targetFor(context, spec)
        val part = File(target.parentFile, "${spec.fileName}.part")
        try {
            var have = part.takeIf { it.exists() }?.length() ?: 0L
            if (have > spec.sizeBytes) { part.delete(); have = 0 }
            if (spec.sizeBytes - have > target.parentFile.usableSpace) {
                return Result.Failed("Not enough free storage (needs ${(spec.sizeBytes - have) / 1_000_000} MB)")
            }
            if (have < spec.sizeBytes) {
                val connection = open(spec.url, have)
                connection.use {
                    // 200 means the server ignored the Range header: start over.
                    if (have > 0 && connection.responseCode != 206) { part.delete(); have = 0 }
                    connection.inputStream.use { input ->
                        java.io.FileOutputStream(part, have > 0).use { out ->
                            val buffer = ByteArray(1 shl 16)
                            var done = have
                            onProgress(done, spec.sizeBytes)
                            while (true) {
                                if (isCancelled()) return Result.Cancelled
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                onProgress(done, spec.sizeBytes)
                            }
                        }
                    }
                }
            }
            if (isCancelled()) return Result.Cancelled
            if (part.length() != spec.sizeBytes) return Result.Failed("Download incomplete; tap Download to resume")
            if (sha256(part) != spec.sha256) {
                part.delete()
                return Result.Failed("The downloaded file did not match its checksum and was discarded")
            }
            target.delete()
            return if (part.renameTo(target)) Result.Done else Result.Failed("Could not store the model")
        } catch (e: IOException) {
            return Result.Failed(e.message ?: "Network error; tap Download to resume") // the partial file is kept
        }
    }

    private inline fun <T> HttpURLConnection.use(block: () -> T): T = try { block() } finally { disconnect() }

    /** Follows redirects by hand so every hop can be checked against the rules above. */
    private fun open(start: String, from: Long): HttpURLConnection {
        var url = URL(start)
        repeat(MAX_REDIRECTS + 1) {
            if (url.protocol != "https" || !allowedHost(url.host)) throw IOException("Blocked connection to ${url.host}")
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "VoiceNotes")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            when (val code = c.responseCode) {
                200, 206 -> return c
                301, 302, 303, 307, 308 -> {
                    val location = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    c.disconnect()
                    url = URL(url, location)
                }
                else -> { c.disconnect(); throw IOException("Server answered HTTP $code") }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun allowedHost(host: String) =
        host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co")

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

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The passphrase does not open this backup (or its first block was damaged: the two cannot be told apart). */
class WrongPassphraseException : IOException("Wrong passphrase, or the file is damaged")

/** The backup is truncated, reordered or changed after it was written. */
class CorruptBackupException(message: String) : IOException(message)

/**
 * Optional passphrase protection for a backup file, as a stream wrapper around the ZIP archive.
 *
 * Layout: `"VNBK"`, format version (1), KDF id (1 = PBKDF2-HMAC-SHA256), iterations (4 bytes), salt (16), nonce prefix (8);
 * then blocks of `[4-byte length][AES-256-GCM ciphertext + 16-byte tag]`, each holding up to [BLOCK_SIZE] bytes of plaintext.
 * The nonce of a block is the prefix plus its 4-byte number, and the authenticated data is the header plus one byte that
 * marks the last block, so blocks cannot be reordered, dropped from the end, or taken from another file undetected.
 * The last block always exists (it may be empty), which is how the end of the stream is authenticated.
 *
 * An unencrypted backup is a plain ZIP file and starts with `PK`, so a reader can tell the two apart with [isEncrypted].
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 1
    private const val KDF_PBKDF2_SHA256 = 1
    private const val SALT_SIZE = 16
    private const val NONCE_PREFIX_SIZE = 8
    private const val TAG_BITS = 128
    private const val HEADER_SIZE = 4 + 1 + 1 + 4 + SALT_SIZE + NONCE_PREFIX_SIZE
    const val BLOCK_SIZE = 64 * 1024
    const val DEFAULT_ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 10_000_000

    /** True for a backup written with [encrypt]; a plain archive (or anything else) is false. */
    fun isEncrypted(firstBytes: ByteArray): Boolean = firstBytes.size >= 4 && firstBytes.copyOfRange(0, 4).contentEquals(MAGIC)

    /** Wraps [out] so that everything written to the result is encrypted. Closing the result finishes the file (and closes [out]). */
    fun encrypt(out: OutputStream, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): OutputStream {
        require(passphrase.isNotEmpty())
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS)
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(VERSION.toByte()).put(KDF_PBKDF2_SHA256.toByte()).putInt(iterations).put(salt).put(prefix).array()
        out.write(header)
        return BlockWriter(out, deriveKey(passphrase, salt, iterations), header, prefix)
    }

    /**
     * Reads the header from [input] and returns a stream with the plaintext. [WrongPassphraseException] comes from the first
     * read if the passphrase is wrong; [CorruptBackupException] from any read that finds the data changed or cut short.
     */
    fun decrypt(input: InputStream, passphrase: CharArray): InputStream {
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header, "The file is too short to be a backup")
        if (!isEncrypted(header)) throw CorruptBackupException("This is not an encrypted Voice Notes backup")
        val buffer = ByteBuffer.wrap(header, 4, HEADER_SIZE - 4)
        val version = buffer.get().toInt() and 0xff
        val kdf = buffer.get().toInt() and 0xff
        if (version != VERSION || kdf != KDF_PBKDF2_SHA256) throw CorruptBackupException("This backup was made by a newer version of Voice Notes")
        val iterations = buffer.getInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw CorruptBackupException("The backup header is damaged")
        val salt = ByteArray(SALT_SIZE).also(buffer::get)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(buffer::get)
        return BlockReader(input, deriveKey(passphrase, salt, iterations), header, prefix)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readFully(input: InputStream, into: ByteArray, tooShort: String) {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) throw CorruptBackupException(tooShort)
            read += n
        }
    }

    private fun cipher(mode: Int, key: SecretKey, prefix: ByteArray, counter: Int, header: ByteArray, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }
    }

    private class BlockWriter(
        private val out: OutputStream,
        private val key: SecretKey,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(BLOCK_SIZE)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var offset = off
            var left = len
            while (left > 0) {
                // A full buffer is only written when more data follows, so the last block is always the one flushed by close().
                if (filled == BLOCK_SIZE) flushBlock(last = false)
                val n = minOf(left, BLOCK_SIZE - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n; offset += n; left -= n
            }
        }

        private fun flushBlock(last: Boolean) {
            val encrypted = cipher(Cipher.ENCRYPT_MODE, key, prefix, counter++, header, last).doFinal(buffer, 0, filled)
            out.write(ByteBuffer.allocate(4).putInt(encrypted.size).array())
            out.write(encrypted)
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushBlock(last = true)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    private class BlockReader(
        private val input: InputStream,
        private val key: SecretKey,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var position = 0
        private var counter = 0
        private var pendingLength = -1 // the length of the next block, once it has been read ahead
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == plain.size) {
                if (finished) return -1
                nextBlock()
            }
            val n = minOf(len, plain.size - position)
            System.arraycopy(plain, position, b, off, n)
            position += n
            return n
        }

        private fun readLength(): Int {
            val lengthBytes = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(lengthBytes, read, 4 - read)
                if (n < 0) return if (read == 0) -1 else throw CorruptBackupException("The backup is cut short")
                read += n
            }
            return ByteBuffer.wrap(lengthBytes).getInt()
        }

        private fun nextBlock() {
            val length = if (pendingLength >= 0) pendingLength else readLength()
            pendingLength = -1
            if (length < 0 || length > BLOCK_SIZE + 16 + 16) {
                throw if (counter == 0 && length < 0) CorruptBackupException("The file is too short to be a backup") else CorruptBackupException("The backup is damaged")
            }
            val encrypted = ByteArray(length)
            try {
                readFully(input, encrypted, "The backup is cut short")
            } catch (e: EOFException) {
                throw CorruptBackupException("The backup is cut short")
            }
            val next = readLength() // a block is the last one exactly when nothing follows it
            val last = next < 0
            pendingLength = next
            val decrypted = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, counter, header, last).doFinal(encrypted)
            } catch (e: GeneralSecurityException) {
                throw if (counter == 0) WrongPassphraseException() else CorruptBackupException("The backup is damaged or cut short")
            }
            counter++
            plain = decrypted
            position = 0
            finished = last
        }

        override fun close() = input.close()
    }
}

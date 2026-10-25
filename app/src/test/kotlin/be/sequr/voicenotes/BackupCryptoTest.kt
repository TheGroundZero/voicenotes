// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

class BackupCryptoTest {
    private val pass = "correct horse battery".toCharArray()
    private val fast = 1_000 // key derivation rounds: the real default is slow on purpose

    private fun encrypt(data: ByteArray, passphrase: CharArray = pass): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, passphrase, fast).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decrypt(file: ByteArray, passphrase: CharArray = pass): ByteArray =
        BackupCrypto.decrypt(ByteArrayInputStream(file), passphrase).use { it.readBytes() }

    private fun sample(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    @Test fun roundTripAtBlockBoundaries() {
        for (size in listOf(0, 1, 100, BackupCrypto.BLOCK_SIZE - 1, BackupCrypto.BLOCK_SIZE, BackupCrypto.BLOCK_SIZE + 1, 3 * BackupCrypto.BLOCK_SIZE, 200_000)) {
            val data = sample(size)
            assertArrayEquals("size $size", data, decrypt(encrypt(data)))
        }
    }

    @Test fun smallWritesGiveTheSameResult() {
        val data = sample(150_000)
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, pass, fast).use { s -> data.toList().chunked(777).forEach { s.write(it.toByteArray()) } }
        assertArrayEquals(data, decrypt(out.toByteArray()))
    }

    @Test fun twoEncryptionsOfTheSameDataDiffer() {
        val data = sample(1000)
        assertFalse(encrypt(data).contentEquals(encrypt(data)))
    }

    @Test fun plaintextDoesNotAppearInTheFile() {
        val secret = "my very private recording".toByteArray()
        val file = encrypt(secret + sample(500))
        assertTrue(BackupCrypto.isEncrypted(file))
        assertFalse(String(file, Charsets.ISO_8859_1).contains("private recording"))
    }

    @Test fun aPlainZipIsNotEncrypted() {
        assertFalse(BackupCrypto.isEncrypted(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(2)))
    }

    @Test fun wrongPassphraseIsReportedAsSuch() {
        val file = encrypt(sample(5000))
        try {
            decrypt(file, "wrong passphrase".toCharArray())
            fail("expected WrongPassphraseException")
        } catch (e: WrongPassphraseException) {
            // expected
        }
    }

    @Test fun aChangedByteIsDetectedAnywhere() {
        val file = encrypt(sample(3 * BackupCrypto.BLOCK_SIZE + 10))
        for (position in listOf(0, 5, 8, 30, 40, 100, file.size / 2, file.size - 1)) {
            val damaged = file.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            try {
                decrypt(damaged)
                fail("a change at byte $position went unnoticed")
            } catch (e: IOException) {
                // expected: wrong passphrase, corrupt, or a bad header
            }
        }
    }

    @Test fun aFileCutShortIsDetected() {
        val file = encrypt(sample(3 * BackupCrypto.BLOCK_SIZE + 10))
        // cut inside a block, and cut exactly after a whole block (the final block is then missing)
        val firstBlockEnd = 4 + 1 + 1 + 4 + 16 + 8 + 4 + BackupCrypto.BLOCK_SIZE + 16
        for (length in listOf(file.size - 1, file.size - 30, firstBlockEnd, firstBlockEnd + 100, 20, 0)) {
            try {
                decrypt(file.copyOf(length))
                fail("a file cut to $length bytes was accepted")
            } catch (e: IOException) {
                // expected
            }
        }
    }

    @Test fun reorderedBlocksAreDetected() {
        val file = encrypt(sample(3 * BackupCrypto.BLOCK_SIZE))
        val headerSize = 4 + 1 + 1 + 4 + 16 + 8
        val blockSize = 4 + BackupCrypto.BLOCK_SIZE + 16
        val first = file.copyOfRange(headerSize, headerSize + blockSize)
        val second = file.copyOfRange(headerSize + blockSize, headerSize + 2 * blockSize)
        val swapped = file.copyOf()
        System.arraycopy(second, 0, swapped, headerSize, blockSize)
        System.arraycopy(first, 0, swapped, headerSize + blockSize, blockSize)
        try {
            decrypt(swapped)
            fail("swapped blocks were accepted")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test fun absurdIterationCountsAreRefused() {
        val file = encrypt(sample(10))
        val tampered = file.copyOf()
        // iterations live in bytes 6..9 of the header
        tampered[6] = 0x7f; tampered[7] = 0x7f
        try {
            decrypt(tampered)
            fail("expected an error")
        } catch (e: IOException) {
            // expected, and quickly: the count is checked before any key is derived
        }
    }

    @Test fun aNewerFormatVersionIsRefusedClearly() {
        val file = encrypt(sample(10)).also { it[4] = 9 }
        try {
            decrypt(file)
            fail("expected an error")
        } catch (e: CorruptBackupException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }
}

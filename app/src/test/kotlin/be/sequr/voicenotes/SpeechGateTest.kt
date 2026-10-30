// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechGateTest {
    @get:Rule val tmp = TemporaryFolder()

    // Versions up to 0.5.1 extracted the bundled model to exactly this path. Phones that update keep that copy only if the
    // download code looks in the same place, so the path is spelled out here and must not change.
    @Test fun theSpeechCheckIsWhereEarlierVersionsPutTheirCopy() {
        assertEquals(File("/data/user/0/be.sequr.voicenotes/files/vad/ggml-silero-v5.1.2.bin"), ModelStore.speechCheckFile(File("/data/user/0/be.sequr.voicenotes/files")))
    }

    @Test fun aMissingFileIsNotTheSpeechCheck() {
        assertNull(SpeechGate.verified(File(tmp.root, "none.bin")))
    }

    @Test fun aFileOfTheWrongSizeIsNotTheSpeechCheck() {
        val f = tmp.newFile("short.bin").apply { writeBytes(ByteArray(10)) }
        assertNull(SpeechGate.verified(f))
    }

    @Test fun aFileOfTheRightSizeWithOtherContentIsNotTheSpeechCheck() {
        val f = tmp.newFile("other.bin").apply { writeBytes(ByteArray(ModelCatalog.speechCheck.sizeBytes.toInt())) }
        assertNull(SpeechGate.verified(f))
    }

    @Test fun aQuickLookOnlyChecksTheSize() {
        val right = tmp.newFile("right.bin").apply { writeBytes(ByteArray(ModelCatalog.speechCheck.sizeBytes.toInt())) }
        val wrong = tmp.newFile("wrong.bin").apply { writeBytes(ByteArray(10)) }
        assertTrue(SpeechGate.looksInstalled(right))      // zeros of the right size look right; verified() is the one that hashes
        assertFalse(SpeechGate.looksInstalled(wrong))
        assertFalse(SpeechGate.looksInstalled(File(tmp.root, "none.bin")))
    }

    // Needs the real model, which is not in the repository (fetch it with scripts/fetch-vad-model.sh); skipped without it.
    // A remembered result must not outlive a change of the file: same size, other content.
    @Test fun aChangedFileIsCheckedAgain() {
        val real = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "models/ggml-silero-v5.1.2.bin") }.firstOrNull { it.exists() }
        assumeTrue(real != null)
        val copy = tmp.newFile("copy.bin").apply { writeBytes(real!!.readBytes()) }
        assertNotNull(SpeechGate.verified(copy))
        assertNotNull(SpeechGate.verified(copy))          // the second call is answered from memory
        copy.writeBytes(ByteArray(ModelCatalog.speechCheck.sizeBytes.toInt()))
        copy.setLastModified(copy.lastModified() + 5_000)
        assertNull(SpeechGate.verified(copy))
    }

    @Test fun theUpstreamFileIsAccepted() {
        val real = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "models/ggml-silero-v5.1.2.bin") }.firstOrNull { it.exists() }
        assumeTrue(real != null)
        assertNotNull(SpeechGate.verified(real!!))
    }
}

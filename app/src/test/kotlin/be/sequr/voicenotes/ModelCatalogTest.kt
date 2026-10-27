// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalog is shipped in the app and is what the downloader trusts, so its entries are checked strictly. */
class ModelCatalogTest {
    @Test fun idsAreUniqueAndSafeInFileNames() {
        val ids = ModelCatalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue("unsafe id $it", Regex("[A-Za-z0-9._-]+").matches(it)) }
    }

    @Test fun checksumsAreLowerCaseSha256() {
        ModelCatalog.models.forEach { assertTrue("bad hash for ${it.id}", Regex("[0-9a-f]{64}").matches(it.sha256)) }
    }

    @Test fun sizesLookLikeModels() {
        ModelCatalog.models.forEach { assertTrue("${it.id}: ${it.sizeBytes}", it.sizeBytes in 100_000_000..4_000_000_000) }
    }

    @Test fun downloadsAreHttpsOnHuggingFaceAndPinnedToARevision() {
        ModelCatalog.models.forEach {
            val uri = URI(it.url)
            assertEquals("https", uri.scheme)
            assertEquals("huggingface.co", uri.host)
            assertTrue("not pinned to a commit: ${it.url}", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(it.url))
            assertTrue(it.url.endsWith("/" + it.fileName))
        }
    }

    @Test fun theSpeechCheckIsPinnedLikeTheModelsButKeptOutOfTheModelList() {
        val check = ModelCatalog.speechCheck
        assertTrue(ModelCatalog.models.none { it.id == check.id })
        assertTrue(Regex("[0-9a-f]{64}").matches(check.sha256))
        assertEquals("ggml-silero-v5.1.2.bin", check.fileName)                 // the name earlier versions extracted their bundled copy under
        assertEquals(885_098L, check.sizeBytes)
        val uri = URI(check.url)
        assertEquals("https", uri.scheme)
        assertEquals("huggingface.co", uri.host)
        assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(check.url))
        assertTrue(check.url.endsWith("/" + check.fileName))
        assertEquals(check, ModelCatalog.downloadable(check.id))
        assertNull(ModelCatalog.byId(check.id))                                // never offered as a transcription model
    }

    @Test fun lookupById() {
        val first = ModelCatalog.models.first()
        assertEquals(first, ModelCatalog.byId(first.id))
        assertNull(ModelCatalog.byId("no-such-model"))
        assertNotNull(ModelCatalog.byId("small"))
    }
}

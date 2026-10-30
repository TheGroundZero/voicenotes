// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, text: String) = File(tmp.root, name).apply { writeText(text) }

    @Test fun theNewFileTakesThePlaceOfTheOldOne() {
        val old = file("model.bin", "old")
        val fresh = file("import.tmp", "new")
        assertTrue(ModelStore.replaceFile(fresh, old))
        assertEquals("new", old.readText())
        assertFalse(fresh.exists())
    }

    @Test fun aMissingTargetIsFine() {
        val fresh = file("import.tmp", "new")
        val target = File(tmp.root, "model.bin")
        assertTrue(ModelStore.replaceFile(fresh, target))
        assertEquals("new", target.readText())
    }

    // The old code deleted the target first: a failed move then lost a working file.
    @Test fun aFailedMoveLeavesTheOldFileInPlace() {
        val old = file("model.bin", "old")
        val missing = File(tmp.root, "never-written.tmp")
        assertFalse(ModelStore.replaceFile(missing, old))
        assertEquals("old", old.readText())
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExportTest {
    @Test fun entryIsAMarkdownSectionWithDateLanguageAndTrimmedText() {
        val entry = NoteExport.entry(createdAt = 0L, language = "nl", text = "  Dit is een test.  \n")
        val lines = entry.split("\n")
        assertTrue("heading expected, got: ${lines[0]}", Regex("## \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} · NL").matches(lines[0]))
        assertEquals("", lines[1])
        assertEquals("Dit is een test.", lines[2])
        assertTrue("sections must be separated by a blank line", entry.endsWith("\n\n"))
    }

    @Test fun twoEntriesCanBeJoinedWithoutRunningTogether() {
        val joined = NoteExport.entry(1_000L, "en", "one") + NoteExport.entry(2_000L, "en", "two")
        assertEquals(2, Regex("(?m)^## ").findAll(joined).count())
    }
}

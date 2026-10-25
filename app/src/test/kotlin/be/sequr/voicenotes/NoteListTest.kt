// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteListTest {
    private val brussels = ZoneId.of("Europe/Brussels")
    private val now = LocalDateTime.of(2026, 10, 25, 12, 0).atZone(brussels).toInstant()

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10) =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(brussels).toInstant().toEpochMilli()

    private fun rows(times: List<Long>, oldestFirst: Boolean, zone: ZoneId = brussels) =
        NoteList.rows(times, { it }, oldestFirst, now, zone, Locale.UK)

    /** Headers as their title, notes as their time, so the whole list reads as one line in the assertions. */
    private fun shape(rows: List<NoteRow<Long>>) = rows.map { if (it is NoteRow.Day) it.title else (it as NoteRow.Item).value }

    private val sample = listOf(at(25, 9), at(25, 7), at(24, 23, 30), at(23, 10))

    @Test fun `newest first has a header above each day`() {
        assertEquals(
            listOf("Today", at(25, 9), at(25, 7), "Yesterday", at(24, 23, 30), "23 Oct 2026", at(23, 10)),
            shape(rows(sample, oldestFirst = false)),
        )
    }

    @Test fun `oldest first reverses the notes and the days`() {
        assertEquals(
            listOf("23 Oct 2026", at(23, 10), "Yesterday", at(24, 23, 30), "Today", at(25, 7), at(25, 9)),
            shape(rows(sample, oldestFirst = true)),
        )
    }

    @Test fun `the order does not depend on the order the notes come in`() {
        assertEquals(shape(rows(sample, false)), shape(rows(sample.shuffled(java.util.Random(7)), false)))
        assertEquals(shape(rows(sample, true)), shape(rows(sample.shuffled(java.util.Random(7)), true)))
    }

    @Test fun `a day starts at local midnight`() {
        val lateAndEarly = listOf(at(25, 0, 1), at(24, 23, 59))
        assertEquals(listOf("Today", at(25, 0, 1), "Yesterday", at(24, 23, 59)), shape(rows(lateAndEarly, false)))
    }

    @Test fun `the same instant can be another day in another time zone`() {
        val t = Instant.parse("2026-10-24T23:30:00Z").toEpochMilli() // 25 Oct 01:30 in Brussels, 24 Oct 19:30 in New York
        assertEquals(listOf("Today", t), shape(rows(listOf(t), false, brussels)))
        assertEquals(listOf("Yesterday", t), shape(NoteList.rows(listOf(t), { it }, false, now, ZoneId.of("America/New_York"), Locale.UK)))
    }

    @Test fun `no notes, no rows`() {
        assertEquals(emptyList<Any>(), rows(emptyList(), false))
    }

    @Test fun `headers carry the day so a list can key on them`() {
        val days = rows(sample, false).filterIsInstance<NoteRow.Day>().map { it.epochDay }
        assertEquals(3, days.toSet().size)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One line of the note list: a header for a day, or a note. */
sealed interface NoteRow<out T> {
    data class Day(val epochDay: Long, val title: String) : NoteRow<Nothing>
    data class Item<T>(val value: T) : NoteRow<T>
}

/** Puts the notes in the order the user chose and puts a header above each day. Kept free of Android types so it can be unit-tested. */
object NoteList {
    fun dayTitle(day: LocalDate, today: LocalDate, locale: Locale): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
    }

    fun <T> rows(
        items: List<T>,
        time: (T) -> Long,
        oldestFirst: Boolean,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<NoteRow<T>> {
        val sorted = if (oldestFirst) items.sortedBy(time) else items.sortedByDescending(time)
        val today = now.atZone(zone).toLocalDate()
        val rows = ArrayList<NoteRow<T>>(sorted.size + 8)
        var lastDay: LocalDate? = null
        for (item in sorted) {
            val day = Instant.ofEpochMilli(time(item)).atZone(zone).toLocalDate()
            if (day != lastDay) {
                rows += NoteRow.Day(day.toEpochDay(), dayTitle(day, today, locale))
                lastDay = day
            }
            rows += NoteRow.Item(item)
        }
        return rows
    }
}

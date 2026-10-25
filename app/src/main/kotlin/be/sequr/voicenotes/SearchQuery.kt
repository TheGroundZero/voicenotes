// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.text.Normalizer
import java.util.Locale

/** Labels are short, single-line names. Two names that differ only in case or accents are the same label. */
object LabelName {
    const val MAX_LENGTH = 40

    /** The name to store, or null if [raw] cannot be a label name (empty, too long, or containing a quote or control character). */
    fun clean(raw: String): String? {
        val name = raw.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty() || name.length > MAX_LENGTH) return null
        if (name.any { it == '"' || it.isISOControl() }) return null
        return name
    }

    /** Lower case and without accents, for comparing names and searching text. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}

/**
 * What the search bar means. Plain words (or a "quoted phrase") must all appear in the note's text; a `label:` term must name one
 * of the note's labels exactly, and is never looked for in the text. Everything is case and accent insensitive, and every term has to
 * match. Examples: `milk`, `label:shopping`, `label:"to do" milk`.
 *
 * [labelFragment] is the label name being typed at the end of the query (after `label:`), for suggestions and for narrowing the list as
 * you type; null when the query does not end inside a label term. A `label:` with nothing after it filters nothing yet.
 */
class SearchQuery(val words: List<String>, val labels: List<String>, val labelFragment: String?) {
    val isEmpty: Boolean get() = words.isEmpty() && labels.isEmpty() && labelFragment.isNullOrEmpty()

    /**
     * [foldedText] is the note's current text and [foldedLabels] its label names, both through [LabelName.fold]. A finished `label:` term
     * must equal one of the labels. The term still being typed at the end matches any label that starts with what has been typed so far,
     * so the list narrows while you type (and `label:shop` shows nothing for a note that only says "shop").
     */
    fun matches(foldedText: String, foldedLabels: Set<String>): Boolean {
        if (!words.all { foldedText.contains(it) }) return false
        if (!labels.all { it in foldedLabels }) return false
        return labelFragment.isNullOrEmpty() || foldedLabels.any { it.startsWith(labelFragment) }
    }

    companion object {
        private const val PREFIX = "label:"
        private val SPACES = Regex("\\s+")

        fun parse(text: String): SearchQuery {
            val words = ArrayList<String>()
            val labels = ArrayList<String>()
            var fragment: String? = null
            var i = 0
            val n = text.length
            while (i < n) {
                if (text[i].isWhitespace()) { i++; continue }
                val isLabel = text.regionMatches(i, PREFIX, 0, PREFIX.length, ignoreCase = true)
                if (isLabel) i += PREFIX.length
                val value: String
                val finished: Boolean // false while the term is still being typed: it reaches the end of the text without being closed
                if (i < n && text[i] == '"') {
                    val end = text.indexOf('"', i + 1)
                    finished = end >= 0
                    value = text.substring(i + 1, if (finished) end else n)
                    i = if (finished) end + 1 else n
                } else {
                    var j = i
                    while (j < n && !text[j].isWhitespace()) j++
                    value = text.substring(i, j)
                    i = j
                    finished = i < n // a bare word is finished by the space after it
                }
                val folded = LabelName.fold(value).trim().replace(SPACES, " ")
                when {
                    isLabel && !finished -> fragment = folded
                    isLabel -> if (folded.isNotEmpty()) labels.add(folded)
                    folded.isNotEmpty() -> words.add(folded)
                }
            }
            return SearchQuery(words.distinct(), labels.distinct(), fragment)
        }

        /** The text of a `label:` term for [name], quoted when it contains spaces. */
        fun term(name: String): String = if (name.any { it.isWhitespace() }) "$PREFIX\"$name\"" else "$PREFIX$name"

        /** [query] with the term for [name] added at the end, unless the query already has it. A term being typed is replaced. */
        fun withLabel(query: String, name: String): String {
            val folded = LabelName.fold(name)
            val base = removeTrailingLabelFragment(query)
            if (parse(base).labels.contains(folded)) return base
            return (if (base.isBlank()) "" else base.trimEnd() + " ") + term(name)
        }

        /** Drops a `label:xyz` that is still being typed at the end, so a chosen suggestion can replace it. */
        fun removeTrailingLabelFragment(query: String): String {
            if (parse(query).labelFragment == null) return query
            val idx = query.lastIndexOf(PREFIX, ignoreCase = true)
            return if (idx >= 0) query.substring(0, idx) else query
        }
    }
}

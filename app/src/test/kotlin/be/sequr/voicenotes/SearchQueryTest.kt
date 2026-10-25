// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    private fun q(text: String) = SearchQuery.parse(text)
    private fun fold(s: String) = LabelName.fold(s)

    /** A note with this text and these labels, against the labels that exist. */
    private fun hit(query: String, text: String, labels: List<String> = emptyList()): Boolean =
        q(query).matches(fold(text), labels.map(::fold).toSet())

    @Test fun plainWordsMustAllAppear() {
        assertTrue(hit("milk oat", "Buy oat milk and paper"))
        assertFalse(hit("milk cheese", "Buy oat milk and paper"))
        assertTrue(hit("", "anything"))
    }

    @Test fun searchIgnoresCaseAndAccents() {
        assertTrue(hit("RESERVER", "Penser à réserver une table"))
        assertTrue(hit("réserver", "penser a reserver"))
        assertTrue(hit("Ça", "ca va"))
    }

    @Test fun aQuotedPhraseIsOneTerm() {
        assertTrue(hit("\"oat milk\"", "buy oat milk"))
        assertFalse(hit("\"milk oat\"", "buy oat milk"))
    }

    @Test fun aLabelTermOnlyMatchesLabelsNeverTheText() {
        // the central rule: a note that merely SAYS "shopping" is not found by label:shopping
        assertFalse(hit("label:shopping", "I am going shopping tomorrow"))
        assertTrue(hit("label:shopping", "buy milk", labels = listOf("Shopping")))
        // and a word is never looked for in the labels
        assertFalse(hit("shopping", "buy milk", labels = listOf("Shopping")))
    }

    @Test fun labelAndWordsCombine() {
        assertTrue(hit("label:shopping milk", "buy milk", labels = listOf("shopping")))
        assertFalse(hit("label:shopping cheese", "buy milk", labels = listOf("shopping")))
        assertFalse(hit("label:shopping milk", "buy milk", labels = listOf("work")))
    }

    @Test fun severalLabelTermsAreAnd() {
        assertTrue(hit("label:a label:b", "x", labels = listOf("a", "b")))
        assertFalse(hit("label:a label:b", "x", labels = listOf("a")))
    }

    @Test fun labelNamesWithSpacesNeedQuotes() {
        assertTrue(hit("label:\"to do\"", "x", labels = listOf("To Do")))
        assertEquals(listOf("to do"), q("label:\"to do\" ").labels)
        assertEquals(listOf("to"), q("label:to do ").labels)
        assertEquals(listOf("do"), q("label:to do ").words)
    }

    @Test fun labelMatchIsExactNotAPrefix() {
        assertFalse(hit("label:shop ", "x", labels = listOf("shopping"))) // a finished term must match the whole name
        assertTrue(hit("label:Shopping ", "x", labels = listOf("shopping")))
        assertTrue(hit("LABEL:shopping ", "x", labels = listOf("shopping")))
    }

    @Test fun aLabelBeingTypedNarrowsByPrefixButNeverLooksInTheText() {
        val query = q("label:shop")
        assertEquals("shop", query.labelFragment)
        assertTrue(query.labels.isEmpty())
        assertTrue(hit("label:shop", "x", labels = listOf("shopping")))
        assertFalse(hit("label:shop", "I like to shop a lot")) // the word in the text does not count
        assertFalse(hit("label:shopping", "x", labels = listOf("work")))
        assertTrue(hit("label:shopping", "x", labels = listOf("shopping")))
    }

    @Test fun fragmentOnlyWhileTheTermIsOpen() {
        assertEquals("", q("label:").labelFragment)
        assertEquals("sh", q("milk label:sh").labelFragment)
        assertNull(q("label:shopping ").labelFragment)
        assertNull(q("label:\"to do\"").labelFragment)
        assertEquals("to d", q("label:\"to d").labelFragment)
        assertNull(q("milk").labelFragment)
    }

    @Test fun anEmptyLabelTermFiltersNothing() {
        assertTrue(hit("label:", "anything"))
        assertTrue(q("label:").isEmpty)
        assertTrue(q("milk label:").words == listOf("milk"))
    }

    @Test fun wordsLookingLikeThePrefixAreJustWords() {
        assertEquals(listOf("mylabel:x"), q("mylabel:x").words)
        assertEquals(listOf("label"), q("label").words)
    }

    @Test fun termAndWithLabel() {
        assertEquals("label:work", SearchQuery.term("work"))
        assertEquals("label:\"to do\"", SearchQuery.term("to do"))
        assertEquals("label:work", SearchQuery.withLabel("", "work"))
        assertEquals("milk label:work", SearchQuery.withLabel("milk", "work"))
        assertEquals("milk label:work ", SearchQuery.withLabel("milk label:work ", "Work").let { if (it.endsWith(" ")) it else "$it " })
        assertEquals("milk label:work", SearchQuery.withLabel("milk label:wo", "work")) // the half-typed term is replaced
    }

    @Test fun labelNamesAreCleaned() {
        assertEquals("To do", LabelName.clean("  To   do "))
        assertNull(LabelName.clean(""))
        assertNull(LabelName.clean("   "))
        assertNull(LabelName.clean("say \"hi\""))
        assertEquals("two lines", LabelName.clean("two\nlines")) // line breaks become spaces
        assertNull(LabelName.clean("bell\u0007"))
        assertNull(LabelName.clean("x".repeat(LabelName.MAX_LENGTH + 1)))
        assertEquals("x".repeat(LabelName.MAX_LENGTH), LabelName.clean("x".repeat(LabelName.MAX_LENGTH)))
        assertEquals("café", LabelName.clean("café"))
    }

    @Test fun foldingMakesAccentedNamesEqual() {
        assertEquals(LabelName.fold("Café"), LabelName.fold("cafe"))
        assertEquals(LabelName.fold("ÉCOLE"), LabelName.fold("ecole"))
    }
}

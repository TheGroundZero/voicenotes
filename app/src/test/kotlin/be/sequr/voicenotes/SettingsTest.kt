// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun languageNamesAreReadable() {
        assertEquals("Dutch", Settings.languageName("nl"))
        assertEquals("French", Settings.languageName("fr"))
        assertEquals("Auto", Settings.languageName(Transcriber.AUTO))
    }

    @Test fun everySupportedLanguageHasAName() {
        Settings.supportedLanguages.forEach { assertTrue(it, Settings.languageName(it).length > 2) }
    }

    @Test fun supportedLanguagesAreTwoLetterCodes() {
        Settings.supportedLanguages.forEach { assertTrue(it, Regex("[a-z]{2}").matches(it)) }
    }
}

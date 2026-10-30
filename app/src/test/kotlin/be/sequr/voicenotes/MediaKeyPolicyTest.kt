// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKeyPolicyTest {
    private val toggleKeys = listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
    )

    private fun ignore(key: Int, fromCar: Boolean, recording: Boolean, sinceStartMs: Long = 60_000) =
        VoiceNotesMediaService.ignoreKey(key, fromCar, recording, sinceStartMs)

    // --- a note must never start by itself

    @Test fun noKeyFromAndroidAutoEverStartsANote() {
        // seen in a car: a pause key arrived while Android Auto disconnected and nothing was recording
        toggleKeys.forEach { assertTrue("key $it", ignore(it, fromCar = true, recording = false)) }
    }

    @Test fun aPauseKeyNeverStartsANoteFromAnySource() {
        assertTrue(ignore(KeyEvent.KEYCODE_MEDIA_PAUSE, fromCar = false, recording = false))
    }

    @Test fun aHeadsetStillStartsANote() {
        assertFalse(ignore(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, fromCar = false, recording = false))
        assertFalse(ignore(KeyEvent.KEYCODE_HEADSETHOOK, fromCar = false, recording = false))
        assertFalse(ignore(KeyEvent.KEYCODE_MEDIA_PLAY, fromCar = false, recording = false))
    }

    // --- a note that runs

    @Test fun aKeyFromTheCarRightAfterTheStartIsIgnored() {
        toggleKeys.forEach {
            assertTrue(ignore(it, fromCar = true, recording = true, sinceStartMs = 130))   // seen in a car: 0.13 s after the start
            assertTrue(ignore(it, fromCar = true, recording = true, sinceStartMs = 2_999))
        }
    }

    @Test fun aLaterKeyFromTheCarStopsTheNote() {
        toggleKeys.forEach {
            assertFalse(ignore(it, fromCar = true, recording = true, sinceStartMs = 3_000))
            assertFalse(ignore(it, fromCar = true, recording = true, sinceStartMs = 60_000))
        }
    }

    @Test fun aHeadsetKeyStopsTheNoteAtOnce() {
        toggleKeys.forEach { assertFalse("key $it", ignore(it, fromCar = false, recording = true, sinceStartMs = 100)) }
    }

    @Test fun aClockThatRanBackwardsNeverBlocksAKey() {
        assertFalse(ignore(KeyEvent.KEYCODE_MEDIA_PAUSE, fromCar = true, recording = true, sinceStartMs = -1))
    }
}

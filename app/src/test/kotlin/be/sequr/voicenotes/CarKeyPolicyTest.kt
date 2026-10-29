// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKeyPolicyTest {
    private fun ignore(fromCar: Boolean, recording: Boolean, sinceStartMs: Long) =
        VoiceNotesMediaService.ignoreCarKey(fromCar, recording, sinceStartMs)

    @Test fun aKeyFromTheCarRightAfterTheStartIsIgnored() {
        assertTrue(ignore(fromCar = true, recording = true, sinceStartMs = 130))   // seen in a car: 0.13 s after the start
        assertTrue(ignore(fromCar = true, recording = true, sinceStartMs = 2_999))
    }

    @Test fun aLaterKeyFromTheCarStopsTheNote() {
        assertFalse(ignore(fromCar = true, recording = true, sinceStartMs = 3_000))
        assertFalse(ignore(fromCar = true, recording = true, sinceStartMs = 60_000))
    }

    @Test fun keysFromAHeadsetOrOtherAppsAreNeverDelayed() {
        assertFalse(ignore(fromCar = false, recording = true, sinceStartMs = 100))
    }

    @Test fun nothingIsIgnoredWhenNoNoteIsRunning() {
        assertFalse(ignore(fromCar = true, recording = false, sinceStartMs = 100))
    }

    @Test fun aClockThatRanBackwardsNeverBlocksAKey() {
        assertFalse(ignore(fromCar = true, recording = true, sinceStartMs = -1))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPolicyTest {
    private fun state(level: Int = 80, charging: Boolean = false, powerSave: Boolean = false, low: Boolean = false) =
        BatteryPolicy.State(level, charging, powerSave, low)

    @Test fun healthyBatteryAllowsAutomaticTranscription() {
        assertTrue(state().allowsAutomatic)
        assertNull(state().holdReason)
    }

    @Test fun lowBatteryWaitsAndSaysUntilWhen() {
        val low = state(level = 12, low = true)
        assertFalse(low.allowsAutomatic)
        assertTrue(low.holdReason!!.contains("12"))
        assertTrue(low.holdReason!!.contains("${BatteryPolicy.RESUME_AT}"))
    }

    @Test fun batterySaverWaitsEvenWithAFullBattery() {
        val saver = state(level = 100, powerSave = true)
        assertFalse(saver.allowsAutomatic)
        assertEquals("battery saver is on", saver.holdReason)
    }

    @Test fun thresholdsAreOrdered() {
        assertTrue(BatteryPolicy.PAUSE_BELOW < BatteryPolicy.RESUME_AT)
        assertEquals(15, BatteryPolicy.PAUSE_BELOW)
        assertEquals(25, BatteryPolicy.RESUME_AT)
    }
}

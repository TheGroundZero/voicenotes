// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * When may a note be transcribed without the user asking? Transcription keeps the processor busy for tens of
 * seconds, so it waits while the battery is low or battery saver is on. A manual request always runs.
 *
 * Low battery has a hysteresis so it does not flap around one value: pause below [PAUSE_BELOW] percent, carry on
 * once the battery is back at [RESUME_AT] percent, or whenever the phone is charging.
 */
object BatteryPolicy {
    const val PAUSE_BELOW = 15
    const val RESUME_AT = 25

    data class State(val levelPercent: Int, val charging: Boolean, val powerSave: Boolean, val lowBattery: Boolean) {
        val allowsAutomatic: Boolean get() = !powerSave && !lowBattery

        /** Why automatic transcription is waiting, or null when it is not. */
        val holdReason: String?
            get() = when {
                powerSave -> "battery saver is on"
                lowBattery -> "battery is low ($levelPercent %); resumes at $RESUME_AT % or when charging"
                else -> null
            }
    }

    fun state(context: Context): State {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val level = (battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, scale) ?: scale) * 100 / scale
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val low = when {
            charging -> false
            level < PAUSE_BELOW -> true
            level >= RESUME_AT -> false
            else -> Settings.batteryLow(context) // between the two thresholds: keep the previous decision
        }
        Settings.setBatteryLow(context, low)
        val powerSave = context.getSystemService(PowerManager::class.java).isPowerSaveMode
        return State(level, charging, powerSave, low)
    }
}

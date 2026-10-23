// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Audio cues: the only feedback while driving. Played on the alarm stream so they stay
 * audible in vibrate/silent mode, with a short vibration as backup.
 */
object Tones {
    fun start(context: Context) = play(context, ToneGenerator.TONE_PROP_BEEP, VibrationEffect.EFFECT_CLICK)
    fun stop(context: Context) = play(context, ToneGenerator.TONE_PROP_ACK, VibrationEffect.EFFECT_DOUBLE_CLICK)
    fun error(context: Context) = play(context, ToneGenerator.TONE_PROP_NACK, VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun play(context: Context, tone: Int, effect: Int) {
        // Short-lived generator; released on a timer so the tone can finish.
        val gen = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        gen.startTone(tone, 200)
        Handler(Looper.getMainLooper()).postDelayed({ gen.release() }, 400)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?.vibrate(VibrationEffect.createPredefined(effect))
    }
}

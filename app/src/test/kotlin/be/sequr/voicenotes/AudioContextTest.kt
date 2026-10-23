// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioContextTest {
    private fun samples(seconds: Double) = (seconds * AudioContext.SAMPLE_RATE).toInt()

    @Test fun veryShortNotesGetTheMinimumWindow() {
        assertEquals(AudioContext.MIN, AudioContext.forSamples(0))
        assertEquals(AudioContext.MIN, AudioContext.forSamples(samples(0.5)))
    }

    @Test fun windowIsTheAudioPlusTwoSeconds() {
        assertEquals(5 * 50 + 100, AudioContext.forSamples(samples(5.0)))
        assertEquals(20 * 50 + 100, AudioContext.forSamples(samples(20.0)))
    }

    @Test fun longNotesUseTheFullWindow() {
        assertEquals(0, AudioContext.forSamples(samples(25.1)))
        assertEquals(0, AudioContext.forSamples(samples(600.0)))
    }

    @Test fun windowNeverExceedsTheFullWindowAndGrowsWithTheAudio() {
        var previous = 0
        for (tenths in 0..250) {
            val window = AudioContext.forSamples(samples(tenths / 10.0))
            assertTrue(window in 1..AudioContext.FULL)
            assertTrue("window must not shrink as the note gets longer", window >= previous)
            previous = window
        }
    }
}

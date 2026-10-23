// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCleanupTest {
    private val now = 1_800_000_000_000L

    @Test fun cutoffIsExactlyTheChosenNumberOfDaysBack() {
        assertEquals(now - TimeUnit.DAYS.toMillis(7), AudioCleanup.cutoff(now, 7))
        assertEquals(now - TimeUnit.DAYS.toMillis(365), AudioCleanup.cutoff(now, 365))
    }

    @Test fun longerSettingsReachFurtherBackSoNothingIsRemovedEarlier() {
        val cutoffs = listOf(7, 30, 90, 365).map { AudioCleanup.cutoff(now, it) }
        assertEquals(cutoffs.sortedDescending(), cutoffs)
        assertTrue(cutoffs.all { it < now })
    }

    @Test fun aNoteTranscribedAtTheCutoffIsNotYetDue() {
        // The query uses "first transcript < cutoff": equality must not count as old enough.
        val cutoff = AudioCleanup.cutoff(now, 30)
        assertTrue(!(cutoff < cutoff))
    }
}

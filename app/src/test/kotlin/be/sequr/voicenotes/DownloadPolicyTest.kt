// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    @Test fun theFirstProgressStepIsShownAndFastOnesAreThinnedOut() {
        assertTrue(DownloadPolicy.reportProgress(0, 1_000, nowMs = 5_000, lastReportMs = 0))
        assertFalse(DownloadPolicy.reportProgress(100, 1_000, nowMs = 5_300, lastReportMs = 5_000))
        assertFalse(DownloadPolicy.reportProgress(100, 1_000, nowMs = 5_500, lastReportMs = 5_000))
        assertTrue(DownloadPolicy.reportProgress(100, 1_000, nowMs = 5_501, lastReportMs = 5_000))
    }

    // Seen on the phone's small speech check and possible for any model: the last step arrived within 500 ms of the one before it.
    @Test fun theLastStepIsNeverDropped() {
        assertTrue(DownloadPolicy.reportProgress(1_000, 1_000, nowMs = 5_010, lastReportMs = 5_000))
        assertTrue(DownloadPolicy.reportProgress(1_001, 1_000, nowMs = 5_010, lastReportMs = 5_000))
    }

    @Test fun aRefusedStartForAnotherDownloadIsTakenBack() {
        val active = setOf("large-v3-turbo-q8_0", "silero-v5.1.2")
        assertEquals("small", DownloadPolicy.staleStart("small", active))
    }

    @Test fun aSecondRequestForARunningDownloadKeepsItsState() {
        val active = setOf("large-v3-turbo-q8_0", "silero-v5.1.2")
        assertNull(DownloadPolicy.staleStart("large-v3-turbo-q8_0", active))
        assertNull(DownloadPolicy.staleStart("silero-v5.1.2", active))
    }

    @Test fun anUnknownRequestIsTakenBackAndNothingIsClearedForNoRequest() {
        assertEquals("nonsense", DownloadPolicy.staleStart("nonsense", emptySet()))
        assertNull(DownloadPolicy.staleStart(null, setOf("small")))
    }
}

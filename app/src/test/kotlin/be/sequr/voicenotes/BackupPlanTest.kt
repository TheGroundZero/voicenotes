// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlanTest {
    private fun note(file: String, createdAt: Long, status: NoteStatus = NoteStatus.DONE, withAudio: Boolean = true) = BackupNote(
        createdAt, file, "en", status, "phone", !withAudio, if (withAudio) BackupAudio(10, "0".repeat(64)) else null, emptyList(),
    )

    @Test fun notesAlreadyHereAreSkippedWhenMerging() {
        val plan = BackupPlan.plan(
            existing = setOf(NoteKey(1000, "a.wav")), existingFiles = setOf("a.wav"),
            backup = listOf(note("a.wav", 1000), note("b.wav", 2000)), stagedAudio = setOf("a.wav", "b.wav"), replace = false,
        )
        assertEquals(1, plan.alreadyPresent)
        assertEquals(listOf("b.wav"), plan.toAdd.map { it.audioFile })
    }

    @Test fun theSameNoteIsAddedAgainWhenReplacing() {
        val plan = BackupPlan.plan(setOf(NoteKey(1000, "a.wav")), setOf("a.wav"), listOf(note("a.wav", 1000)), setOf("a.wav"), replace = true)
        assertEquals(0, plan.alreadyPresent)
        assertEquals(1, plan.toAdd.size)
        // the old file still exists until the replacement has succeeded, so the new copy gets another name
        assertEquals("a-restored2.wav", plan.toAdd[0].audioFile)
    }

    @Test fun aFileNameTakenByAnotherNoteGetsANewName() {
        val plan = BackupPlan.plan(setOf(NoteKey(5, "x.wav")), setOf("x.wav"), listOf(note("x.wav", 1000)), setOf("x.wav"), replace = false)
        assertEquals("x-restored2.wav", plan.toAdd.single().audioFile)
    }

    @Test fun twoBackupNotesNeverGetTheSameName() {
        val plan = BackupPlan.plan(emptySet(), setOf("x.wav", "x-restored2.wav"), listOf(note("x.wav", 1), note("y.wav", 2)), setOf("x.wav", "y.wav"), false)
        val names = plan.toAdd.map { it.audioFile }
        assertEquals(names.toSet().size, names.size)
        assertTrue("x-restored3.wav" in names)
    }

    @Test fun aNoteWithoutRecordingNeverSharesAnotherNotesFileName() {
        // the existing note a.wav still has its recording; a restored note without one must not be named a.wav, or deleting it would delete that recording
        val plan = BackupPlan.plan(setOf(NoteKey(5, "a.wav")), setOf("a.wav"), listOf(note("a.wav", 1000, withAudio = false)), emptySet(), replace = false)
        assertEquals("a-restored2.wav", plan.toAdd.single().audioFile)
        assertFalse(plan.toAdd.single().hasAudio)
    }

    @Test fun uniqueNameCountsUp() {
        assertEquals("a.wav", BackupPlan.uniqueName("a.wav", emptySet()))
        assertEquals("a-restored2.wav", BackupPlan.uniqueName("a.wav", setOf("a.wav")))
        assertEquals("a-restored3.wav", BackupPlan.uniqueName("a.wav", setOf("a.wav", "a-restored2.wav")))
    }

    @Test fun unfinishedNotesWaitForTranscription() {
        assertEquals(NoteStatus.WAITING, BackupPlan.restoredStatus(NoteStatus.QUEUED, true))
        assertEquals(NoteStatus.WAITING, BackupPlan.restoredStatus(NoteStatus.TRANSCRIBING, true))
        assertEquals(NoteStatus.WAITING, BackupPlan.restoredStatus(NoteStatus.WAITING, true))
        assertEquals(NoteStatus.FAILED, BackupPlan.restoredStatus(NoteStatus.RECORDING, true))
    }

    @Test fun aNoteThatWaitsForAMissingRecordingCannotWait() {
        assertEquals(NoteStatus.FAILED, BackupPlan.restoredStatus(NoteStatus.WAITING, false))
        val plan = BackupPlan.plan(emptySet(), emptySet(), listOf(note("a.wav", 1, NoteStatus.WAITING, withAudio = false)), emptySet(), false)
        assertEquals(NoteStatus.FAILED, plan.toAdd.single().status)
        assertFalse(plan.toAdd.single().hasAudio)
    }

    @Test fun finishedNotesKeepTheirStatus() {
        for (s in listOf(NoteStatus.DONE, NoteStatus.FAILED, NoteStatus.NO_SPEECH)) assertEquals(s, BackupPlan.restoredStatus(s, true))
        assertEquals(NoteStatus.DONE, BackupPlan.restoredStatus(NoteStatus.DONE, false))
    }

    @Test fun oldestNotesAreAddedFirst() {
        val plan = BackupPlan.plan(emptySet(), emptySet(), listOf(note("c.wav", 30), note("a.wav", 10), note("b.wav", 20)), setOf("a.wav", "b.wav", "c.wav"), false)
        assertEquals(listOf("a.wav", "b.wav", "c.wav"), plan.toAdd.map { it.audioFile })
    }
}

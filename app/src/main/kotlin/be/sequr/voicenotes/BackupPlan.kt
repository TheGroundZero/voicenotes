// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

/** A note that is already in the app, as far as a restore cares: the pair that tells two copies of one note apart from two notes. */
data class NoteKey(val createdAt: Long, val audioFile: String)

/** One note of a backup that will be added, with the file name and status it gets here. */
class PlannedNote(val source: BackupNote, val audioFile: String, val hasAudio: Boolean, val status: NoteStatus)

class RestorePlan(val toAdd: List<PlannedNote>, val alreadyPresent: Int)

/** Decides what a restore does, without touching anything. Pure, so it can be tested thoroughly. */
object BackupPlan {
    /**
     * @param existing the notes in the app now
     * @param existingFiles every file name in the app's recording folder (taken names, whether or not a note uses them)
     * @param stagedAudio the recordings that came with the backup and passed their checks
     * @param replace true when the existing notes are about to be removed: they are not compared against, and their copies are added again
     */
    fun plan(existing: Set<NoteKey>, existingFiles: Set<String>, backup: List<BackupNote>, stagedAudio: Set<String>, replace: Boolean): RestorePlan {
        val taken = HashSet(existingFiles)
        val toAdd = ArrayList<PlannedNote>()
        var present = 0
        for (note in backup.sortedBy { it.createdAt }) {
            if (!replace && NoteKey(note.createdAt, note.audioFile) in existing) {
                present++
                continue
            }
            val hasAudio = note.audioFile in stagedAudio
            val file = if (hasAudio) uniqueName(note.audioFile, taken) else note.audioFile
            if (hasAudio) taken.add(file)
            toAdd.add(PlannedNote(note, file, hasAudio, restoredStatus(note.status, hasAudio)))
        }
        return RestorePlan(toAdd, present)
    }

    /**
     * Nothing can still be running after a restore: queued or half-finished notes wait for the next transcription run, and a note
     * that waits for a recording the backup does not contain cannot be transcribed, so it counts as failed.
     */
    fun restoredStatus(status: NoteStatus, hasAudio: Boolean): NoteStatus = when (status) {
        NoteStatus.RECORDING -> NoteStatus.FAILED
        NoteStatus.QUEUED, NoteStatus.TRANSCRIBING, NoteStatus.WAITING -> if (hasAudio) NoteStatus.WAITING else NoteStatus.FAILED
        else -> status
    }

    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val base = name.removeSuffix(".wav")
        var i = 2
        while ("$base-restored$i.wav" in taken) i++
        return "$base-restored$i.wav"
    }
}

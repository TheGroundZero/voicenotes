// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Blocking functions: call from a background thread (the service) or Dispatchers.IO (the UI). */
@Dao
abstract class NoteDao {
    @Transaction
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<NoteWithTranscripts>>

    @Query("SELECT * FROM notes WHERE id = :id")
    abstract fun get(id: Long): Note?

    /** Oldest first, for exports. */
    @Transaction
    @Query("SELECT * FROM notes ORDER BY createdAt ASC")
    abstract fun listAll(): List<NoteWithTranscripts>

    @Query("SELECT COUNT(*) FROM transcripts WHERE noteId = :noteId")
    abstract fun countTranscripts(noteId: Long): Int

    // --- labels ---

    @Query("SELECT labels.id AS id, labels.name AS name, COUNT(note_labels.noteId) AS notes FROM labels LEFT JOIN note_labels ON note_labels.labelId = labels.id GROUP BY labels.id ORDER BY labels.name COLLATE NOCASE")
    abstract fun observeLabels(): Flow<List<LabelCount>>

    @Query("SELECT * FROM labels")
    abstract fun labels(): List<Label>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertLabel(label: Label): Long

    @Query("UPDATE labels SET name = :name WHERE id = :id")
    abstract fun renameLabel(id: Long, name: String)

    @Query("DELETE FROM labels WHERE id = :id")
    abstract fun deleteLabel(id: Long)

    @Query("DELETE FROM labels")
    abstract fun deleteAllLabels()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun link(link: NoteLabel)

    @Query("DELETE FROM note_labels WHERE noteId = :noteId AND labelId = :labelId")
    abstract fun unlink(noteId: Long, labelId: Long)

    /** What a restore compares against; see [BackupPlan]. */
    @Query("SELECT createdAt, audioFile FROM notes")
    abstract fun keys(): List<NoteKey>

    /** Notes that are being recorded or transcribed right now (or were, when the process died and has not been cleaned up yet). */
    @Query("SELECT COUNT(*) FROM notes WHERE status IN ('RECORDING', 'QUEUED', 'TRANSCRIBING')")
    abstract fun activeCount(): Int

    /** Removes every note (and its transcripts, by cascade). Only a restore that replaces everything calls this. */
    @Query("DELETE FROM notes")
    abstract fun deleteAll()

    @Query("SELECT COUNT(*) FROM notes WHERE audioFile = :audioFile")
    abstract fun countByFile(audioFile: String): Int

    @Insert
    abstract fun insert(note: Note): Long

    @Insert
    abstract fun insert(transcript: Transcript): Long

    @Query("UPDATE notes SET status = :status WHERE id = :id")
    abstract fun setStatus(id: Long, status: NoteStatus)

    @Query("UPDATE notes SET micSource = :micSource WHERE id = :id")
    abstract fun setMicSource(id: Long, micSource: String)

    @Query("UPDATE notes SET language = :language WHERE id = :id")
    abstract fun setLanguage(id: Long, language: String)

    @Query("DELETE FROM notes WHERE id = :id")
    abstract fun delete(id: Long)

    /**
     * After a process death nothing can still be recording or transcribing. A recording is cut short (FAILED); a
     * note that was only queued or being transcribed goes back to WAITING and is resumed, except the one that was
     * in flight when the process died ([inflightId]), which may be what killed it.
     */
    @Query(
        "UPDATE notes SET status = CASE WHEN status = 'RECORDING' THEN 'FAILED' WHEN id = :inflightId THEN 'FAILED' ELSE 'WAITING' END " +
            "WHERE status IN ('RECORDING', 'QUEUED', 'TRANSCRIBING')",
    )
    abstract fun failInterrupted(inflightId: Long)

    /**
     * Notes whose recording can go: fully transcribed (a failed, waiting or silent note keeps its audio), still having it,
     * and first transcribed before [before]. Their text is not touched.
     */
    @Query(
        "SELECT * FROM notes WHERE status = 'DONE' AND audioDeleted = 0 AND " +
            "(SELECT MIN(createdAt) FROM transcripts WHERE noteId = notes.id) < :before",
    )
    abstract fun audioRemovable(before: Long): List<Note>

    @Query("SELECT * FROM notes WHERE audioDeleted = 0 AND status != 'RECORDING'")
    abstract fun withAudio(): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE audioDeleted = 1")
    abstract fun countAudioRemoved(): Int

    @Query("UPDATE notes SET audioDeleted = 1 WHERE id = :id")
    abstract fun markAudioDeleted(id: Long)

    @Query("SELECT id FROM notes WHERE status = 'WAITING' ORDER BY createdAt")
    abstract fun waitingIds(): List<Long>

    @Query("UPDATE transcripts SET isCurrent = 0 WHERE noteId = :noteId")
    protected abstract fun clearCurrent(noteId: Long)

    @Query("UPDATE transcripts SET isCurrent = 1 WHERE id = :id")
    protected abstract fun markCurrent(id: Long)

    @Query("SELECT * FROM transcripts WHERE id = :id")
    abstract fun getTranscript(id: Long): Transcript?

    @Query("SELECT * FROM transcripts WHERE noteId = :noteId ORDER BY createdAt DESC")
    protected abstract fun transcriptsOf(noteId: Long): List<Transcript>

    @Query("DELETE FROM transcripts WHERE id = :id")
    protected abstract fun deleteTranscriptRow(id: Long)

    /** Adds a new version and makes it the current one. */
    @Transaction
    open fun addTranscript(transcript: Transcript): Long {
        clearCurrent(transcript.noteId)
        return insert(transcript.copy(id = 0, isCurrent = true))
    }

    @Transaction
    open fun makeCurrent(transcriptId: Long) {
        val t = getTranscript(transcriptId) ?: return
        clearCurrent(t.noteId)
        markCurrent(transcriptId)
    }

    /** Deleting the current version promotes the newest remaining one, so a note keeps a visible text. */
    @Transaction
    open fun deleteTranscript(transcriptId: Long) {
        val t = getTranscript(transcriptId) ?: return
        deleteTranscriptRow(transcriptId)
        if (t.isCurrent) transcriptsOf(t.noteId).firstOrNull()?.let { markCurrent(it.id) }
    }
}

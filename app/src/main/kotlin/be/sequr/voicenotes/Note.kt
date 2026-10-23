// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.io.File

/**
 * NO_SPEECH: nothing was transcribed because the recording has no speech; the user can still force it.
 * WAITING: stored and not transcribed yet (low battery, battery saver, or interrupted by a restart); see [BatteryPolicy].
 */
enum class NoteStatus { RECORDING, QUEUED, TRANSCRIBING, DONE, FAILED, NO_SPEECH, WAITING }

enum class TranscriptSource { WHISPER, MANUAL }

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** File name inside [NoteFiles.dir]. */
    val audioFile: String,
    /** The language to use for this note: [Transcriber.AUTO] until it has been resolved, then a code. */
    val language: String,
    val createdAt: Long,
    val status: NoteStatus,
    /**
     * Which microphone recorded the note: [MIC_PHONE], `bluetooth:<device name>`, or [MIC_UNKNOWN] for notes made
     * before this was stored. Makes it possible to compare recording conditions (phone in a pocket, a headset, the
     * car's hands-free system) without guessing.
     */
    @ColumnInfo(defaultValue = MIC_UNKNOWN) val micSource: String = MIC_UNKNOWN,
    /** The recording was removed (by hand or by [AudioCleanup]); the text stays. Such a note cannot be played or transcribed again. */
    @ColumnInfo(defaultValue = "0") val audioDeleted: Boolean = false,
)

const val MIC_PHONE = "phone"
const val MIC_UNKNOWN = "unknown"
const val MIC_BLUETOOTH_PREFIX = "bluetooth:" // a Bluetooth headset or other device
const val MIC_CAR_PREFIX = "car:" // a Bluetooth device that identifies itself as a car's audio or hands-free system

/** One version of a note's text. Re-transcribing and editing add versions; nothing is overwritten. */
@Entity(
    tableName = "transcripts",
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("noteId")],
)
data class Transcript(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val text: String,
    /** `small`, `base`, … or `manual` for edits. */
    val model: String,
    val language: String,
    /** True when the language was auto-detected rather than chosen. */
    val detected: Boolean,
    val source: TranscriptSource,
    val createdAt: Long,
    val isCurrent: Boolean,
)

data class NoteWithTranscripts(
    @Embedded val note: Note,
    @Relation(parentColumn = "id", entityColumn = "noteId") val transcripts: List<Transcript>,
) {
    val current: Transcript? get() = transcripts.firstOrNull { it.isCurrent }

    /** Older versions, newest first. */
    val history: List<Transcript> get() = transcripts.filter { !it.isCurrent }.sortedByDescending { it.createdAt }
}

object NoteFiles {
    fun dir(context: Context) = File(context.filesDir, "notes").apply { mkdirs() }
    fun audio(context: Context, note: Note) = File(dir(context), note.audioFile)
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-defined label. Names are unique ignoring case (the database enforces the ASCII part; [Labels] also treats accents as the same,
 * as search does). See [LabelName] for what a name may look like.
 */
@Entity(tableName = "labels", indices = [Index(value = ["name"], unique = true)])
data class Label(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
)

/** A note can have any number of labels, and a label any number of notes. Deleting either side removes the link only. */
@Entity(
    tableName = "note_labels",
    primaryKeys = ["noteId", "labelId"],
    foreignKeys = [
        ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Label::class, parentColumns = ["id"], childColumns = ["labelId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("labelId")],
)
data class NoteLabel(val noteId: Long, val labelId: Long)

/** A label with the number of notes that carry it, for the label list. */
data class LabelCount(val id: Long, val name: String, val notes: Int)

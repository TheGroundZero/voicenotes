// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

/** What came of asking for a label. */
sealed interface LabelResult {
    class Ok(val label: Label) : LabelResult
    /** The name is empty, too long or has characters a label cannot have (see [LabelName.clean]). */
    object Invalid : LabelResult
    /** Another label already has this name (ignoring case and accents). */
    class Taken(val existing: Label) : LabelResult
}

/** Creating, renaming, deleting and attaching labels. Blocking: call from a background thread. */
class Labels(private val db: NoteDatabase) {
    private val dao = db.noteDao()

    private fun existing(name: String, ignoreId: Long = -1): Label? {
        val folded = LabelName.fold(name)
        return dao.labels().firstOrNull { it.id != ignoreId && LabelName.fold(it.name) == folded }
    }

    /** Creates the label, or says which existing one already has that name. */
    fun create(raw: String): LabelResult {
        val name = LabelName.clean(raw) ?: return LabelResult.Invalid
        return db.runInTransaction<LabelResult> {
            existing(name)?.let { return@runInTransaction LabelResult.Taken(it) }
            LabelResult.Ok(Label(dao.insertLabel(Label(name = name)), name))
        }
    }

    /** The label with this name, created when it does not exist yet (used by a restore and by the note's "new label" field). */
    fun getOrCreate(raw: String): Label? = when (val r = create(raw)) {
        is LabelResult.Ok -> r.label
        is LabelResult.Taken -> r.existing
        LabelResult.Invalid -> null
    }

    fun rename(id: Long, raw: String): LabelResult {
        val name = LabelName.clean(raw) ?: return LabelResult.Invalid
        return db.runInTransaction<LabelResult> {
            existing(name, ignoreId = id)?.let { return@runInTransaction LabelResult.Taken(it) }
            dao.renameLabel(id, name)
            LabelResult.Ok(Label(id, name))
        }
    }

    /** Removes the label from every note; the notes stay. */
    fun delete(id: Long) = dao.deleteLabel(id)

    fun setOnNote(noteId: Long, labelId: Long, on: Boolean) {
        if (on) dao.link(NoteLabel(noteId, labelId)) else dao.unlink(noteId, labelId)
    }
}

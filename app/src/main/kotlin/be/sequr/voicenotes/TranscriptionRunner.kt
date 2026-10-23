// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context

/**
 * Transcribes one stored note and records the result. Shared by [RecordingService] (a note was just recorded, or
 * the user asked) and [TranscriptionJobService] (notes that waited for the battery). One note at a time: the
 * model needs about a gigabyte of memory.
 */
object TranscriptionRunner {
    private val lock = Any()

    /**
     * Adds a new transcript version and never overwrites an older one. [language] null means "what the note
     * says" (auto for a new recording). [force] skips the check for speech (the user asked for this one).
     * [onlyIfWaiting] makes this a no-op for a note that is no longer waiting (another job got there first).
     */
    fun run(context: Context, id: Long, language: String?, model: String?, force: Boolean, onlyIfWaiting: Boolean = false) =
        synchronized(lock) {
            val dao = NoteDatabase.get(context).noteDao()
            val note = dao.get(id) ?: return // deleted while waiting
            if (onlyIfWaiting && note.status != NoteStatus.WAITING) return
            if (note.audioDeleted || !NoteFiles.audio(context, note).exists()) return // nothing left to transcribe
            // If the process dies during this note, the next start must not try it again (a crash would loop).
            Settings.setInflightNote(context, id)
            try {
                dao.setStatus(id, NoteStatus.TRANSCRIBING)
                val outcome = Transcription.transcribe(context, NoteFiles.audio(context, note), language ?: note.language, model, checkForSpeech = !force)
                if (outcome == null) {
                    dao.setStatus(id, NoteStatus.FAILED)
                    return
                }
                if (outcome.result.noSpeech) {
                    dao.setStatus(id, NoteStatus.NO_SPEECH)
                    return
                }
                // Only a note's first transcript is appended automatically; re-transcribing and retrying do not repeat it.
                val isFirst = dao.countTranscripts(id) == 0
                val transcript = Transcript(
                    noteId = id,
                    text = outcome.result.text,
                    model = outcome.model,
                    language = outcome.result.language,
                    detected = outcome.result.detected,
                    source = TranscriptSource.WHISPER,
                    createdAt = System.currentTimeMillis(),
                    isCurrent = true,
                )
                dao.addTranscript(transcript)
                if (isFirst && transcript.text.isNotBlank()) NoteExport.autoAppend(context, note, transcript)
                dao.setLanguage(id, outcome.result.language)
                dao.setStatus(id, NoteStatus.DONE)
            } finally {
                Settings.setInflightNote(context, -1)
            }
        }
}

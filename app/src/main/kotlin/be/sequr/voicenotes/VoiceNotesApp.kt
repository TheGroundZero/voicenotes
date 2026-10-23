// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.Application
import java.text.SimpleDateFormat
import java.util.Locale

class VoiceNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dao = NoteDatabase.get(this).noteDao()
        // Same single thread as the service's writes, so this always runs before they do.
        Io.executor.execute {
            dao.failInterrupted(Settings.inflightNote(this))
            Settings.setInflightNote(this, -1)
            if (dao.waitingIds().isNotEmpty()) TranscriptionJobService.schedule(this)
            AudioCleanup.run(this) // does nothing unless the user turned automatic removal on
            AudioCleanup.schedule(this)
            importLegacyClips(dao)
        }
    }

    /** Clips recorded before the database existed: `<yyyyMMdd-HHmmss>-<lang>.wav`, optional `.txt`. */
    private fun importLegacyClips(dao: NoteDao) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        NoteFiles.dir(this).listFiles { f -> f.extension == "wav" }.orEmpty().forEach { wav ->
            if (dao.countByFile(wav.name) > 0) return@forEach
            val txt = java.io.File(wav.path.removeSuffix(".wav") + ".txt")
            val transcript = txt.takeIf { it.exists() }?.readText()?.trim()
            val name = wav.nameWithoutExtension
            val createdAt = runCatching { stamp.parse(name.substringBeforeLast('-').substringBefore('.'))!!.time }.getOrDefault(wav.lastModified())
            val language = name.substringAfterLast('-', "").takeIf { it.length == 2 } ?: Transcriber.AUTO
            val id = dao.insert(
                Note(
                    audioFile = wav.name,
                    language = language,
                    createdAt = createdAt,
                    status = if (transcript != null) NoteStatus.DONE else NoteStatus.FAILED,
                ),
            )
            if (transcript != null) {
                dao.addTranscript(Transcript(noteId = id, text = transcript, model = "unknown", language = language, detected = false, source = TranscriptSource.WHISPER, createdAt = createdAt, isCurrent = true))
            }
            txt.delete()
        }
    }
}

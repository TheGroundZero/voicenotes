// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** What the user chose for a backup. [passphrase] null means a plain, unencrypted archive. */
class BackupRequest(val includeAudio: Boolean, val passphrase: CharArray?)

enum class RestoreMode { MERGE, REPLACE }

/** What a backup file holds, and what restoring it would do here. */
class BackupPreview(val manifest: BackupManifest, val encrypted: Boolean, val newNotes: Int, val alreadyPresent: Int, val missingModels: List<String>)

class RestoreResult(val added: Int, val alreadyPresent: Int, val waitingForTranscription: Int)

/** Needs a passphrase before the file can be read. */
class PassphraseRequired : IOException("This backup is protected by a passphrase")

/**
 * Backup and restore through the Storage Access Framework: the user picks the file, and whatever app provides that location
 * (for example Nextcloud) does any uploading. Nothing here touches the network.
 */
object BackupManager {
    /** Writes the backup to [uri] and reads it back to check it. [progress] gets a short description of the current step. */
    fun create(context: Context, uri: Uri, request: BackupRequest, progress: (String) -> Unit): Settings.LastBackup {
        val dao = NoteDatabase.get(context).noteDao()
        progress("Reading your notes")
        val notes = dao.listAll().filter { it.note.status != NoteStatus.RECORDING }
        val backupNotes = notes.map { n ->
            val file = NoteFiles.audio(context, n.note)
            val audio = if (request.includeAudio && !n.note.audioDeleted && file.isFile) checksum(file) else null
            BackupNote(
                createdAt = n.note.createdAt, audioFile = n.note.audioFile, language = n.note.language, status = n.note.status,
                micSource = n.note.micSource, audioDeleted = audio == null, // no recording in the archive, for whatever reason
                audio = audio,
                transcripts = n.transcripts.sortedBy { it.createdAt }.map {
                    BackupTranscript(it.text, it.model, it.language, it.detected, it.source, it.createdAt, it.isCurrent)
                },
                labels = n.labels.map { it.name },
            )
        }
        val manifest = BackupManifest(
            formatVersion = BackupArchive.FORMAT_VERSION,
            appVersion = packageInfo(context).versionName ?: "",
            appVersionCode = packageInfo(context).longVersionCode.toInt(),
            createdAt = System.currentTimeMillis(),
            noteCount = backupNotes.size,
            audioCount = backupNotes.count { it.audio != null },
            models = notes.flatMap { it.transcripts }.filter { it.source == TranscriptSource.WHISPER }.map { it.model }.distinct().sorted(),
            settings = BackupSettings(Settings.preferredLanguages(context), Settings.recordLanguage(context), Settings.defaultModel(context)),
            labels = dao.labels().map { it.name }.sortedBy { LabelName.fold(it) },
        )
        val resolver = context.contentResolver
        try {
            progress("Writing the backup" + if (request.passphrase != null) " (encrypting)" else "")
            val raw = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the file for writing")
            val out = if (request.passphrase != null) BackupCrypto.encrypt(raw, request.passphrase) else raw
            out.use { BackupArchive.write(it, manifest, backupNotes) { n -> NoteFiles.dir(context).resolve(n.audioFile).inputStream() } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) } // do not leave a half-written file that looks like a backup
            throw e
        }
        progress("Checking the backup")
        val read = openInput(context, uri)
        try {
            val input = if (request.passphrase != null) BackupCrypto.decrypt(read, request.passphrase) else read
            BackupArchive.open(input).use { opened ->
                if (opened.manifest.noteCount != manifest.noteCount) throw IOException("The file does not match what was written")
                opened.stageAudio(null)
            }
        } catch (e: Exception) {
            throw IOException("The backup was written but failed its check, so do not rely on it: ${e.message}", e)
        }
        return Settings.LastBackup(manifest.createdAt, manifest.noteCount, request.passphrase != null, manifest.includesAudio)
            .also { Settings.setLastBackup(context, it) }
    }

    private fun checksum(file: File): BackupAudio {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return BackupAudio(file.length(), BackupArchive.hex(digest.digest()))
    }

    private fun openInput(context: Context, uri: Uri): BufferedInputStream =
        BufferedInputStream(context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the file"))

    /** Opens [uri] as a backup: decrypting when needed, or throwing [PassphraseRequired] when no passphrase was given. */
    private fun <T> withBackup(context: Context, uri: Uri, passphrase: CharArray?, block: (BackupArchive.Opened, Boolean) -> T): T {
        val input = openInput(context, uri)
        try {
            input.mark(8)
            val head = ByteArray(4)
            val n = input.read(head)
            input.reset()
            val encrypted = n == 4 && BackupCrypto.isEncrypted(head)
            val plain: InputStream = if (encrypted) {
                if (passphrase == null || passphrase.isEmpty()) throw PassphraseRequired()
                BackupCrypto.decrypt(input, passphrase)
            } else input
            return BackupArchive.open(plain).use { block(it, encrypted) }
        } finally {
            input.close()
        }
    }

    /** Reads only the beginning of the file: what it holds and what a restore would do. */
    fun inspect(context: Context, uri: Uri, passphrase: CharArray?): BackupPreview {
        val dao = NoteDatabase.get(context).noteDao()
        val existing = dao.keys().toSet()
        return withBackup(context, uri, passphrase) { opened, encrypted ->
            val already = opened.notes.count { NoteKey(it.createdAt, it.audioFile) in existing }
            val installed = ModelStore.ids(context).toSet()
            BackupPreview(opened.manifest, encrypted, opened.notes.size - already, already, opened.manifest.models.filter { it !in installed })
        }
    }

    /**
     * Restores from [uri]. Everything is read and checked before anything here changes, recordings are copied before the database is
     * touched, and the notes go in as one transaction, so a failure leaves the existing notes as they were.
     */
    fun restore(context: Context, uri: Uri, passphrase: CharArray?, mode: RestoreMode, withSettings: Boolean, progress: (String) -> Unit): RestoreResult {
        val db = NoteDatabase.get(context)
        val dao = db.noteDao()
        if (mode == RestoreMode.REPLACE && (dao.activeCount() > 0 || RecordingService.recording.value)) {
            throw IOException("A note is being recorded or transcribed. Wait until that has finished, then try again.")
        }
        val staging = File(context.cacheDir, "restore-${System.currentTimeMillis()}").apply { mkdirs() }
        val moved = ArrayList<File>()
        var committed = false
        try {
            val staged = withBackup(context, uri, passphrase) { o, _ ->
                val needed = o.notes.sumOf { it.audio?.size ?: 0L }
                if (needed + SAFETY_MARGIN > staging.usableSpace) throw IOException("There is not enough free space on the phone to restore this backup.")
                progress("Reading the backup")
                val names = o.stageAudio(staging) { count -> progress("Reading recordings ($count of ${o.manifest.audioCount})") }
                Staged(o.manifest, o.notes, names)
            }
            val manifest = staged.manifest
            val notes = staged.notes
            progress("Adding the notes")
            val replace = mode == RestoreMode.REPLACE
            val notesDir = NoteFiles.dir(context)
            val oldFiles = if (replace) dao.listAll().map { it.note.audioFile } else emptyList()
            val plan = BackupPlan.plan(dao.keys().toSet(), (notesDir.list()?.toSet() ?: emptySet()) + dao.keys().map { it.audioFile }, notes, staged.audio, replace)
            for (p in plan.toAdd.filter { it.hasAudio }) {
                val target = File(notesDir, p.audioFile)
                val source = File(staging, p.source.audioFile)
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = false)
                    source.delete()
                }
                moved.add(target)
            }
            val labels = Labels(db)
            db.runInTransaction {
                if (replace) {
                    dao.deleteAll()
                    dao.deleteAllLabels()
                }
                manifest.labels.forEach { labels.getOrCreate(it) } // also the labels no note uses
                for (p in plan.toAdd) {
                    val id = dao.insert(
                        Note(
                            audioFile = p.audioFile, language = p.source.language, createdAt = p.source.createdAt, status = p.status,
                            micSource = p.source.micSource, audioDeleted = p.source.audioDeleted || !p.hasAudio,
                        ),
                    )
                    p.source.labels.forEach { name -> labels.getOrCreate(name)?.let { label -> labels.setOnNote(id, label.id, true) } }
                    p.source.transcripts.forEach {
                        dao.insert(Transcript(noteId = id, text = it.text, model = it.model, language = it.language, detected = it.detected, source = it.source, createdAt = it.createdAt, isCurrent = it.isCurrent))
                    }
                }
            }
            committed = true // from here on the notes exist, so their recordings must stay whatever else goes wrong
            val waiting = plan.toAdd.count { it.status == NoteStatus.WAITING }
            runCatching {
                // New notes never reuse a name that was taken, so every file of the replaced notes can go.
                if (replace) oldFiles.forEach { File(notesDir, it).delete() }
                if (withSettings) applySettings(context, manifest.settings)
                if (waiting > 0) TranscriptionJobService.schedule(context)
            }
            return RestoreResult(plan.toAdd.size, plan.alreadyPresent, waiting)
        } catch (e: Exception) {
            if (!committed) moved.forEach { it.delete() }
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    private class Staged(val manifest: BackupManifest, val notes: List<BackupNote>, val audio: Set<String>)

    private fun packageInfo(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0)

    private fun applySettings(context: Context, settings: BackupSettings) {
        settings.preferredLanguages.filter { it in Settings.supportedLanguages }.takeIf { it.isNotEmpty() }?.let { Settings.setPreferredLanguages(context, it) }
        Settings.setRecordLanguage(context, settings.recordLanguage)
        // Only a model that is installed here can be the default; otherwise the best installed one is used, as before.
        settings.defaultModel?.takeIf { it in ModelStore.ids(context) }?.let { Settings.setDefaultModel(context, it) }
    }

    private const val SAFETY_MARGIN = 100L * 1024 * 1024
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The file is not a usable backup (wrong kind of file, damaged, or made by a newer version). The message is meant for the user. */
class BackupFormatException(message: String) : IOException(message)

class BackupSettings(val preferredLanguages: List<String>, val recordLanguage: String, val defaultModel: String?)

class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val appVersionCode: Int,
    val createdAt: Long,
    val noteCount: Int,
    val audioCount: Int,
    val models: List<String>,
    val settings: BackupSettings,
    /** Every label that exists, including the ones no note uses (format version 2). */
    val labels: List<String> = emptyList(),
) {
    val includesAudio: Boolean get() = audioCount > 0
}

class BackupAudio(val size: Long, val sha256: String)

class BackupTranscript(
    val text: String,
    val model: String,
    val language: String,
    val detected: Boolean,
    val source: TranscriptSource,
    val createdAt: Long,
    val isCurrent: Boolean,
)

/** One note as stored in a backup. [audio] is null when the recording is not in the archive (removed, or left out on purpose). */
class BackupNote(
    val createdAt: Long,
    val audioFile: String,
    val language: String,
    val status: NoteStatus,
    val micSource: String,
    val audioDeleted: Boolean,
    val audio: BackupAudio?,
    val transcripts: List<BackupTranscript>,
    /** Label names (new in format version 2; a version 1 backup has none). */
    val labels: List<String> = emptyList(),
)

/**
 * The archive format: a ZIP file with `manifest.json`, `notes.json` and `audio/<name>.wav`, in that order, so a reader can show
 * what a backup holds after reading only the beginning. Notes are stored as data rather than as a copy of the database, so a
 * restore can merge into existing notes and does not depend on the database schema. See docs/BACKUP.md.
 */
object BackupArchive {
    /** 2 adds the labels of each note. Version 1 backups (0.1.0) are still read. */
    const val FORMAT_VERSION = 2
    private const val FORMAT_NAME = "voicenotes-backup"
    private const val MANIFEST = "manifest.json"
    private const val NOTES = "notes.json"
    private const val AUDIO_DIR = "audio/"
    const val MAX_NOTES = 200_000
    private const val MAX_JSON_BYTES = 256L * 1024 * 1024
    const val MAX_AUDIO_BYTES = 512L * 1024 * 1024
    private val FILE_NAME = Regex("[A-Za-z0-9._-]{1,100}\\.wav")

    fun validFileName(name: String) = FILE_NAME.matches(name) && !name.startsWith(".")

    /** Writes the archive. [openAudio] returns the recording of a note that has an [BackupNote.audio] entry. */
    fun write(out: OutputStream, manifest: BackupManifest, notes: List<BackupNote>, openAudio: (BackupNote) -> InputStream) {
        val zip = ZipOutputStream(out)
        zip.setLevel(6)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifestJson(manifest).toString(1).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(NOTES))
        zip.write(notesJson(notes).toString(1).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for (note in notes) {
            val audio = note.audio ?: continue
            zip.putNextEntry(ZipEntry(AUDIO_DIR + note.audioFile))
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            openAudio(note).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    zip.write(buffer, 0, n)
                    total += n
                }
            }
            zip.closeEntry()
            // The recording was measured before it was written; if it changed in between, the backup would be wrong.
            if (total != audio.size || hex(digest.digest()) != audio.sha256) throw IOException("${note.audioFile} changed while it was being backed up")
        }
        zip.finish()
        zip.flush()
    }

    /** The beginning of a backup, read from a fresh stream. Call [Opened.stageAudio] to read the recordings as well. */
    class Opened internal constructor(val manifest: BackupManifest, val notes: List<BackupNote>, private val zip: ZipInputStream) : AutoCloseable {
        /**
         * Reads every recording into [dir] (or just checks them when it is null), checking its size and checksum against `notes.json`. Returns the file names staged.
         * Anything that is not a declared recording (other names, folders, path tricks) makes the whole backup unusable.
         */
        fun stageAudio(dir: File?, onProgress: (Int) -> Unit = {}): Set<String> {
            val declared = notes.mapNotNull { n -> n.audio?.let { n.audioFile to it } }.toMap()
            val staged = LinkedHashSet<String>()
            while (true) {
                val entry = try { zip.nextEntry } catch (e: ZipException) { throw BackupFormatException("The backup is damaged: ${e.message}") } ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.startsWith(AUDIO_DIR) || !validFileName(name.removePrefix(AUDIO_DIR))) throw BackupFormatException("The backup contains an unexpected file: ${name.take(60)}")
                val file = name.removePrefix(AUDIO_DIR)
                val expected = declared[file] ?: throw BackupFormatException("The backup contains a recording no note refers to: $file")
                if (!staged.add(file)) throw BackupFormatException("The backup contains $file twice")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    (if (dir != null) File(dir, file).outputStream() else OutputStream.nullOutputStream()).use { target ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > expected.size || total > MAX_AUDIO_BYTES) throw BackupFormatException("$file is larger than the backup says")
                            digest.update(buffer, 0, n)
                            target.write(buffer, 0, n)
                        }
                    }
                } catch (e: ZipException) {
                    throw BackupFormatException("The backup is damaged: ${e.message}")
                }
                if (total != expected.size || hex(digest.digest()) != expected.sha256) throw BackupFormatException("The recording $file is damaged")
                onProgress(staged.size)
            }
            val missing = declared.keys - staged
            if (missing.isNotEmpty()) throw BackupFormatException("The backup is incomplete: ${missing.size} recording(s) are missing")
            return staged
        }

        override fun close() = zip.close()
    }

    /** Reads and checks `manifest.json` and `notes.json`. [BackupFormatException] carries a user-readable reason. */
    fun open(input: InputStream): Opened {
        val zip = ZipInputStream(input)
        try {
            val manifestBytes = readEntry(zip, MANIFEST)
            val manifest = parseManifest(manifestBytes)
            val notesBytes = readEntry(zip, NOTES)
            val notes = parseNotes(notesBytes)
            if (notes.size != manifest.noteCount) throw BackupFormatException("The backup is damaged: the note count does not match")
            return Opened(manifest, notes, zip)
        } catch (e: Exception) {
            zip.close()
            throw when (e) {
                is BackupFormatException, is CorruptBackupException, is WrongPassphraseException -> e
                is ZipException -> BackupFormatException("This is not a Voice Notes backup, or it is damaged")
                is JSONException -> BackupFormatException("The backup is damaged: ${e.message}")
                else -> e
            }
        }
    }

    private fun readEntry(zip: ZipInputStream, expectedName: String): ByteArray {
        val entry = zip.nextEntry ?: throw BackupFormatException("This is not a Voice Notes backup")
        if (entry.name != expectedName) throw BackupFormatException("This is not a Voice Notes backup")
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_JSON_BYTES) throw BackupFormatException("The backup is damaged: $expectedName is too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // --- JSON ---

    private fun manifestJson(m: BackupManifest) = JSONObject().apply {
        put("format", FORMAT_NAME)
        put("formatVersion", m.formatVersion)
        put("appVersion", m.appVersion)
        put("appVersionCode", m.appVersionCode)
        put("createdAt", m.createdAt)
        put("noteCount", m.noteCount)
        put("audioCount", m.audioCount)
        put("models", JSONArray(m.models))
        put("labels", JSONArray(m.labels))
        put(
            "settings",
            JSONObject().apply {
                put("preferredLanguages", JSONArray(m.settings.preferredLanguages))
                put("recordLanguage", m.settings.recordLanguage)
                put("defaultModel", m.settings.defaultModel ?: JSONObject.NULL)
            },
        )
    }

    private fun parseManifest(bytes: ByteArray): BackupManifest {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        if (json.optString("format") != FORMAT_NAME) throw BackupFormatException("This is not a Voice Notes backup")
        val version = json.getInt("formatVersion")
        if (version > FORMAT_VERSION) throw BackupFormatException("This backup was made by a newer version of Voice Notes. Update the app first.")
        if (version < 1) throw BackupFormatException("The backup is damaged: unknown format version")
        val noteCount = json.getInt("noteCount")
        if (noteCount < 0 || noteCount > MAX_NOTES) throw BackupFormatException("The backup is damaged: unreasonable note count")
        val settings = json.getJSONObject("settings")
        return BackupManifest(
            formatVersion = version,
            appVersion = json.optString("appVersion"),
            appVersionCode = json.optInt("appVersionCode"),
            createdAt = json.getLong("createdAt"),
            noteCount = noteCount,
            audioCount = json.getInt("audioCount"),
            models = strings(json.getJSONArray("models")),
            labels = json.optJSONArray("labels")?.let(::strings).orEmpty().mapNotNull { LabelName.clean(it) }.distinct(),
            settings = BackupSettings(
                preferredLanguages = strings(settings.getJSONArray("preferredLanguages")),
                recordLanguage = settings.getString("recordLanguage"),
                defaultModel = if (settings.isNull("defaultModel")) null else settings.getString("defaultModel"),
            ),
        )
    }

    private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }

    private fun notesJson(notes: List<BackupNote>) = JSONObject().put(
        "notes",
        JSONArray().apply {
            for (n in notes) {
                put(
                    JSONObject().apply {
                        put("createdAt", n.createdAt)
                        put("audioFile", n.audioFile)
                        put("language", n.language)
                        put("status", n.status.name)
                        put("micSource", n.micSource)
                        put("audioDeleted", n.audioDeleted)
                        put("audio", n.audio?.let { JSONObject().put("size", it.size).put("sha256", it.sha256) } ?: JSONObject.NULL)
                        put("labels", JSONArray(n.labels))
                        put(
                            "transcripts",
                            JSONArray().apply {
                                for (t in n.transcripts) {
                                    put(
                                        JSONObject().apply {
                                            put("text", t.text)
                                            put("model", t.model)
                                            put("language", t.language)
                                            put("detected", t.detected)
                                            put("source", t.source.name)
                                            put("createdAt", t.createdAt)
                                            put("isCurrent", t.isCurrent)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        },
    )

    private fun parseNotes(bytes: ByteArray): List<BackupNote> {
        val array = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("notes")
        if (array.length() > MAX_NOTES) throw BackupFormatException("The backup is damaged: unreasonable note count")
        val seenFiles = HashSet<String>()
        return List(array.length()) { i ->
            val n = array.getJSONObject(i)
            val file = n.getString("audioFile")
            if (!validFileName(file)) throw BackupFormatException("The backup contains an invalid file name")
            if (!seenFiles.add(file)) throw BackupFormatException("The backup lists $file twice")
            val audio = if (n.isNull("audio")) null else n.getJSONObject("audio").let { a ->
                val size = a.getLong("size")
                val sha = a.getString("sha256")
                if (size < 0 || size > MAX_AUDIO_BYTES || !SHA256.matches(sha)) throw BackupFormatException("The backup is damaged: bad recording details")
                BackupAudio(size, sha)
            }
            val transcripts = n.getJSONArray("transcripts").let { ts ->
                List(ts.length()) { j ->
                    val t = ts.getJSONObject(j)
                    BackupTranscript(
                        text = t.getString("text"),
                        model = t.getString("model"),
                        language = t.getString("language"),
                        detected = t.getBoolean("detected"),
                        source = enumOrFail<TranscriptSource>(t.getString("source")),
                        createdAt = t.getLong("createdAt"),
                        isCurrent = t.getBoolean("isCurrent"),
                    )
                }
            }
            BackupNote(
                createdAt = n.getLong("createdAt"),
                audioFile = file,
                language = n.getString("language"),
                status = enumOrFail<NoteStatus>(n.getString("status")),
                micSource = n.getString("micSource"),
                audioDeleted = n.getBoolean("audioDeleted"),
                audio = audio,
                transcripts = transcripts,
                labels = n.optJSONArray("labels")?.let { l -> List(l.length()) { k -> l.getString(k) } }.orEmpty().mapNotNull { LabelName.clean(it) }.distinct(),
            )
        }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")

    private inline fun <reified E : Enum<E>> enumOrFail(value: String): E =
        enumValues<E>().firstOrNull { it.name == value } ?: throw BackupFormatException("The backup contains an unknown value: ${value.take(30)}")

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

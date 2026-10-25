// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Getting notes out of the app. Everything here runs because the user asked: sharing goes through
 * the system share sheet, files are only written to places the user picked.
 */
object NoteExport {
    /** A guard against reading a huge file into memory when appending. */
    private const val MAX_APPEND_TARGET_BYTES = 50L * 1024 * 1024

    private fun timestamp(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    /** A label as a Markdown/Obsidian tag: spaces become hyphens. */
    fun tag(label: String): String = "#" + label.trim().replace(Regex("\\s+"), "-")

    /** One note as a Markdown section: a heading with date and language, the text, and its labels as tags when it has any. */
    fun entry(createdAt: Long, language: String, text: String, labels: List<String> = emptyList()): String =
        "## ${timestamp(createdAt)} · ${language.uppercase()}\n\n${text.trim()}\n\n" +
            if (labels.isEmpty()) "" else labels.joinToString(" ", prefix = "Labels: ", postfix = "\n\n", transform = ::tag)

    /** Null when the note has no text yet. */
    fun entry(item: NoteWithTranscripts): String? {
        val current = item.current?.takeIf { it.text.isNotBlank() } ?: return null
        return entry(item.note.createdAt, current.language, current.text, item.labels.map { it.name }.sortedBy { LabelName.fold(it) })
    }

    fun shareText(context: Context, item: NoteWithTranscripts) {
        val text = item.current?.text?.takeIf { it.isNotBlank() } ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text.trim())
            putExtra(Intent.EXTRA_SUBJECT, "Note ${timestamp(item.note.createdAt)}")
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    fun shareAudio(context: Context, note: Note) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", NoteFiles.audio(context, note))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    /**
     * Adds [text] to the end of the file at [uri]. Blocking.
     *
     * Not done with the "append" open mode: some document providers treat it as "overwrite", which
     * would wipe the user's file. Instead the existing content is read first and written back with
     * the new text; if reading fails, nothing is written.
     */
    fun append(context: Context, uri: Uri, text: String): Result<Unit> = runCatching {
        val resolver = context.contentResolver
        val existing = resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytes((MAX_APPEND_TARGET_BYTES + 1).toInt())
            if (bytes.size > MAX_APPEND_TARGET_BYTES) throw IOException("The target file is larger than 50 MB")
            bytes
        } ?: throw IOException("Could not read the target file")
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write to the target file")
        out.use {
            it.write(existing)
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    /** Name of the picked file, for display. */
    fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"

    /** All notes that have text, oldest first, as one Markdown document. */
    fun exportAll(context: Context, uri: Uri, notes: List<NoteWithTranscripts>): Result<Int> = runCatching {
        val entries = notes.mapNotNull(::entry)
        val document = buildString {
            append("# Voice Notes export\n\nExported ${timestamp(System.currentTimeMillis())}\n\n")
            entries.forEach(::append)
        }
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write to the file")
        out.use { it.write(document.toByteArray(Charsets.UTF_8)) }
        entries.size
    }

    /** Called by the service after a new note's first transcript; does nothing unless the user turned it on. */
    fun autoAppend(context: Context, note: Note, transcript: Transcript) {
        val target = Settings.appendTarget(context)?.takeIf { Settings.autoAppend(context) } ?: return
        val result = append(context, Uri.parse(target), entry(note.createdAt, transcript.language, transcript.text))
        Settings.setAppendError(context, result.exceptionOrNull()?.let { it.message ?: "Append failed" })
    }
}

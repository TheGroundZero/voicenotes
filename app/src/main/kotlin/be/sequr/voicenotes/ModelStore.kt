// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class InstalledModel(val id: String, val sizeBytes: Long, val spec: ModelSpec?)

sealed interface ImportResult {
    /** [verified] is true when the file matched a catalog hash. */
    data class Imported(val id: String, val verified: Boolean) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/** Models on disk. No network code here; downloading is [ModelDownloader]'s job alone. */
object ModelStore {
    /** Every whisper.cpp model file starts with the 32-bit magic 0x67676d6c, little endian. */
    private val MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)
    private val FILE = Regex("""ggml-(.+)\.bin""")

    fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context, id: String) = File(dir(context), "ggml-$id.bin")

    /** Where the speech check lives. Not in [dir]: it must never show up as a transcription model. (Versions up to 0.5.1 extracted their bundled copy here.) */
    fun speechCheckFile(context: Context) = speechCheckFile(context.filesDir).also { it.parentFile?.mkdirs() }

    /** `files/vad/ggml-silero-v5.1.2.bin`: where versions up to 0.5.1 extracted their bundled copy, so those phones keep it. */
    fun speechCheckFile(filesDir: File) = File(File(filesDir, "vad"), ModelCatalog.speechCheck.fileName)

    /** The file a download of [spec] ends up in. */
    fun targetFor(context: Context, spec: ModelSpec) = if (spec.id == ModelCatalog.speechCheck.id) speechCheckFile(context) else file(context, spec.id)

    /** Catalog models first (best first), then anything else the user imported. */
    fun installed(context: Context): List<InstalledModel> {
        val found = dir(context).listFiles().orEmpty().mapNotNull { f ->
            FILE.matchEntire(f.name)?.let { InstalledModel(it.groupValues[1], f.length(), ModelCatalog.byId(it.groupValues[1])) }
        }
        val order = ModelCatalog.models.map { it.id }
        return found.sortedWith(compareBy({ order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.id }))
    }

    /** The model used when none is asked for: the user's default if installed, otherwise the best one. */
    fun resolve(context: Context, requested: String? = null): InstalledModel? {
        val all = installed(context)
        return all.firstOrNull { it.id == requested } ?: all.firstOrNull { it.id == Settings.defaultModel(context) } ?: all.firstOrNull()
    }

    /** Installed model ids with the default first, for pickers. */
    fun ids(context: Context): List<String> {
        val all = installed(context).map { it.id }
        val default = resolve(context)?.id
        return if (default == null) all else listOf(default) + (all - default)
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        if (Settings.defaultModel(context) == id) Settings.setDefaultModel(context, null)
    }

    /**
     * Copies a model the user picked into the app. Accepts only files with the whisper.cpp magic.
     * A file whose SHA-256 matches a catalog entry is installed as that model; anything else is
     * installed as a custom model, never under a catalog name. Blocking.
     */
    fun importFrom(context: Context, uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "model.bin"
        val tmp = File(dir(context), "import.tmp")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openInputStream(uri) ?: return ImportResult.Failed("Could not open the file")
            input.use {
                val head = ByteArray(MAGIC.size)
                if (it.readNBytes(head, 0, head.size) != head.size || !head.contentEquals(MAGIC)) {
                    return ImportResult.Failed("This is not a whisper.cpp model file (ggml format)")
                }
                digest.update(head)
                tmp.outputStream().use { out ->
                    out.write(head)
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = it.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash == ModelCatalog.speechCheck.sha256) {          // the speech check, not a transcription model
                val target = speechCheckFile(context)
                target.delete()
                return if (tmp.renameTo(target)) ImportResult.Imported(ModelCatalog.speechCheck.title, verified = true) else ImportResult.Failed("Could not store the speech check")
            }
            val spec = ModelCatalog.models.firstOrNull { it.sha256 == hash }
            val id = spec?.id ?: customId(displayName)
            file(context, id).delete()
            if (!tmp.renameTo(file(context, id))) return ImportResult.Failed("Could not store the model")
            ImportResult.Imported(id, verified = spec != null)
        } catch (e: IOException) {
            ImportResult.Failed(e.message ?: "Import failed")
        } finally {
            tmp.delete()
        }
    }

    /** Removes the speech check; notes are then transcribed without it until it is downloaded or imported again. */
    fun deleteSpeechCheck(context: Context) {
        speechCheckFile(context).delete()
    }

    private fun customId(displayName: String): String {
        val base = displayName.substringAfterLast('/').removeSuffix(".bin").removePrefix("ggml-")
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_', '-').ifBlank { "custom" }
        // A custom file must never take a catalog name, or it would pass for a verified model.
        return if (ModelCatalog.downloadable(base) != null) "custom-$base" else base
    }
}

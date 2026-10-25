// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val settings = BackupSettings(listOf("en", "nl"), "auto", "large-v3-turbo-q8_0")

    private fun sha(bytes: ByteArray) = BackupArchive.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun note(file: String, createdAt: Long, audio: ByteArray?, status: NoteStatus = NoteStatus.DONE, text: String = "Hello wörld \"quoted\"\nsecond line") = BackupNote(
        createdAt = createdAt, audioFile = file, language = "en", status = status, micSource = "phone", audioDeleted = audio == null,
        audio = audio?.let { BackupAudio(it.size.toLong(), sha(it)) },
        transcripts = listOf(
            BackupTranscript(text, "small", "en", true, TranscriptSource.WHISPER, createdAt + 1, false),
            BackupTranscript("$text (edited)", "manual", "en", false, TranscriptSource.MANUAL, createdAt + 2, true),
        ),
    )

    private fun manifest(notes: List<BackupNote>) = BackupManifest(
        BackupArchive.FORMAT_VERSION, "0.1.0", 2, 1_700_000_000_000, notes.size, notes.count { it.audio != null }, listOf("small"), settings,
    )

    private fun build(notes: List<BackupNote>, audio: Map<String, ByteArray>, manifest: BackupManifest = manifest(notes)): ByteArray {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, manifest, notes) { ByteArrayInputStream(audio.getValue(it.audioFile)) }
        return out.toByteArray()
    }

    private val wavA = ByteArray(5000) { (it % 251).toByte() }
    private val wavB = ByteArray(300) { (it * 7).toByte() }

    @Test fun roundTripKeepsEverything() {
        val notes = listOf(note("a.wav", 1000, wavA), note("b.wav", 2000, wavB, NoteStatus.WAITING), note("c.wav", 3000, null))
        val bytes = build(notes, mapOf("a.wav" to wavA, "b.wav" to wavB))
        BackupArchive.open(ByteArrayInputStream(bytes)).use { opened ->
            assertEquals(3, opened.manifest.noteCount)
            assertEquals(2, opened.manifest.audioCount)
            assertEquals(listOf("en", "nl"), opened.manifest.settings.preferredLanguages)
            assertEquals("large-v3-turbo-q8_0", opened.manifest.settings.defaultModel)
            assertEquals(notes.map { it.audioFile }, opened.notes.map { it.audioFile })
            val first = opened.notes[0]
            assertEquals(NoteStatus.DONE, first.status)
            assertEquals(2, first.transcripts.size)
            assertEquals("Hello wörld \"quoted\"\nsecond line (edited)", first.transcripts.single { it.isCurrent }.text)
            assertEquals(TranscriptSource.MANUAL, first.transcripts.single { it.isCurrent }.source)
            assertNull(opened.notes[2].audio)
            val dir = temp.newFolder()
            assertEquals(setOf("a.wav", "b.wav"), opened.stageAudio(dir))
            assertTrue(dir.resolve("a.wav").readBytes().contentEquals(wavA))
            assertTrue(dir.resolve("b.wav").readBytes().contentEquals(wavB))
        }
    }

    @Test fun labelsSurviveTheRoundTrip() {
        val labelled = BackupNote(1000, "a.wav", "en", NoteStatus.DONE, "phone", true, null, emptyList(), listOf("Shopping", "to do"))
        val bare = BackupNote(2000, "b.wav", "en", NoteStatus.DONE, "phone", true, null, emptyList())
        val m = BackupManifest(BackupArchive.FORMAT_VERSION, "0.2.0", 3, 1, 2, 0, emptyList(), settings, labels = listOf("Shopping", "to do", "unused"))
        BackupArchive.open(ByteArrayInputStream(build(listOf(labelled, bare), emptyMap(), m))).use {
            assertEquals(listOf("Shopping", "to do"), it.notes[0].labels)
            assertTrue(it.notes[1].labels.isEmpty())
            assertEquals(listOf("Shopping", "to do", "unused"), it.manifest.labels)
        }
    }

    @Test fun aVersion1BackupFromRelease020IsStillRead() {
        val notes = listOf(note("a.wav", 1000, wavA))
        val m = manifest(notes)
        val manifestJson = org.json.JSONObject(String(manifestBytes(notes, m))).apply { put("formatVersion", 1); remove("labels") }
        val notesJson = org.json.JSONObject(String(notesBytes(notes))).also { root -> root.getJSONArray("notes").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).remove("labels") } }
        val bytes = zip("manifest.json" to manifestJson.toString().toByteArray(), "notes.json" to notesJson.toString().toByteArray(), "audio/a.wav" to wavA)
        BackupArchive.open(ByteArrayInputStream(bytes)).use {
            assertEquals(1, it.manifest.formatVersion)
            assertTrue(it.notes.single().labels.isEmpty())
            assertTrue(it.manifest.labels.isEmpty())
            assertEquals(setOf("a.wav"), it.stageAudio(null))
        }
    }

    @Test fun unusableLabelNamesInABackupAreDropped() {
        val notes = listOf(note("a.wav", 1000, null))
        val root = org.json.JSONObject(String(notesBytes(notes)))
        root.getJSONArray("notes").getJSONObject(0).put("labels", org.json.JSONArray(listOf("ok", "bad\"quote", "", "ok", "  spaced   out ")))
        BackupArchive.open(ByteArrayInputStream(zip("manifest.json" to manifestBytes(notes), "notes.json" to root.toString().toByteArray()))).use {
            assertEquals(listOf("ok", "spaced out"), it.notes.single().labels)
        }
    }

    @Test fun checkingWithoutStagingWorks() {
        val notes = listOf(note("a.wav", 1000, wavA))
        BackupArchive.open(ByteArrayInputStream(build(notes, mapOf("a.wav" to wavA)))).use { assertEquals(setOf("a.wav"), it.stageAudio(null)) }
    }

    @Test fun anEmptyBackupIsValid() {
        BackupArchive.open(ByteArrayInputStream(build(emptyList(), emptyMap()))).use {
            assertEquals(0, it.notes.size)
            assertTrue(it.stageAudio(null).isEmpty())
        }
    }

    private fun expectRefused(bytes: ByteArray, contains: String? = null, stage: Boolean = true) {
        try {
            BackupArchive.open(ByteArrayInputStream(bytes)).use { if (stage) it.stageAudio(temp.newFolder()) }
            fail("the backup was accepted")
        } catch (e: IOException) {
            if (contains != null) assertTrue("message was: ${e.message}", e.message!!.contains(contains))
        }
    }

    @Test fun aDamagedRecordingIsDetected() {
        val notes = listOf(note("a.wav", 1000, wavA))
        val declared = notes[0]
        val lying = BackupNote(declared.createdAt, declared.audioFile, declared.language, declared.status, declared.micSource, false, BackupAudio(wavA.size.toLong(), sha(wavB)), declared.transcripts)
        // build a ZIP by hand whose recording does not match the checksum in notes.json
        val bytes = zip("manifest.json" to manifestBytes(listOf(lying)), "notes.json" to notesBytes(listOf(lying)), "audio/a.wav" to wavA)
        expectRefused(bytes, "damaged")
    }

    @Test fun pathTricksInRecordingNamesAreRefused() {
        val notes = listOf(note("a.wav", 1000, wavA))
        for (name in listOf("audio/../evil.wav", "audio/sub/a.wav", "/etc/passwd", "audio/a.wav.exe", "audio/.hidden.wav", "other/a.wav")) {
            expectRefused(zip("manifest.json" to manifestBytes(notes), "notes.json" to notesBytes(notes), name to wavA), "unexpected")
        }
    }

    @Test fun invalidFileNamesInNotesJsonAreRefused() {
        for (name in listOf("../x.wav", "a/b.wav", "a b.wav", "", ".wav.wav", "x".repeat(200) + ".wav")) {
            val bad = note(name, 1, null)
            expectRefused(zip("manifest.json" to manifestBytes(listOf(bad)), "notes.json" to notesBytes(listOf(bad))), "file name", stage = false)
        }
    }

    @Test fun aRecordingNobodyDeclaredIsRefused() {
        val notes = listOf(note("a.wav", 1000, null))
        expectRefused(zip("manifest.json" to manifestBytes(notes), "notes.json" to notesBytes(notes), "audio/a.wav" to wavA), "no note refers")
    }

    @Test fun aMissingRecordingMakesTheBackupIncomplete() {
        val notes = listOf(note("a.wav", 1000, wavA), note("b.wav", 2000, wavB))
        expectRefused(zip("manifest.json" to manifestBytes(notes), "notes.json" to notesBytes(notes), "audio/a.wav" to wavA), "incomplete")
    }

    @Test fun aRecordingLargerThanDeclaredIsRefused() {
        val notes = listOf(note("a.wav", 1000, wavB))
        expectRefused(zip("manifest.json" to manifestBytes(notes), "notes.json" to notesBytes(notes), "audio/a.wav" to wavA), "larger")
    }

    @Test fun aNewerFormatVersionIsRefusedWithAClearMessage() {
        val notes = listOf(note("a.wav", 1000, null))
        val newer = BackupManifest(BackupArchive.FORMAT_VERSION + 1, "9.9.9", 99, 1, 1, 0, emptyList(), settings)
        expectRefused(build(notes, emptyMap(), newer), "newer version")
    }

    @Test fun somethingThatIsNotABackupIsRefused() {
        expectRefused("just some text, not a zip".toByteArray(), "not a Voice Notes backup")
        expectRefused(ByteArray(0), "not a Voice Notes backup")
        expectRefused(zip("readme.txt" to "hello".toByteArray()), "not a Voice Notes backup")
        expectRefused(zip("notes.json" to "{}".toByteArray(), "manifest.json" to "{}".toByteArray()), "not a Voice Notes backup")
    }

    @Test fun anUnknownStatusIsRefused() {
        val notes = listOf(note("a.wav", 1000, null))
        val json = String(notesBytes(notes)).replace("\"DONE\"", "\"EXPLODED\"")
        expectRefused(zip("manifest.json" to manifestBytes(notes), "notes.json" to json.toByteArray()), "unknown value")
    }

    @Test fun theNoteCountMustMatch() {
        val notes = listOf(note("a.wav", 1000, null), note("b.wav", 2000, null))
        val wrong = BackupManifest(BackupArchive.FORMAT_VERSION, "0.1.0", 2, 1, 5, 0, emptyList(), settings)
        expectRefused(zip("manifest.json" to manifestBytes(notes, wrong), "notes.json" to notesBytes(notes)), "note count")
    }

    @Test fun twoNotesCannotShareAFileName() {
        val notes = listOf(note("a.wav", 1000, null), note("a.wav", 2000, null))
        expectRefused(zip("manifest.json" to manifestBytes(notes), "notes.json" to notesBytes(notes)), "twice")
    }

    @Test fun encryptedRoundTrip() {
        val notes = listOf(note("a.wav", 1000, wavA))
        val plain = build(notes, mapOf("a.wav" to wavA))
        val enc = ByteArrayOutputStream()
        BackupCrypto.encrypt(enc, "passphrase 1".toCharArray(), 1_000).use { it.write(plain) }
        BackupArchive.open(BackupCrypto.decrypt(ByteArrayInputStream(enc.toByteArray()), "passphrase 1".toCharArray())).use {
            assertEquals(setOf("a.wav"), it.stageAudio(temp.newFolder()))
        }
    }

    // --- helpers that write archives by hand, to build the bad ones the writer would never produce ---

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((name, data) in entries) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return out.toByteArray()
    }

    /** Uses the writer for the JSON parts so they are valid, then reads them back out of its archive. */
    private fun partsOf(notes: List<BackupNote>, manifest: BackupManifest): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, manifest, notes.map { BackupNote(it.createdAt, it.audioFile, it.language, it.status, it.micSource, it.audioDeleted, null, it.transcripts) }) { error("no audio") }
        val result = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; result[e.name] = z.readBytes() }
        }
        return result
    }

    private fun manifestBytes(notes: List<BackupNote>, manifest: BackupManifest = manifest(notes)) = partsOf(notes, manifest).getValue("manifest.json")

    /** notes.json with the recording details of [notes] kept (the helper above strips them, so put them back by text). */
    private fun notesBytes(notes: List<BackupNote>): ByteArray {
        val root = org.json.JSONObject(String(partsOf(notes, manifest(notes)).getValue("notes.json")))
        val array = root.getJSONArray("notes")
        notes.forEachIndexed { i, n ->
            n.audio?.let { array.getJSONObject(i).put("audio", org.json.JSONObject().put("size", it.size).put("sha256", it.sha256)) }
            array.getJSONObject(i).put("audioFile", n.audioFile) // the invalid-name tests need the raw value
        }
        return root.toString().toByteArray()
    }
}

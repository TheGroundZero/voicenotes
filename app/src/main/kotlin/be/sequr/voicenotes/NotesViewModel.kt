// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = NoteDatabase.get(app).noteDao()

    /** null until the first load, so the UI does not flash an empty state. */
    val notes: StateFlow<List<NoteWithTranscripts>?> = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One-off messages for the UI (snackbar), e.g. the result of appending a note to a file. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _appendTarget = MutableStateFlow(appendTargetName())
    /** Display name of the Markdown file notes are appended to, or null if none is set. */
    val appendTarget: StateFlow<String?> = _appendTarget.asStateFlow()

    /** Re-read after the Export screen changes the target. */
    fun refreshAppendTarget() {
        _appendTarget.value = appendTargetName()
    }

    private fun appendTargetName(): String? =
        Settings.appendTarget(getApplication())?.let { NoteExport.displayName(getApplication(), android.net.Uri.parse(it)) }

    fun append(item: NoteWithTranscripts) {
        val target = Settings.appendTarget(getApplication())
        val entry = NoteExport.entry(item)
        if (target == null || entry == null) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { NoteExport.append(getApplication(), android.net.Uri.parse(target), entry) }
            _events.tryEmit(
                result.fold(
                    onSuccess = { "Added to ${appendTargetName() ?: "the file"}" },
                    onFailure = { "Could not add to the file: ${it.message ?: "unknown error"}" },
                ),
            )
        }
    }

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()
    private var player: MediaPlayer? = null

    fun togglePlayback(note: Note) {
        val wasPlaying = _playingId.value == note.id
        stopPlayback()
        if (wasPlaying) return
        player = try {
            MediaPlayer().apply {
                setDataSource(NoteFiles.audio(getApplication(), note).path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            return // unplayable file: leave the list as is rather than crash
        }
        _playingId.value = note.id
    }

    fun stopPlayback() {
        player?.release()
        player = null
        _playingId.value = null
    }

    fun installedModels(): List<String> = WhisperTranscriber.installedModels(getApplication())

    /** Adds a new version; the current text is kept in the history. [language] is a code or auto. */
    fun retranscribe(note: Note, language: String, model: String?) =
        RecordingService.transcribe(getApplication(), note.id, language, model, force = true)

    /** Run the normal pipeline again, including the check for speech (used after a failure). */
    fun retry(note: Note) = RecordingService.transcribe(getApplication(), note.id)

    fun editTranscript(note: Note, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.addTranscript(
                Transcript(
                    noteId = note.id, text = text.trim(), model = "manual", language = note.language,
                    detected = false, source = TranscriptSource.MANUAL, createdAt = System.currentTimeMillis(), isCurrent = true,
                ),
            )
        }
    }

    fun makeCurrent(transcript: Transcript) {
        viewModelScope.launch(Dispatchers.IO) { dao.makeCurrent(transcript.id) }
    }

    fun deleteTranscript(transcript: Transcript) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteTranscript(transcript.id) }
    }

    /** Deletes only the recording; the note and its text stay. */
    fun removeAudio(note: Note) {
        if (_playingId.value == note.id) stopPlayback()
        viewModelScope.launch(Dispatchers.IO) { AudioCleanup.removeAudio(getApplication(), note) }
    }

    fun delete(note: Note) {
        if (_playingId.value == note.id) stopPlayback()
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(note.id) // transcripts go with it (cascade)
            NoteFiles.audio(getApplication(), note).delete()
        }
    }

    override fun onCleared() = stopPlayback()
}

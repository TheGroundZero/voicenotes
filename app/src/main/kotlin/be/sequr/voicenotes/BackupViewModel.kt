// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a restore is, from picking the file to the final choice. */
sealed interface RestoreStep {
    class AskPassphrase(val uri: Uri, val wrong: Boolean) : RestoreStep
    class Review(val uri: Uri, val passphrase: CharArray?, val preview: BackupPreview) : RestoreStep
}

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val _lastBackup = MutableStateFlow(Settings.lastBackup(app))
    val lastBackup: StateFlow<Settings.LastBackup?> = _lastBackup.asStateFlow()

    /** What the screen is busy with right now (null when idle); the screen blocks input while this is set. */
    private val _working = MutableStateFlow<String?>(null)
    val working: StateFlow<String?> = _working.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _restoreStep = MutableStateFlow<RestoreStep?>(null)
    val restoreStep: StateFlow<RestoreStep?> = _restoreStep.asStateFlow()

    fun backup(uri: Uri, includeAudio: Boolean, passphrase: CharArray?) {
        val app = getApplication<Application>()
        Settings.setBackupEncrypt(app, passphrase != null)
        Settings.setBackupWithAudio(app, includeAudio)
        viewModelScope.launch {
            _working.value = "Starting"
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.create(app, uri, BackupRequest(includeAudio, passphrase)) { _working.value = it } }
            }
            passphrase?.fill('\u0000')
            _working.value = null
            result.fold(
                onSuccess = {
                    _lastBackup.value = it
                    _message.value = "Backed up ${it.notes} notes" + (if (it.withAudio) " with their recordings" else " (text only)") +
                        (if (it.encrypted) ", protected by your passphrase." else ". The file is not encrypted.")
                },
                onFailure = { _message.value = "The backup failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    /** The user picked a file to restore from. */
    fun chooseRestoreFile(uri: Uri) = inspect(uri, null, wrong = false)

    fun submitPassphrase(uri: Uri, passphrase: CharArray) = inspect(uri, passphrase, wrong = true)

    private fun inspect(uri: Uri, passphrase: CharArray?, wrong: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _working.value = "Reading the file"
            val result = withContext(Dispatchers.IO) { runCatching { BackupManager.inspect(app, uri, passphrase) } }
            _working.value = null
            result.fold(
                onSuccess = { _restoreStep.value = RestoreStep.Review(uri, passphrase, it) },
                onFailure = {
                    when (it) {
                        is PassphraseRequired -> _restoreStep.value = RestoreStep.AskPassphrase(uri, wrong = false)
                        is WrongPassphraseException -> _restoreStep.value = RestoreStep.AskPassphrase(uri, wrong = true)
                        else -> { _restoreStep.value = null; _message.value = "This file cannot be restored: ${it.message ?: "unknown error"}" }
                    }
                },
            )
            if (result.isFailure) passphrase?.fill('\u0000')
        }
    }

    fun cancelRestore() {
        (_restoreStep.value as? RestoreStep.Review)?.passphrase?.fill('\u0000')
        _restoreStep.value = null
    }

    fun restore(review: RestoreStep.Review, mode: RestoreMode, withSettings: Boolean) {
        val app = getApplication<Application>()
        _restoreStep.value = null
        viewModelScope.launch {
            _working.value = "Starting"
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.restore(app, review.uri, review.passphrase, mode, withSettings) { _working.value = it } }
            }
            review.passphrase?.fill('\u0000')
            _working.value = null
            result.fold(
                onSuccess = {
                    _message.value = buildString {
                        append("Restored ${it.added} notes")
                        if (it.alreadyPresent > 0) append("; ${it.alreadyPresent} were already here and were left as they are")
                        append(".")
                        if (it.waitingForTranscription > 0) append(" ${it.waitingForTranscription} will be transcribed when the battery allows.")
                    }
                },
                onFailure = { _message.value = "The restore failed and nothing was changed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = NoteDatabase.get(app).noteDao()

    private val _target = MutableStateFlow(targetName())
    val target: StateFlow<String?> = _target.asStateFlow()

    private val _autoAppend = MutableStateFlow(Settings.autoAppend(app))
    val autoAppend: StateFlow<Boolean> = _autoAppend.asStateFlow()

    private val _appendError = MutableStateFlow(Settings.appendError(app))
    val appendError: StateFlow<String?> = _appendError.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun targetName(): String? =
        Settings.appendTarget(getApplication())?.let { NoteExport.displayName(getApplication(), Uri.parse(it)) }

    /** The picker returned a file; keep access to it across restarts. */
    fun setTarget(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            _message.value = "That location does not allow keeping access to the file. Pick another one."
            return
        }
        Settings.setAppendTarget(getApplication(), uri.toString())
        Settings.setAppendError(getApplication(), null)
        _appendError.value = null
        _target.value = targetName()
    }

    fun clearTarget() {
        val app = getApplication<Application>()
        Settings.appendTarget(app)?.let {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        Settings.setAppendTarget(app, null)
        Settings.setAutoAppend(app, false)
        Settings.setAppendError(app, null)
        _target.value = null
        _autoAppend.value = false
        _appendError.value = null
    }

    fun setAutoAppend(enabled: Boolean) {
        Settings.setAutoAppend(getApplication(), enabled && _target.value != null)
        _autoAppend.value = Settings.autoAppend(getApplication())
    }

    fun refreshError() {
        _appendError.value = Settings.appendError(getApplication())
    }

    fun exportAll(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { NoteExport.exportAll(getApplication(), uri, dao.listAll()) }
            _message.value = result.fold(
                onSuccess = { "Exported $it notes" },
                onFailure = { "Export failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

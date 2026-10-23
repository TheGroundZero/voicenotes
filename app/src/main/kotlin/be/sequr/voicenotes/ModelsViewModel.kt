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

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val _installed = MutableStateFlow(ModelStore.installed(app))
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    /** The model transcription will use when none is picked. */
    private val _defaultId = MutableStateFlow(ModelStore.resolve(app)?.id)
    val defaultId: StateFlow<String?> = _defaultId.asStateFlow()

    val downloads = ModelDownloads.states

    private val _allowMobileData = MutableStateFlow(Settings.allowMobileData(app))
    val allowMobileData: StateFlow<Boolean> = _allowMobileData.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    init {
        // A finished or failed download changes what is installed.
        viewModelScope.launch { downloads.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            val (models, default) = withContext(Dispatchers.IO) { ModelStore.installed(getApplication()) to ModelStore.resolve(getApplication())?.id }
            _installed.value = models
            _defaultId.value = default
        }
    }

    fun download(spec: ModelSpec) = ModelDownloadService.start(getApplication(), spec.id)

    fun cancelDownload() = ModelDownloadService.cancel(getApplication())

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ModelStore.delete(getApplication(), id) }
            refresh()
        }
    }

    fun makeDefault(id: String) {
        Settings.setDefaultModel(getApplication(), id)
        refresh()
    }

    fun setAllowMobileData(allow: Boolean) {
        Settings.setAllowMobileData(getApplication(), allow)
        _allowMobileData.value = allow
    }

    fun importModel(uri: Uri) {
        _importing.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ModelStore.importFrom(getApplication(), uri) }
            _importing.value = false
            _message.value = when (result) {
                is ImportResult.Imported ->
                    if (result.verified) "Imported ${result.id} (checksum verified)"
                    else "Imported ${result.id}. This is not a catalog model, so its checksum could not be verified."
                is ImportResult.Failed -> result.message
            }
            refresh()
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

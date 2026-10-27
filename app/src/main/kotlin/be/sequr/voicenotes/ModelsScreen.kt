// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun megabytes(bytes: Long) = "%d MB".format(bytes / 1_000_000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val defaultId by viewModel.defaultId.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val allowMobileData by viewModel.allowMobileData.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val speechCheckInstalled by viewModel.speechCheckInstalled.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<String?>(null) }
    var deletingSpeechCheck by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importModel) }
    val busy = downloads.values.any { it !is DownloadState.Failed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Speech recognition runs entirely on this phone. Models, and a small speech-check model that skips notes without " +
                        "speech, are downloaded from huggingface.co only when you tap Download. Your recordings and transcripts are " +
                        "never uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val catalogIds = ModelCatalog.models.map { it.id }
            items(ModelCatalog.models.size) { index ->
                val spec = ModelCatalog.models[index]
                CatalogCard(
                    spec = spec,
                    installed = installed.firstOrNull { it.id == spec.id },
                    isDefault = defaultId == spec.id,
                    state = downloads[spec.id],
                    anotherBusy = busy && downloads[spec.id] == null,
                    onDownload = { viewModel.download(spec) },
                    onCancel = viewModel::cancelDownload,
                    onMakeDefault = { viewModel.makeDefault(spec.id) },
                    onDelete = { deleting = spec.id },
                )
            }
            item {
                val check = ModelCatalog.speechCheck
                CatalogCard(
                    spec = check,
                    installed = if (speechCheckInstalled) InstalledModel(check.id, check.sizeBytes, check) else null,
                    isDefault = false,
                    defaultable = false,
                    state = downloads[check.id],
                    anotherBusy = busy && downloads[check.id] == null,
                    onDownload = { viewModel.download(check) },
                    onCancel = viewModel::cancelDownload,
                    onMakeDefault = {},
                    onDelete = { deletingSpeechCheck = true },
                )
            }
            val custom = installed.filter { it.id !in catalogIds }
            if (custom.isNotEmpty()) {
                item { Text("Imported models", style = MaterialTheme.typography.titleSmall) }
                items(custom.size) { index ->
                    val model = custom[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(model.id, style = MaterialTheme.typography.titleMedium)
                            Text("${megabytes(model.sizeBytes)} · not verified", style = MaterialTheme.typography.bodySmall)
                            Row {
                                if (defaultId == model.id) Text("Default", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                                else TextButton(onClick = { viewModel.makeDefault(model.id) }) { Text("Make default") }
                                TextButton(onClick = { deleting = model.id }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (importing) "Importing…" else "Import a model from a file…")
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Download over mobile data", style = MaterialTheme.typography.bodyLarge)
                        Text("Models are large (0.5-0.9 GB). Off means Wi-Fi only.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = allowMobileData, onCheckedChange = viewModel::setAllowMobileData)
                }
            }
        }
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $id?") },
            text = {
                Text(
                    if (installed.size <= 1) "This is your only model. Without one, new notes cannot be transcribed until you download or import another."
                    else "You can download it again later."
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.delete(id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    if (deletingSpeechCheck) {
        AlertDialog(
            onDismissRequest = { deletingSpeechCheck = false },
            title = { Text("Delete the speech check?") },
            text = { Text("Without it, notes with no speech are transcribed too and can get invented text. You can download it again later.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteSpeechCheck(); deletingSpeechCheck = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingSpeechCheck = false }) { Text("Cancel") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun CatalogCard(
    spec: ModelSpec,
    installed: InstalledModel?,
    isDefault: Boolean,
    defaultable: Boolean = true,
    state: DownloadState?,
    anotherBusy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onMakeDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(spec.title, style = MaterialTheme.typography.titleMedium)
                Text(megabytes(spec.sizeBytes), style = MaterialTheme.typography.labelMedium)
            }
            Text(spec.description, style = MaterialTheme.typography.bodyMedium)
            when {
                state is DownloadState.Downloading -> {
                    LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("${megabytes(state.done)} of ${megabytes(state.total)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                state is DownloadState.Verifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Verifying checksum…", style = MaterialTheme.typography.bodySmall)
                }
                installed != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!defaultable) Text("✓ Installed", modifier = Modifier.padding(horizontal = 12.dp))
                    else if (isDefault) Text("✓ Installed · default", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                    else {
                        Text("✓ Installed", modifier = Modifier.padding(horizontal = 12.dp))
                        TextButton(onClick = onMakeDefault) { Text("Make default") }
                    }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                else -> {
                    if (state is DownloadState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onDownload, enabled = !anotherBusy) { Text(if (state is DownloadState.Failed) "Retry" else "Download") }
                        if (state is DownloadState.Failed && state.openSettings) {
                            val context = LocalContext.current
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            }) { Text("Open app settings") }
                        }
                    }
                }
            }
        }
    }
}

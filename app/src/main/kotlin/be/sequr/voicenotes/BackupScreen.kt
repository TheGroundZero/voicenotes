// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

private const val MIN_PASSPHRASE = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val last by viewModel.lastBackup.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val restoreStep by viewModel.restoreStep.collectAsStateWithLifecycle()

    var includeAudio by remember { mutableStateOf(Settings.backupWithAudio(context)) }
    var encrypt by remember { mutableStateOf(Settings.backupEncrypt(context)) }
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val passphraseOk = !encrypt || (passphrase.length >= MIN_PASSPHRASE && passphrase == repeat)

    fun startBackup(uri: android.net.Uri) {
        viewModel.backup(uri, includeAudio, if (encrypt) passphrase.toCharArray() else null)
        passphrase = ""
        repeat = ""
    }

    val createEncrypted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(::startBackup) }
    val createPlain = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(::startBackup) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::chooseRestoreFile) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
                navigationIcon = { TextButton(onClick = onBack, enabled = working == null) { Text("‹ Back") } },
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
                    "A backup is one file with your notes, every version of their text and, if you want, the recordings. You choose where it " +
                        "goes: the app that provides that location (for example Nextcloud) does any uploading. Voice Notes itself never " +
                        "sends anything over the network, and Android's own cloud backup is switched off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Back up", style = MaterialTheme.typography.titleMedium)
                        last?.let {
                            Text(
                                "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.time))}, " +
                                    "${it.notes} notes, ${if (it.withAudio) "with recordings" else "text only"}, ${if (it.encrypted) "encrypted" else "not encrypted"}.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } ?: Text("No backup made yet.", style = MaterialTheme.typography.bodySmall)
                        SwitchRow("Include the recordings", "Recordings are about 2 MB per minute. Without them the backup holds only the text.", includeAudio) { includeAudio = it }
                        SwitchRow("Protect with a passphrase", "Encrypts the whole file. Anyone who can read an unprotected backup can hear every recording.", encrypt) { encrypt = it }
                        if (encrypt) {
                            OutlinedTextField(
                                value = passphrase, onValueChange = { passphrase = it }, label = { Text("Passphrase (at least $MIN_PASSPHRASE characters)") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = repeat, onValueChange = { repeat = it }, label = { Text("Passphrase again") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                                isError = repeat.isNotEmpty() && repeat != passphrase,
                            )
                            Text(
                                "There is no way to recover a lost passphrase: without it the backup cannot be opened, by anyone.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            )
                        }
                        OutlinedButton(
                            enabled = passphraseOk && working == null,
                            onClick = {
                                val name = "voicenotes-${LocalDate.now()}"
                                if (encrypt) createEncrypted.launch("$name.vnbk") else createPlain.launch("$name.zip")
                            },
                        ) { Text("Back up…") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Restore", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pick a backup file. You see what it holds before anything changes. Notes you already have are left alone, " +
                                "and a failed restore changes nothing. Speech models are not part of a backup; download them again under Models.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(enabled = working == null, onClick = { pickBackup.launch(arrayOf("*/*")) }) { Text("Restore from a backup…") }
                    }
                }
            }
        }
    }

    working?.let {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Please wait") },
            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(); Text(it) } },
            confirmButton = {},
        )
    }

    when (val step = restoreStep) {
        is RestoreStep.AskPassphrase -> PassphraseDialog(step, onSubmit = { viewModel.submitPassphrase(step.uri, it) }, onCancel = viewModel::cancelRestore)
        is RestoreStep.Review -> ReviewDialog(step, onRestore = viewModel::restore, onCancel = viewModel::cancelRestore)
        null -> Unit
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
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PassphraseDialog(step: RestoreStep.AskPassphrase, onSubmit: (CharArray) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This backup is protected. Enter the passphrase that was used to make it.")
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = step.wrong, modifier = Modifier.fillMaxWidth(),
                )
                if (step.wrong) Text("That did not open the backup. Check the passphrase, or the file may be damaged.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = text.isNotEmpty(), onClick = { onSubmit(text.toCharArray()) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ReviewDialog(step: RestoreStep.Review, onRestore: (RestoreStep.Review, RestoreMode, Boolean) -> Unit, onCancel: () -> Unit) {
    val p = step.preview
    var mode by remember { mutableStateOf(RestoreMode.MERGE) }
    var withSettings by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore this backup?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Made ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.manifest.createdAt))} " +
                        "with Voice Notes ${p.manifest.appVersion}. ${p.manifest.noteCount} notes, " +
                        (if (p.manifest.includesAudio) "with recordings" else "text only") + (if (p.encrypted) ", encrypted." else "."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (mode == RestoreMode.MERGE) "${p.newNotes} notes will be added; ${p.alreadyPresent} are already here and stay as they are."
                    else "All ${p.manifest.noteCount} notes in the backup replace everything you have now.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (p.missingModels.isNotEmpty()) {
                    Text("Transcribed with ${p.missingModels.joinToString()}, which is not installed here. You can still read everything; download it under Models to re-transcribe.", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == RestoreMode.MERGE, onClick = { mode = RestoreMode.MERGE })
                    Text("Add to my notes (recommended)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == RestoreMode.REPLACE, onClick = { mode = RestoreMode.REPLACE })
                    Text("Replace everything")
                }
                if (mode == RestoreMode.REPLACE) {
                    Text(
                        "This removes all notes and recordings on this phone, but only after the backup has been read and checked. " +
                            "Make a backup of the current notes before you do this.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withSettings, onCheckedChange = { withSettings = it })
                    Text("Also restore the language settings")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(step, mode, withSettings) }) { Text(if (mode == RestoreMode.REPLACE) "Replace everything" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Like the stock contracts, but ask for write access that survives restarts (needed to append later). */
private class OpenWritableDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

private class CreateWritableDocument(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(viewModel: ExportViewModel, onBack: () -> Unit, onTargetChanged: () -> Unit) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    val autoAppend by viewModel.autoAppend.collectAsStateWithLifecycle()
    val appendError by viewModel.appendError.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // An automatic append may have failed while this screen was closed.
    LaunchedEffect(Unit) { viewModel.refreshError() }

    val pickExisting = rememberLauncherForActivityResult(OpenWritableDocument()) { uri -> uri?.let { viewModel.setTarget(it); onTargetChanged() } }
    val createNew = rememberLauncherForActivityResult(CreateWritableDocument("text/markdown")) { uri -> uri?.let { viewModel.setTarget(it); onTargetChanged() } }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let(viewModel::exportAll) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export & sharing") },
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
                    "Notes only leave this phone when you choose: through the share sheet, or into a file you pick here. " +
                        "Nothing is sent automatically, and the app has no account or server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Markdown file", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Notes can be added to the end of one Markdown file, for example in an Obsidian vault or a Nextcloud folder. " +
                                "Each note becomes a section with its date, language and text. Audio is not included.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (target != null) "Target: $target" else "No file chosen",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (target != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickExisting.launch(arrayOf("*/*")) }) { Text("Choose file…") }
                            OutlinedButton(onClick = { createNew.launch("notes.md") }) { Text("New file…") }
                        }
                        if (target != null) {
                            TextButton(onClick = { viewModel.clearTarget(); onTargetChanged() }) { Text("Stop using this file") }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("Add new notes automatically", style = MaterialTheme.typography.bodyLarge)
                                    Text("Right after a note has been transcribed.", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = autoAppend, onCheckedChange = viewModel::setAutoAppend)
                            }
                            appendError?.let {
                                Text("The last automatic add failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Export everything", style = MaterialTheme.typography.titleMedium)
                        Text("Saves all notes that have text, oldest first, as one Markdown file.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { exportAll.launch("voicenotes-export.md") }) { Text("Export all notes…") }
                    }
                }
            }
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
}

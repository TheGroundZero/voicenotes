// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Create, rename and delete labels, and jump to the notes that carry one. Putting labels on notes happens on the note itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(
    labels: List<LabelCount>,
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onShow: (String) -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<LabelCount?>(null) }
    var deleting by remember { mutableStateOf<LabelCount?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Labels") },
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
                    "Labels sort your notes. Put them on a note with its ⋯ menu, then find the notes with the search bar: " +
                        "label:name finds the notes with that label, and never notes that merely say the word.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it }, singleLine = true,
                            label = { Text("New label") }, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName); newName = "" }) { Text("Add label") }
                    }
                }
            }
            if (labels.isEmpty()) {
                item { Text("No labels yet.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(labels, key = { it.id }) { label ->
                Card(Modifier.fillMaxWidth().clickable { onShow(label.name) }) {
                    Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (label.notes == 1) "1 note" else "${label.notes} notes",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { renaming = label }) { Text("Rename") }
                        TextButton(onClick = { deleting = label }) { Text("Delete") }
                    }
                }
            }
        }
    }

    renaming?.let { label ->
        var text by remember(label.id) { mutableStateOf(label.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename label") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onRename(label.id, text); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { label ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${label.name}”?") },
            text = { Text("It is taken off ${if (label.notes == 1) "its note" else "its ${label.notes} notes"}. The notes themselves stay.") },
            confirmButton = { TextButton(onClick = { onDelete(label.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

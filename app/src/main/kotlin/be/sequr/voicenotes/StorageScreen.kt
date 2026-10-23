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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val choices = listOf(0 to "Never", 7 to "1 week after transcription", 30 to "1 month after transcription", 90 to "3 months after transcription", 365 to "1 year after transcription")

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)

private fun recordings(n: Int) = if (n == 1) "1 recording" else "$n recordings"
private fun notes(n: Int) = if (n == 1) "1 note" else "$n notes"

/** How much room recordings take, and the optional automatic removal of old ones. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(Settings.autoRemovalDays(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf<Pair<AudioCleanup.Preview, Int>?>(null) }
    var pending by remember { mutableStateOf<Pair<Int, AudioCleanup.Preview>?>(null) } // a choice waiting for the user's confirmation

    LaunchedEffect(refresh) { usage = withContext(Dispatchers.IO) { AudioCleanup.usage(context) } }

    fun apply(choice: Int) {
        Settings.setAutoRemovalDays(context, choice)
        days = choice
        AudioCleanup.schedule(context)
        scope.launch {
            withContext(Dispatchers.IO) { AudioCleanup.run(context) }
            refresh++
        }
    }

    fun choose(choice: Int) {
        if (choice == days) return
        if (choice == 0) return apply(0)
        scope.launch {
            val due = withContext(Dispatchers.IO) { AudioCleanup.preview(context, choice) }
            if (due.notes == 0) apply(choice) else pending = choice to due // the first run needs a yes from the user
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
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
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Recordings", style = MaterialTheme.typography.titleMedium)
                        val u = usage
                        Text(
                            if (u == null) "Counting…" else "${recordings(u.first.notes)} ${if (u.first.notes == 1) "takes" else "take"} ${megabytes(u.first.bytes)}." +
                                if (u.second > 0) " The recording has been removed from ${notes(u.second)}; the text is kept." else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Speech models are stored separately (Models screen).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Remove old recordings automatically", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Frees space by deleting a note's recording some time after it was transcribed. The text and all its " +
                                "versions stay. Without the recording you can no longer play the note, share its audio or transcribe " +
                                "it again with another model. Notes that failed, are waiting or had no speech keep their recording. " +
                                "It is off unless you choose a time here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        choices.forEach { (value, label) ->
                            Row(Modifier.fillMaxWidth().clickable { choose(value) }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = days == value, onClick = { choose(value) })
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { (choice, due) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Remove ${recordings(due.notes)} now?") },
            text = {
                Text(
                    "${notes(due.notes)} ${if (due.notes == 1) "was" else "were"} transcribed more than $choice days ago. " +
                        "${if (due.notes == 1) "Its recording" else "Their recordings"} (${megabytes(due.bytes)}) will be deleted now, and later " +
                        "recordings the same way once they are old enough. The text of every note stays. This cannot be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { pending = null; apply(choice) }) { Text("Remove them") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

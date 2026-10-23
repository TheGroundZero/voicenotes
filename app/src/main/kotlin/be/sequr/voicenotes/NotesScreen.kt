// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import java.text.DateFormat
import java.util.Date

class NoteActions(
    val play: (Note) -> Unit,
    val retranscribe: (Note, language: String, model: String?) -> Unit,
    val retry: (Note) -> Unit,
    val edit: (Note, text: String) -> Unit,
    val makeCurrent: (Transcript) -> Unit,
    val deleteTranscript: (Transcript) -> Unit,
    val delete: (Note) -> Unit,
    val shareText: (NoteWithTranscripts) -> Unit,
    val shareAudio: (Note) -> Unit,
    val append: (NoteWithTranscripts) -> Unit,
    val removeAudio: (Note) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesTopBar(onOpenModels: () -> Unit, onOpenExport: () -> Unit, onOpenTriggers: () -> Unit, onOpenStorage: () -> Unit, onOpenPrivacy: () -> Unit, onOpenAbout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Triggers") }, onClick = { menu = false; onOpenTriggers() })
                    DropdownMenuItem(text = { Text("Export & sharing") }, onClick = { menu = false; onOpenExport() })
                    DropdownMenuItem(text = { Text("Models") }, onClick = { menu = false; onOpenModels() })
                    DropdownMenuItem(text = { Text("Storage") }, onClick = { menu = false; onOpenStorage() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Privacy & permissions") }, onClick = { menu = false; onOpenPrivacy() })
                    DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; onOpenAbout() })
                }
            }
        },
    )
}

@Composable
fun NotesScreen(
    notes: List<NoteWithTranscripts>?,
    playingId: Long?,
    recording: Boolean,
    recordLanguage: String,
    preferred: List<String>,
    installedModels: List<String>,
    onRecordLanguage: (String) -> Unit,
    onPreferred: (List<String>) -> Unit,
    onToggleRecording: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenTriggers: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenStorage: () -> Unit,
    appendTarget: String?,
    batteryHold: String?,
    events: Flow<String>,
    actions: NoteActions,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(events) { events.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = { NotesTopBar(onOpenModels, onOpenExport, onOpenTriggers, onOpenStorage, onOpenPrivacy, onOpenAbout) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { RecordControl(recording, recordLanguage, preferred, onRecordLanguage, onPreferred, onToggleRecording) },
    ) { padding ->
        when {
            notes == null -> Unit
            notes.isEmpty() -> Text(
                "No notes yet. Tap the record button, speak, and tap it again. You can also add the Note tile to Quick Settings.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            else -> {
                val listState = rememberLazyListState()
                // A new note is inserted above the first visible one; show it unless the user is reading further down.
                LaunchedEffect(notes.first().note.id) {
                    if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = padding.calculateTopPadding() + 16.dp,
                        bottom = padding.calculateBottomPadding() + 88.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(notes, key = { it.note.id }) { item ->
                        NoteCard(item, playing = playingId == item.note.id, preferred = preferred, models = installedModels, appendTarget = appendTarget, batteryHold = batteryHold, actions = actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    item: NoteWithTranscripts,
    playing: Boolean,
    preferred: List<String>,
    models: List<String>,
    appendTarget: String?,
    batteryHold: String?,
    actions: NoteActions,
) {
    val note = item.note
    val current = item.current
    // Transcripts are collapsed by default; this state survives rotation and scrolling.
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var showHistory by rememberSaveable(note.id) { mutableStateOf(false) }
    var menu by rememberSaveable(note.id) { mutableStateOf(false) }
    var dialog by rememberSaveable(note.id) { mutableStateOf(NoteDialog.NONE) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.createdAt)),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    MicBadge(note.micSource)
                }
                Text(languageLabel(note, current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }

            when (note.status) {
                NoteStatus.RECORDING -> Status("Recording…")
                NoteStatus.QUEUED -> Status("Waiting to transcribe…")
                NoteStatus.TRANSCRIBING -> Status("Transcribing…")
                NoteStatus.FAILED -> Text("Transcription failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                NoteStatus.WAITING -> Text(
                    if (batteryHold != null) "Waiting to transcribe: $batteryHold" else "Waiting to transcribe…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NoteStatus.NO_SPEECH -> Text("No speech detected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NoteStatus.DONE -> Unit
            }

            current?.text?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (note.status != NoteStatus.RECORDING) {
                    if (note.audioDeleted) {
                        Text("Recording removed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    } else {
                        TextButton(onClick = { actions.play(note) }) { Text(if (playing) "■ Stop" else "▶ Play") }
                    }
                }
                if (note.status == NoteStatus.FAILED) {
                    TextButton(onClick = { actions.retry(note) }) { Text("Retry") }
                }
                if (note.status == NoteStatus.WAITING) {
                    TextButton(onClick = { actions.retry(note) }) { Text("Transcribe now") }
                }
                if (note.status == NoteStatus.NO_SPEECH) {
                    // Overrides the speech check: the user knows better than the detector.
                    TextButton(onClick = { actions.retranscribe(note, note.language, null) }) { Text("Transcribe anyway") }
                }
                if (!current?.text.isNullOrBlank()) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "▴ Collapse" else "▾ Expand") }
                }
                if (note.status != NoteStatus.RECORDING) {
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋯") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (current != null) {
                                DropdownMenuItem(text = { Text("Edit text") }, onClick = { menu = false; dialog = NoteDialog.EDIT })
                            }
                            if (!note.audioDeleted) {
                                DropdownMenuItem(text = { Text("Re-transcribe…") }, onClick = { menu = false; dialog = NoteDialog.RETRANSCRIBE })
                            }
                            if (item.history.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(if (showHistory) "Hide history" else "History (${item.history.size})") },
                                    onClick = { menu = false; showHistory = !showHistory },
                                )
                            }
                            HorizontalDivider()
                            if (current != null) {
                                DropdownMenuItem(text = { Text("Share text…") }, onClick = { menu = false; actions.shareText(item) })
                                if (appendTarget != null) {
                                    DropdownMenuItem(text = { Text("Add to $appendTarget") }, onClick = { menu = false; actions.append(item) })
                                }
                            }
                            if (!note.audioDeleted) {
                                DropdownMenuItem(text = { Text("Share audio…") }, onClick = { menu = false; actions.shareAudio(note) })
                            }
                            HorizontalDivider()
                            if (!note.audioDeleted && note.status == NoteStatus.DONE) {
                                DropdownMenuItem(text = { Text("Remove recording…") }, onClick = { menu = false; dialog = NoteDialog.REMOVE_AUDIO })
                            }
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; dialog = NoteDialog.DELETE })
                        }
                    }
                }
            }

            if (showHistory) {
                item.history.forEach { version -> HistoryRow(version, actions) }
            }
        }
    }

    when (dialog) {
        NoteDialog.NONE -> Unit
        NoteDialog.EDIT -> EditDialog(
            initial = current?.text.orEmpty(),
            onDismiss = { dialog = NoteDialog.NONE },
            onSave = { dialog = NoteDialog.NONE; actions.edit(note, it) },
        )
        NoteDialog.RETRANSCRIBE -> RetranscribeDialog(
            languages = (listOf(Transcriber.AUTO) + preferred + listOfNotNull(note.language.takeIf { it != Transcriber.AUTO && it !in preferred })),
            models = models,
            onDismiss = { dialog = NoteDialog.NONE },
            onStart = { language, model -> dialog = NoteDialog.NONE; expanded = true; actions.retranscribe(note, language, model) },
        )
        NoteDialog.REMOVE_AUDIO -> AlertDialog(
            onDismissRequest = { dialog = NoteDialog.NONE },
            title = { Text("Remove the recording?") },
            text = { Text("The text stays. Without the recording you can no longer play this note, share its audio or transcribe it again. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { dialog = NoteDialog.NONE; actions.removeAudio(note) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { dialog = NoteDialog.NONE }) { Text("Cancel") } },
        )
        NoteDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = NoteDialog.NONE },
            title = { Text("Delete this note?") },
            text = { Text("The recording and all its transcript versions will be removed from this device.") },
            confirmButton = { TextButton(onClick = { dialog = NoteDialog.NONE; actions.delete(note) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { dialog = NoteDialog.NONE }) { Text("Cancel") } },
        )
    }
}

private enum class NoteDialog { NONE, EDIT, RETRANSCRIBE, REMOVE_AUDIO, DELETE }

/** A small icon for the microphone that recorded the note, plus the device's name for Bluetooth and car. Nothing for old notes. */
@Composable
private fun MicBadge(source: String) {
    val (icon, description, name) = when {
        source == MIC_PHONE -> Triple(R.drawable.ic_source_phone, "Recorded with the phone's microphone", null)
        source.startsWith(MIC_CAR_PREFIX) -> Triple(R.drawable.ic_source_car, "Recorded with a car's microphone", source.removePrefix(MIC_CAR_PREFIX))
        source.startsWith(MIC_BLUETOOTH_PREFIX) -> Triple(R.drawable.ic_source_bluetooth, "Recorded with a Bluetooth microphone", source.removePrefix(MIC_BLUETOOTH_PREFIX))
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painterResource(icon),
            contentDescription = if (name.isNullOrBlank()) description else "$description: $name",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!name.isNullOrBlank()) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "NL · auto" when the language was detected, "NL" when chosen, "Auto" before it is known. */
private fun languageLabel(note: Note, current: Transcript?): String {
    val code = current?.language ?: note.language
    if (code == Transcriber.AUTO) return "Auto"
    return code.uppercase() + if (current?.detected == true) " · auto" else ""
}

@Composable
private fun HistoryRow(version: Transcript, actions: NoteActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider()
        val source = if (version.source == TranscriptSource.MANUAL) "edited" else version.model
        Text(
            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(version.createdAt))} · $source · ${version.language.uppercase()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(version.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row {
            TextButton(onClick = { actions.makeCurrent(version) }) { Text("Use this version") }
            TextButton(onClick = { actions.deleteTranscript(version) }) { Text("Delete") }
        }
    }
}

@Composable
private fun EditDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit text") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                Text("Saved as a new version; the current one stays in the history.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank() && text.trim() != initial.trim(), onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RetranscribeDialog(
    languages: List<String>,
    models: List<String>,
    onDismiss: () -> Unit,
    onStart: (language: String, model: String?) -> Unit,
) {
    var language by rememberSaveable { mutableStateOf(Transcriber.AUTO) }
    var model by rememberSaveable { mutableStateOf(models.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-transcribe") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Language", style = MaterialTheme.typography.titleSmall)
                languages.forEach { Choice(Settings.languageName(it), language == it) { language = it } }
                Text("Model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                if (models.isEmpty()) Text("No model installed", color = MaterialTheme.colorScheme.error)
                models.forEach { Choice(it, model == it) { model = it } }
                Text(
                    "The result is added as a new version; the current text is kept in the history.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = models.isNotEmpty(), onClick = { onStart(language, model) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun Status(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** One pill with the language chip (hidden while recording) and the record button. */
@Composable
private fun RecordControl(
    recording: Boolean,
    language: String,
    preferred: List<String>,
    onLanguage: (String) -> Unit,
    onPreferred: (List<String>) -> Unit,
    onToggle: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var editPreferred by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!recording) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("${if (language == Transcriber.AUTO) "AUTO" else language.uppercase()} ▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        (listOf(Transcriber.AUTO) + preferred).forEach { code ->
                            DropdownMenuItem(text = { Text(Settings.languageName(code)) }, onClick = { menu = false; onLanguage(code) })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Preferred languages…") }, onClick = { menu = false; editPreferred = true })
                    }
                }
            }
            IconButton(
                onClick = onToggle,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (recording) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                    contentColor = if (recording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) { Text(if (recording) "■" else "●", style = MaterialTheme.typography.titleMedium) }
        }
    }
    if (editPreferred) {
        PreferredLanguagesDialog(preferred, onDismiss = { editPreferred = false }, onSave = { editPreferred = false; onPreferred(it) })
    }
}

/** Auto-detection only chooses among these. The language ticked first is the fallback when detection is unsure. */
@Composable
private fun PreferredLanguagesDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Preferred languages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Auto-detection only chooses among these. The first one is used when detection is unsure.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Settings.supportedLanguages.forEach { code ->
                    val checked = code in selected
                    val toggle = { selected = if (checked) (selected - code).ifEmpty { selected } else selected + code }
                    Row(Modifier.fillMaxWidth().clickable { toggle() }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        Text(Settings.languageName(code) + if (code == selected.first()) "  ·  fallback" else "")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

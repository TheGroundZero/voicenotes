// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.Manifest
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Screen { ONBOARDING, NOTES, MODELS, EXPORT, BACKUP, LABELS, TRIGGERS, PRIVACY, ABOUT, STORAGE }

class MainActivity : ComponentActivity() {
    private val viewModel: NotesViewModel by viewModels()
    private val modelsViewModel: ModelsViewModel by viewModels()
    private val exportViewModel: ExportViewModel by viewModels()
    private val backupViewModel: BackupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VoiceNotesMediaService.restoreIfEnabled(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            MaterialTheme(
                // minSdk is 34, so the wallpaper-based colours are always available.
                colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
            ) {
                val notes by viewModel.visibleNotes.collectAsStateWithLifecycle()
                val query by viewModel.query.collectAsStateWithLifecycle()
                val oldestFirst by viewModel.oldestFirst.collectAsStateWithLifecycle()
                val labels by viewModel.labels.collectAsStateWithLifecycle()
                val playingId by viewModel.playingId.collectAsStateWithLifecycle()
                val recording by RecordingService.recording.collectAsStateWithLifecycle()
                var preferred by remember { mutableStateOf(Settings.preferredLanguages(context)) }
                var recordLanguage by remember { mutableStateOf(Settings.recordLanguage(context)) }
                val actions = remember {
                    NoteActions(
                        play = viewModel::togglePlayback,
                        retranscribe = viewModel::retranscribe,
                        retry = viewModel::retry,
                        edit = viewModel::editTranscript,
                        makeCurrent = viewModel::makeCurrent,
                        deleteTranscript = viewModel::deleteTranscript,
                        delete = viewModel::delete,
                        shareText = { NoteExport.shareText(this@MainActivity, it) },
                        shareAudio = { NoteExport.shareAudio(this@MainActivity, it) },
                        append = viewModel::append,
                        removeAudio = viewModel::removeAudio,
                        addNewLabel = viewModel::addNewLabel,
                        setLabel = viewModel::setLabelOnNote,
                        filterByLabel = { viewModel.setQuery(SearchQuery.withLabel(viewModel.query.value, it)) },
                    )
                }
                // Permissions are explained first and asked for in context; only a fresh install without the microphone sees this.
                var screen by rememberSaveable {
                    mutableStateOf(
                        if (!Settings.onboarded(context) && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) Screen.ONBOARDING
                        else Screen.NOTES,
                    )
                }
                BackHandler(enabled = screen != Screen.NOTES && screen != Screen.ONBOARDING) { screen = Screen.NOTES }
                when (screen) {
                    Screen.MODELS -> {
                        ModelsScreen(modelsViewModel, onBack = { screen = Screen.NOTES })
                        return@MaterialTheme
                    }
                    Screen.EXPORT -> {
                        ExportScreen(exportViewModel, onBack = { screen = Screen.NOTES }, onTargetChanged = viewModel::refreshAppendTarget)
                        return@MaterialTheme
                    }
                    Screen.BACKUP -> {
                        BackupScreen(backupViewModel, onBack = { screen = Screen.NOTES })
                        return@MaterialTheme
                    }
                    Screen.LABELS -> {
                        LabelsScreen(
                            labels = labels,
                            events = viewModel.events,
                            onBack = { screen = Screen.NOTES },
                            onCreate = viewModel::createLabel,
                            onRename = viewModel::renameLabel,
                            onDelete = viewModel::deleteLabel,
                            onShow = { viewModel.setQuery(SearchQuery.term(it)); screen = Screen.NOTES },
                        )
                        return@MaterialTheme
                    }
                    Screen.TRIGGERS -> {
                        TriggersScreen(onBack = { screen = Screen.NOTES })
                        return@MaterialTheme
                    }
                    Screen.PRIVACY -> {
                        PrivacyScreen(onBack = { screen = Screen.NOTES })
                        return@MaterialTheme
                    }
                    Screen.STORAGE -> {
                        StorageScreen(onBack = { screen = Screen.NOTES })
                        return@MaterialTheme
                    }
                    Screen.ABOUT -> {
                        AboutScreen(onBack = { screen = Screen.NOTES })
                        return@MaterialTheme
                    }
                    Screen.ONBOARDING -> {
                        OnboardingScreen(onDone = { screen = Screen.NOTES })
                        return@MaterialTheme
                    }
                    Screen.NOTES -> Unit
                }
                val appendTarget by viewModel.appendTarget.collectAsStateWithLifecycle()
                var batteryHold by remember { mutableStateOf(BatteryPolicy.state(context).holdReason) }
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) { batteryHold = BatteryPolicy.state(c).holdReason }
                    }
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_BATTERY_CHANGED)
                        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    }
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    onDispose { context.unregisterReceiver(receiver) }
                }
                NotesScreen(
                    notes = notes,
                    query = query,
                    labels = labels,
                    onQuery = viewModel::setQuery,
                    onOpenLabels = { screen = Screen.LABELS },
                    oldestFirst = oldestFirst,
                    onOldestFirst = viewModel::setOldestFirst,
                    playingId = playingId,
                    recording = recording,
                    recordLanguage = recordLanguage,
                    preferred = preferred,
                    installedModels = viewModel.installedModels(),
                    onRecordLanguage = { recordLanguage = it; Settings.setRecordLanguage(context, it) },
                    onPreferred = {
                        Settings.setPreferredLanguages(context, it)
                        preferred = it
                        // The pill's choice must stay one of the preferred languages (or Auto).
                        recordLanguage = Settings.recordLanguage(context)
                    },
                    onToggleRecording = { toggleRecording(recordLanguage) },
                    onOpenModels = { screen = Screen.MODELS },
                    onOpenExport = { screen = Screen.EXPORT },
                    onOpenBackup = { screen = Screen.BACKUP },
                    onOpenTriggers = { screen = Screen.TRIGGERS },
                    onOpenPrivacy = { screen = Screen.PRIVACY },
                    onOpenAbout = { screen = Screen.ABOUT },
                    onOpenStorage = { screen = Screen.STORAGE },
                    appendTarget = appendTarget,
                    batteryHold = batteryHold,
                    events = viewModel.events,
                    actions = actions,
                )
            }
        }
    }

    private fun toggleRecording(language: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 0)
            return
        }
        viewModel.stopPlayback() // playback would otherwise be captured by the microphone
        RecordingService.toggle(this, language)
    }

    override fun onResume() {
        super.onResume()
        RecordWidget.update(this) // the microphone permission may have just been granted
    }

    override fun onStop() {
        viewModel.stopPlayback()
        super.onStop()
    }
}

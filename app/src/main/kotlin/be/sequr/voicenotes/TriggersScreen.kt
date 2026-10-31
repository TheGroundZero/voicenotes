// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Ways to start a note without opening the app. All of them do the same thing: toggle the recording. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    var headset by remember { mutableStateOf(Settings.headsetButton(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var bluetoothMic by remember { mutableStateOf(Settings.bluetoothMic(context)) }
    var remembered by remember { mutableStateOf(Settings.bluetoothMemory(context)) }
    var bluetoothDevices by remember { mutableStateOf(BluetoothMic.available(context).map { it.productName.toString() }) }
    var hasBluetoothPermission by remember { mutableStateOf(BluetoothMic.hasPermission(context)) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasBluetoothPermission = granted
        bluetoothMic = granted
        Settings.setBluetoothMic(context, granted)
        bluetoothDevices = BluetoothMic.available(context).map { it.productName.toString() }
        if (!granted) message = "Without the Nearby devices permission the app cannot see Bluetooth microphones."
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Triggers") },
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
                    "Start and stop a note without opening the app. Every trigger detects the language automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                TriggerCard("Quick Settings tile", "A tile in the pull-down panel. Tap once to start, again to stop.") {
                    OutlinedButton(onClick = {
                        val manager = context.getSystemService(StatusBarManager::class.java)
                        manager.requestAddTileService(
                            ComponentName(context, NoteTileService::class.java),
                            "Note",
                            Icon.createWithResource(context, R.drawable.ic_mic),
                            context.mainExecutor,
                        ) { result ->
                            message = when (result) {
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings"
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                                else -> "Could not add the tile. Add it by editing Quick Settings."
                            }
                        }
                    }) { Text("Add to Quick Settings") }
                }
            }
            item {
                TriggerCard("Home-screen widget", "A button for your home screen that starts and stops a note.") {
                    val manager = AppWidgetManager.getInstance(context)
                    OutlinedButton(
                        enabled = manager.isRequestPinAppWidgetSupported,
                        onClick = {
                            val asked = manager.requestPinAppWidget(ComponentName(context, RecordWidget::class.java), null, null)
                            if (!asked) message = "Your launcher did not accept the request. Add the widget from its widget picker."
                        },
                    ) { Text("Add to home screen") }
                    if (!manager.isRequestPinAppWidgetSupported) {
                        Text("Your launcher cannot add widgets from here. Use its widget picker.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                TriggerCard(
                    "Android Auto",
                    "$appName appears in Android Auto's app list as a media app. Open it and use the Record button or " +
                        "the \"Record a note\" item; pause stops the note. A plain play press, or the steering wheel's play " +
                        "button, deliberately does not start a note, because Android Auto presses play by itself when it " +
                        "connects. Because $appName is installed outside Google Play, Android Auto's developer option " +
                        "\"Unknown sources\" must be on.",
                ) {}
            }
            item {
                TriggerCard(
                    "Microphone",
                    "By default notes are recorded with the phone's own microphone. With this on, a connected Bluetooth headset " +
                        "or car hands-free system is used instead, which may give clearer speech than a phone in a pocket or " +
                        "cup holder. While recording, the car or headset may treat it like a call (music pauses, and the sound " +
                        "quality is that of a phone call). Some car head units give the audio back about a second after the link " +
                        "opens (the radio starts again): the note then carries on with the phone's microphone and is marked as " +
                        "recorded with it. The same happens if the Bluetooth microphone does not connect within a few seconds. " +
                        "After a car has done this twice in a row, the app stops trying it and uses the phone's microphone from " +
                        "the start; it is listed here, where you can let it try again.",
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Use a Bluetooth microphone", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = bluetoothMic && hasBluetoothPermission,
                            onCheckedChange = { on ->
                                if (on && !hasBluetoothPermission) {
                                    askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                } else {
                                    bluetoothMic = on
                                    Settings.setBluetoothMic(context, on)
                                }
                            },
                        )
                    }
                    Text(
                        if (!hasBluetoothPermission) "Needs the Nearby devices permission, which is asked when you switch this on."
                        else if (bluetoothDevices.isEmpty()) "No Bluetooth microphone is connected right now."
                        else "Available now: " + bluetoothDevices.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    BluetoothMemory.skippedEntries(remembered).forEach { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Not used with ${entry.name}: it took the audio back right after the start, ${entry.drops} times in a row.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                remembered = BluetoothMemory.forget(remembered, entry.id)
                                Settings.setBluetoothMemory(context, remembered)
                            }) { Text("Try again") }
                        }
                    }
                    TextButton(onClick = {
                        hasBluetoothPermission = BluetoothMic.hasPermission(context)
                        bluetoothDevices = BluetoothMic.available(context).map { it.productName.toString() }
                        remembered = Settings.bluetoothMemory(context)
                    }) { Text("Refresh") }
                }
            }
            item {
                TriggerCard(
                    "Headset or steering-wheel button",
                    "Lets a Bluetooth headset or the car's play/pause button start and stop a note. While this is on, " +
                        "a notification is shown. Android decides which app receives the buttons, so a music app that is " +
                        "playing may still get them first.",
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Use the play/pause button", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = headset,
                            onCheckedChange = {
                                headset = it
                                if (it) VoiceNotesMediaService.turnOn(context) else VoiceNotesMediaService.turnOff(context)
                            },
                        )
                    }
                }
            }
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun TriggerCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

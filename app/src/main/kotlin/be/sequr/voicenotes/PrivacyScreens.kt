// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Opens this app's page in the system settings, where every permission can be changed (including Network on GrapheneOS). */
private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun granted(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/**
 * First start: say what the app does with the phone before any permission is asked for. Shown only when the
 * microphone has not been allowed yet and this screen has not been completed before.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Settings.setOnboarded(context)
        onDone()
    }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                "Record a voice note with one tap, also while driving. The note is turned into text on this phone.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("What stays on your phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Your recordings and their text are stored only in this app, and are never uploaded. There is no account, " +
                    "no analytics and no advertising. Notes leave the phone only when you share or export them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("What the app will ask for", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Microphone: to record your notes.\n" +
                    "• Notifications: to show that a note is being recorded or transcribed, with a Stop button.\n\n" +
                    "Two more are asked later, only if you use the feature: network access when you download a speech model " +
                    "from huggingface.co, and nearby devices when you choose to record through a Bluetooth headset or car.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "You can review all of this later under Privacy & permissions.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { ask.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & permissions") },
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
                    "Everything this app does with your phone, and why. None of it needs an account.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                RuntimePermission(
                    title = "Microphone",
                    permission = Manifest.permission.RECORD_AUDIO,
                    required = true,
                    why = "To record your notes. Recordings are stored only inside this app. The microphone is used only while a " +
                        "note is being recorded, which Android also shows with its own indicator.",
                )
            }
            item {
                RuntimePermission(
                    title = "Notifications",
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    required = false,
                    why = "To show that a note is being recorded or transcribed, and to offer a Stop button. Without it recording " +
                        "still works, but the notification is hidden.",
                )
            }
            item {
                RuntimePermission(
                    title = "Nearby devices",
                    permission = Manifest.permission.BLUETOOTH_CONNECT,
                    required = false,
                    why = "Only for recording through a Bluetooth headset or a car's hands-free system (Triggers → Microphone). " +
                        "The app only looks at which headset is connected; it does not scan for or pair with devices.",
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Network", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Used only to download a speech model from huggingface.co, and only when you tap Download on the " +
                                "Models screen. Your recordings and text are never sent anywhere. Android cannot show whether " +
                                "this is currently allowed. If you want the app to have no network access at all, switch it " +
                                "off in the system's app settings (on GrapheneOS: Permissions → Network); everything except " +
                                "downloads keeps working, and models can still be imported from a file.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { openAppSettings(context) }) { Text("Open app settings") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your data", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "• Recordings, text and settings are kept in the app's private storage, which other apps cannot read.\n" +
                                "• Android's cloud backup and device-to-device transfer are switched off for this app.\n" +
                                "• No analytics, crash reporting, advertising or tracking.\n" +
                                "• A note leaves the phone only through Share or Export, or into a Markdown file you chose.\n" +
                                "• Deleting a note removes its recording and all versions of its text from the phone.\n" +
                                "• Recordings are kept until you remove them. Storage can delete old ones automatically after a time you choose (off by default).\n" +
                                "• Uninstalling the app removes everything it stored.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimePermission(title: String, permission: String, required: Boolean, why: String) {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(granted(context, permission)) }
    // The state can change while the app is in the background (the user may use the system settings).
    LifecycleResumeEffect(permission) {
        isGranted = granted(context, permission)
        onPauseOrDispose {}
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted = it }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (isGranted) "Allowed" else "Not allowed") + if (required && !isGranted) " · needed" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(why, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isGranted) OutlinedButton(onClick = { ask.launch(permission) }) { Text("Allow") }
                TextButton(onClick = { openAppSettings(context) }) { Text("App settings") }
            }
        }
    }
}

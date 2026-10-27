// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private const val SOURCE_URL = "https://tangled.org/sequr.be/voicenotes"

private class Component(val name: String, val what: String, val licence: String, val copyright: String)

/** What ships in the app, or is downloaded by it, and under which licence. Keep in step with build.gradle.kts and third_party/. */
private val components = listOf(
    Component("whisper.cpp and ggml", "Runs the speech recognition on the phone, and the speech detector.", "MIT", "Copyright (c) 2023-2026 The ggml authors"),
    Component("OpenAI Whisper", "The speech recognition models you download (model weights and design).", "MIT", "Copyright (c) 2022 OpenAI"),
    Component("Silero VAD", "Tells speech from silence and noise (a small model, 885 KB, downloaded with the speech models).", "MIT", "Copyright (c) 2020-present Silero Team"),
    Component(
        "AndroidX: Core, Activity, Lifecycle, Compose, Material 3, Room",
        "The app's user interface and its database.",
        "Apache License 2.0",
        "Copyright The Android Open Source Project",
    ),
    Component("Material Design icons", "The small symbols for the microphone that recorded a note, and the search button.", "Apache License 2.0", "Copyright Google LLC"),
    Component("Kotlin and kotlinx.coroutines", "The programming language and its concurrency library.", "Apache License 2.0", "Copyright JetBrains s.r.o. and contributors"),
)

private const val MIT_TEXT =
    "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated " +
        "documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation " +
        "the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and " +
        "to permit persons to whom the Software is furnished to do so, subject to the following conditions:\n\n" +
        "The above copyright notice and this permission notice shall be included in all copies or substantial portions of " +
        "the Software.\n\n" +
        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED " +
        "TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
        "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF " +
        "CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER " +
        "DEALINGS IN THE SOFTWARE."

private const val APACHE_TEXT =
    "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use these files except in compliance " +
        "with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0. Unless " +
        "required by applicable law or agreed to in writing, software distributed under the License is distributed on an " +
        "\"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text("Version $version", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A private voice-note recorder. Speech is turned into text on this phone, and nothing is uploaded.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "This is free software: you can use, study, change and share it under the terms of the GNU General " +
                                "Public License, version 3, or (at your option) any later version. It comes with no warranty.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Plain, selectable text: the app opens no links itself (it has no web view and no network code outside the model downloader).
                        SelectionContainer {
                            Text("Source code: $SOURCE_URL", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("Open source components", style = MaterialTheme.typography.titleMedium) }
            items(components.size) { index ->
                val c = components[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text(c.what, style = MaterialTheme.typography.bodySmall)
                        Text("${c.licence} · ${c.copyright}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MIT License", style = MaterialTheme.typography.titleSmall)
                        Text("Applies to whisper.cpp and ggml, OpenAI Whisper and Silero VAD, each with the copyright notice above.", style = MaterialTheme.typography.bodySmall)
                        Text(MIT_TEXT, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Apache License 2.0", style = MaterialTheme.typography.titleSmall)
                        Text(APACHE_TEXT, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

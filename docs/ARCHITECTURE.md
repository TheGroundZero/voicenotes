<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Architecture: a map of the code

Where things are and how a note travels through them, so that you do not have to read every file to find your way. All source
files are in `app/src/main/kotlin/be/sequr/voicenotes/` unless a path says otherwise; the app is one module and one activity.

## The life of a note

```
trigger ──► RecordingService ──► WavRecorder ──► files/notes/<stamp>.wav + a row in Room (status RECORDING)
 button, tile, widget,                │
 headset, Android Auto                ▼ stop
                              status QUEUED ──► one worker thread ──► TranscriptionRunner
                                                   (WAITING instead, when the battery is low)      │
                                                                                                    ▼
                  Transcript row (current) ◄── Transcription ◄── WhisperTranscriber ◄── native whisper_core
                  note status DONE, NO_SPEECH or FAILED          (JNI: WhisperBridge)   speech check, window, language, beam search
```

1. **Trigger.** Every way to start a note ends as an `ACTION_TOGGLE` intent to `RecordingService`, normally through
   `RecordingService.toggle(context, language)`: the in-app button (`NotesScreen`, via `MainActivity`), `NoteTileService`,
   `VoiceNotesMediaService` (headset buttons and Android Auto). `RecordWidget` builds the same intent itself.
2. **Start.** `RecordingService.startRecording` checks the microphone permission, becomes a foreground service, asks for audio focus and
   asks `MicrophoneRoute` (in `BluetoothMic.kt`) for a Bluetooth microphone if the user turned that on and the device has not been
   given up on (`BluetoothMemory`). `beginRecording` then starts `WavRecorder` (16 kHz mono PCM16, `files/notes/`), inserts the note
   and plays the start tone (`Tones`). A note stops by itself after ten minutes.
3. **Stop.** The note is marked `QUEUED`. A single worker thread transcribes one note at a time. `BatteryPolicy` decides whether that
   may happen now; otherwise the note becomes `WAITING` and `TranscriptionJobService` picks it up later.
4. **Transcribe.** `TranscriptionRunner.run` sets `TRANSCRIBING`, calls `Transcription.transcribe`, which uses `WhisperTranscriber`
   and, through `WhisperBridge` (JNI), the native core. The result is stored as a `Transcript` (every re-transcription and every manual
   edit is another row; one is current), the note's language is updated, and the status becomes `DONE`, `NO_SPEECH` or `FAILED`.
   The first transcript may be appended to the user's Markdown file (`NoteExport.autoAppend`).
5. **Show.** `NotesViewModel` observes Room and feeds `NotesScreen`.

## Code map

### Recording and triggers
| File | What it does |
|---|---|
| `RecordingService.kt` | The foreground service that records, and runs the transcription queue. Audio focus, the Bluetooth fallback, the stop rules. |
| `WavRecorder.kt` | Writes the WAV file from `AudioRecord`; can switch to the phone's microphone while running. |
| `BluetoothMic.kt` | `BluetoothMic` (which Bluetooth microphones exist) and `MicrophoneRoute` (switching Android's communication device). |
| `BluetoothMemory.kt` | Pure logic: which Bluetooth devices took the audio back twice in a row and are no longer tried. |
| `VoiceNotesMediaService.kt` | One media session for headset buttons and for Android Auto (browse tree with *Record a note*). `ignoreKey` decides which keys from the car are ignored. |
| `NoteTileService.kt`, `RecordWidget.kt` | The Quick Settings tile and the home-screen widget. |
| `Tones.kt` | The start, stop and error sounds (the only feedback while driving). |

### Transcription
| File | What it does |
|---|---|
| `TranscriptionRunner.kt`, `Transcription.kt` | Transcribe one stored note and record the result; pick the model. |
| `WhisperTranscriber.kt`, `WhisperBridge.kt`, `Transcriber.kt` | The engine behind an interface; the JNI wrapper. |
| `SpeechGate.kt` | Finds and verifies the speech-check model file (Silero VAD) that the native code uses to skip notes without speech. |
| `TranscriptionJobService.kt`, `BatteryPolicy.kt` | Transcribing notes that had to wait, and when waiting is needed. |
| `AudioCleanup.kt` | The optional removal of old recordings (the text stays). |

### Models
| File | What it does |
|---|---|
| `ModelCatalog.kt` | The models the app can download, with pinned URL, size and SHA-256. Shipped inside the app on purpose. |
| `ModelStore.kt` | Where model files live (`files/models/`, the speech check in `files/vad/`), import by checksum, replacing a file in one step. |
| `ModelDownloader.kt` | **The only code that uses the network.** Resumable download with checksum verification. |
| `ModelDownloadService.kt`, `DownloadPolicy.kt` | The foreground service around it, the shared download state (`ModelDownloads`), and the pure rules for progress and refused starts. |

### Data
| File | What it does |
|---|---|
| `Note.kt`, `Label.kt` | Room entities: `Note`, `Transcript`, `Label`, `NoteLabel`; `NoteStatus` is the state machine above; `NoteFiles` is where audio lives. |
| `NoteDatabase.kt`, `NoteDao.kt` | Room database (version 5, migrations in the file, exported schemas in `app/schemas/`), queries, and the `Io` executor for database work off the main thread. |
| `Settings.kt` | All preferences, in one `SharedPreferences` file. |
| `SearchQuery.kt`, `NoteList.kt`, `Labels.kt` | The search syntax (`label:name`), the grouped list (day headers), and label rules. |
| `BackupArchive.kt`, `BackupCrypto.kt`, `BackupManager.kt`, `BackupPlan.kt` | Backup and restore: the file format, the encryption, the orchestration and the merge rules. See [BACKUP.md](BACKUP.md). |
| `NoteExport.kt` | Sharing, exporting and appending to a Markdown file; always on the user's action. |

### Screens
`MainActivity.kt` holds the theme choice and the navigation (a `Screen` enum, no navigation library). `NotesScreen.kt` is the main list;
`ModelsScreen.kt`, `TriggersScreen.kt`, `ExportScreen.kt`, `BackupScreen.kt`, `LabelsScreen.kt`, `StorageScreen.kt`, `PrivacyScreens.kt`,
`AboutScreen.kt` and `AppearanceScreen.kt` are the others, each with a `...ViewModel.kt` where there is state to keep.
Themes: `ThemeSchemes.kt` (palettes, contrast maths, tested) and [THEMES.md](THEMES.md).

## Native code (`app/src/main/cpp/`)

| File | What it does |
|---|---|
| `whisper_core.cpp`, `whisper_core.h` | The transcription steps: speech check, audio-window sizing, language detection restricted to the preferred languages, beam search. Shared with the benchmark tool. |
| `whisper_jni.cpp` | The JNI entry points. |
| `audio_context.h`, `vn_result.h` | The window-size rule and the JSON result (always valid UTF-8). |
| `CMakeLists.txt` | Builds whisper.cpp (`third_party/whisper.cpp`, a pinned submodule with one patch, `third_party/patches/`) and the core into one library. |

## Threads

The main thread draws the UI. Database work goes through `Io.executor`. Transcription runs on one worker thread inside `RecordingService`
(or in `TranscriptionJobService`), one note at a time, because a model needs about a gigabyte of memory. Downloads run on one thread in
`ModelDownloadService`. State that screens watch is held in `StateFlow`s (`RecordingService.recording`, `ModelDownloads.states`).

## Tests and checks

- JVM unit tests: `app/src/test/kotlin/be/sequr/voicenotes/`, one file per pure piece of logic. There are no instrumented tests.
- `scripts/check.sh` runs everything that needs no phone; `--light` is the part that needs no Android tools. `docs/BUILDING.md` lists
  the setup, `docs/RELEASING.md` the release steps and the reproducible build.
- `benchmark/` is a separate tool (command line, Python scorer, C++ runner that links the same native core) for comparing speech
  models; see [BENCHMARK.md](BENCHMARK.md). It is not part of the app.

## Decisions that look odd but are on purpose

- **No dependency injection, no navigation library, no analytics.** Small and checkable beats flexible.
- **One language per note.** The language is detected once, at the start; see the known gaps in [ROADMAP.md](ROADMAP.md).
- **The model catalog is in the app**, not fetched: a catalog from a server would be a trust anchor nobody can verify.
- **The Bluetooth fallback is automatic.** Some car head units take the audio back about a second after the hands-free link opens;
  the note carries on with the phone's microphone, and `BluetoothMemory` stops trying a device that does this twice in a row.
- **Keys from Android Auto never start a note,** and a pause key never starts one from any source. A note must not start by itself.

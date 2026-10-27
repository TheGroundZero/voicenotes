# Third-party software and data

Everything here is free software. The in-app *About* screen lists the same components.

| Component | Used for | Where | Licence |
|---|---|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and ggml, v1.9.5 | speech recognition, language detection, voice-activity detection (C/C++) | `third_party/whisper.cpp` (git submodule) plus our patch | MIT, (c) 2023-2026 The ggml authors |
| [OpenAI Whisper](https://github.com/openai/whisper) models | the speech models the user downloads or imports; **not part of the app package** | downloaded from huggingface.co on request, see `ModelCatalog.kt` | MIT, (c) 2022 OpenAI |
| [Silero VAD](https://github.com/snakers4/silero-vad) v5.1.2, converted for whisper.cpp | telling speech from silence and noise; **not part of the app package** | downloaded from huggingface.co on request, see `ModelCatalog.kt` | MIT, (c) 2020-present Silero Team |
| AndroidX (Core, Activity, Lifecycle, Compose, Material 3, Room) | user interface, database | Maven dependencies in `app/build.gradle.kts` | Apache-2.0, (c) The Android Open Source Project |
| Kotlin and kotlinx.coroutines | language and concurrency | pulled in by the above | Apache-2.0, (c) JetBrains |
| [Material Design icons](https://github.com/google/material-design-icons) | the small microphone-source symbols and the search button | `app/src/main/res/drawable/ic_source_*.xml`, `ic_search.xml` | Apache-2.0, (c) Google LLC |
| Gradle wrapper | building | `gradle/wrapper`, `gradlew` | Apache-2.0, (c) Gradle Inc. |

The licence texts are in `LICENSES/`. File-level licences are stated in `REUSE.toml` and in the SPDX headers of the
source files.

## whisper.cpp

A git submodule, pinned to the commit of tag **v1.9.5** (`d1be6fde11ac6e0407606b4e42fe72d34add8037`), which is also
checked by `scripts/setup-third-party.sh`. Run that script after cloning (and `git submodule update --init` if you
did not clone with `--recurse-submodules`): it applies our patches in `third_party/patches` and is safe to repeat.
The CMake build refuses to configure if a patch is missing.

- `0001-whisper-set-audio-ctx.patch`: adds `whisper_set_audio_ctx()` so language detection can run with a short
  encoder context. Upstream's `whisper_full()` detects the language *before* it applies `audio_ctx`, so detection always
  encodes a full 30 s window, a fixed cost of several seconds even for a 5 s note. The patch is a few lines.

## Speech-detection model

Not part of the app package. The Silero VAD v5.1.2 model converted for whisper.cpp (885,098 bytes, SHA-256
`29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf`, MIT, (c) 2020-present Silero Team) comes from
<https://huggingface.co/ggml-org/whisper-vad>. The app downloads it from the revision pinned in `ModelCatalog.kt`
(`speechCheck`), together with the first speech model, or imports it from a file, and checks the checksum before use.
`scripts/fetch-vad-model.sh` downloads the same file and verifies it against those pins. The app uses it only to decide
whether a note contains any speech before spending seconds of processor time on transcribing it; without it notes are
transcribed as usual. Versions up to 0.5.1 carried the file inside the APK.

## Updating

To move to a newer whisper.cpp: check out the new tag in the submodule, run `scripts/setup-third-party.sh` with the
new commit in the script, regenerate the patch if it no longer applies, update the version in this file, `README.md`,
`docs/BUILDING.md`, and run the on-device checks (a transcription in each language, silence, a
long note), because whisper.cpp changes can change decoding behaviour.

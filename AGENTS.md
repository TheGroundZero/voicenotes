<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Voice Notes: notes for coding agents

Short on purpose, for people and for coding assistants (local or hosted). The long versions are
[CONTRIBUTING.md](CONTRIBUTING.md), [docs/BUILDING.md](docs/BUILDING.md) and the code map in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Everything here can be checked in the repository.

## What this is

An Android app (Kotlin, Jetpack Compose, Room, whisper.cpp through JNI), package `be.sequr.voicenotes`, minSdk 34, arm64 only.
One tap records a voice note (button, Quick Settings tile, widget, headset or car button, Android Auto) and the phone
transcribes it. **Nothing leaves the phone**; the only network use is downloading a speech model when the user asks.

## Commands (from the repository root)

```sh
# once per clone, in this order (details: docs/BUILDING.md)
scripts/setup-third-party.sh      # checks the pinned whisper.cpp and applies our one patch
scripts/setup-toolchain.sh        # JDK 17, Android SDK, NDK, CMake, pinned; Linux x86_64 only (Android Studio works too)
scripts/fetch-vad-model.sh        # the speech-check model; one unit test is skipped without it
scripts/install-hooks.sh          # refuses direct pushes to main
source env.sh                     # in every new shell

scripts/check.sh --light          # seconds, needs no Android tools: licences, scripts, pins, personal paths, audio files, the Python benchmark tests
./gradlew testDebugUnitTest --tests '*DownloadPolicyTest*'   # one test class
scripts/check.sh                  # the whole gate, a few minutes: also the C++ core tests (g++), Android lint, all unit tests, the privacy guards
./gradlew assembleDebug           # a debug APK
./gradlew assembleReleaseCheck    # a release-like build that installs next to a real install (be.sequr.voicenotes.releasecheck)
```

The app has JVM unit tests only (`app/src/test`); the benchmark code has Python tests and one C++ test (`benchmark/tests`). Anything that touches recording, Bluetooth, the foreground services or Android Auto can
only be tried on a real phone (or the Desktop Head Unit, `scripts/dhu.sh`): say in the commit message what you could not test.

## Rules the build enforces (do not work around them)

- **Network code only in `ModelDownloader.kt`.** The build fails if another file uses network APIs or if the manifest asks for another
  network permission (`verifyNetworkIsolation` in `app/build.gradle.kts`). No analytics, crash reporting, ads or Play Services
  (`verifyNoTrackers`).
- **No recordings and no personal data in the tree.** `scripts/check.sh` fails on audio files and on `/home/<user>/` paths. Tests use
  invented text and generated or synthetic audio only; never a real voice, never a real transcript.
- **Licence headers.** A new source file starts with `SPDX-License-Identifier: GPL-3.0-or-later` (`scripts/check-licenses.py`;
  `REUSE.toml` covers files that cannot carry one).
- **Scripts that run in CI use only `bash`, coreutils, findutils, `grep`, `git` and `python3`**: that is all the Tangled CI image has
  (a script that used `sed` once failed a check there).
- **Do not edit `third_party/whisper.cpp`** (a pinned submodule plus `third_party/patches/0001-whisper-set-audio-ctx.patch`).
- `gradle/verification-metadata.xml` is written by Gradle; read the diff of a dependency change, do not edit it by hand.
- The UI text is mostly written directly into the Compose code, in English; there are no translations yet.

## How to write a change here

- Match the surrounding code, including how much it comments. Comments say *why*.
- Put decisions into small pure objects with unit tests instead of into services and screens. Examples to copy:
  `DownloadPolicy`, `BluetoothMemory`, `SearchQuery`, `BatteryPolicy`, and `VoiceNotesMediaService.ignoreKey`.
- Make a new test fail once on purpose (break the rule for a moment) to see that it tests something.
- User-visible changes get a line under "Unreleased" in `CHANGELOG.md`; docs that describe the behaviour change with the code.
- Small commits, [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) (`fix(models): ...`), on a branch named
  `feat/...`, `fix/...` or `docs/...`. `main` only changes through a pull request on Tangled
  (<https://tangled.org/sequr.be/voicenotes>); the GitHub repository is a read-only mirror.
- Pushing more commits (or force-pushing) to a branch does not update an open pull request on Tangled: the author has to press
  *Resubmit*, and a merged pull request cannot take later commits (open a new one).

## Habits that avoid mistakes

- After every commit run `git status` and `git show --stat HEAD`, and check that it contains exactly what you meant. Never hide the
  error output of `git add`: a path that does not exist makes it add nothing.
- Read the output of `scripts/check.sh` to the end; a green exit code with an empty log is not a result.
- If a command needs a decision about secrets, the network or somebody's phone, stop and ask.
- If you wrote or substantially shaped a commit, end its message with a `Co-Authored-By: <tool/model> <noreply address or tool URL>`
  trailer, and say so in the PR description ([CONTRIBUTING.md](CONTRIBUTING.md#ai-assisted-contributions)). A person stays responsible.

## Files an assistant does not need to read

Generated or large, and rarely relevant: `benchmark/results*/` (JSON, about 19,000 lines each), `gradle/verification-metadata.xml`,
`third_party/whisper.cpp/`, `LICENSES/`, `fastlane/**/images/`, `app/schemas/` (Room exports), `docs/BENCHMARK.md` unless you work on the
benchmark.

<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Roadmap

Voice Notes records voice notes hands-free (while driving, for example), transcribes them offline on the device and
exports them as Markdown. This page says what exists, what is next, and the design rules that decisions are measured
against. Build and release instructions are in [BUILDING.md](BUILDING.md) and [RELEASING.md](RELEASING.md).

## Design rules

- **Private by default.** No analytics, no accounts, no cloud. The only network use is downloading a speech model (and the small speech-check model) when
  the user asks for it (HTTPS, pinned revision, SHA-256 checked). Only `ModelDownloader.kt` may use network APIs, and the
  build fails otherwise. Models can also be imported from a file, so the app works with network access revoked.
- **No Google services.** Built for GrapheneOS and plain AOSP; no Play Services, no tracker libraries (checked by the
  build).
- **Never lose a note.** Audio is saved before transcription; a failed transcription keeps the audio.
- **Extensible.** Triggers and transcription engines are separate extension points.
- **Reproducible and verifiable.** Pinned toolchain and dependencies; a clean local build and the CI build of every release (v0.1.0 to v0.5.1) are byte-identical.

## Done

- Recording from a Quick Settings tile, an in-app button, a home-screen widget, a headset or steering-wheel button, and
  an Android Auto media entry (record only; other audio pauses while recording). Bluetooth microphone when connected.
- Offline transcription with whisper.cpp (beam search, short-note speed-up), language auto-detection among the user's
  preferred languages, a speech check (Silero VAD) that skips silent recordings, re-transcription with kept versions.
- Model management: download with checksum, import, delete; one recommended model and a smaller fallback.
- Battery-aware transcription (pauses below 15 %, resumes at 25 % or on charger, none in battery saver, manual always
  allowed); optional automatic removal of old recordings.
- Share and export: share sheet, Markdown export, append to a user-chosen file.
- Labels and one search bar (words search the text, `label:name` the labels only; version 0.3.0).
- Target SDK 36 (Android 16): the toolchain moved to Android Gradle Plugin 8.9.3 and Gradle 8.11.1, and recording, the headset-button service, model downloads, background transcription jobs and back navigation were checked on a phone (version 0.5.0).
- Sorting: newest or oldest first, remembered, with a header for each day (version 0.4.0).
- Backup and restore (version 0.2.0): one file, optionally encrypted, merge or replace, checked before and after ([BACKUP.md](BACKUP.md)).
- Release readiness: GPL-3.0-or-later with REUSE metadata, pinned toolchain script, dependency verification, a check
  script (licences, lint, tests, privacy guards), run-time CPU variant selection, shrunk release build, store texts.

## Next, in order

1. **Publishing, remaining part.** Versions 0.1.0 to 0.5.1 are released (Tangled, GitHub mirror, Obtainium). F-Droid answered the
   questions ([fdroid/questions-to-fdroid.md](fdroid/questions-to-fdroid.md)): open a Request For Packaging, with everything built
   from source or downloaded by the user. The one bundled binary (the speech-check model) is now downloaded like the Whisper models.
   The policy asks for three months of active development, which the project reaches on 2027-01-23; until then the request waits
   (readiness checklist and request text: [fdroid/rfp-draft.md](fdroid/rfp-draft.md)). Other things to settle first: validating
   recording from Android Auto in the car, and trying the recipe with F-Droid's own tools.
2. **Automated model benchmarking** (design and progress: [BENCHMARK.md](BENCHMARK.md); the decision rule is written down before any result; steps 1 and 2 of 5 are done). A repeatable harness that compares new speech models (Whisper family first, engine
   and model kept swappable) on word error rate, hallucinations, language identification, speed and memory, using only
   generated or openly licensed speech. Recordings of the maintainers' own voices are never uploaded or published.
3. **In-car validation** (moved back on 2026-10-26; it needs the real car). Headset button alongside a music app, Android Auto on a real head unit, car hands-free
   microphone, speech-check tuning for road noise, a guard against repetition loops in transcripts.
4. **Colour themes (for fun, last).** A few extra schemes next to the system light and dark theme and dynamic colour:
   high contrast, "Matrix" and "Dracula". Chosen in the app, with the default staying "follow the system".

## Known gaps

- Sorting is by time only. Other orders (by recording length, to clean up storage; by state, probably better as a
  filter) were left out; sorting by label is covered by `label:` search.
- No boot receiver: after a reboot, waiting notes and the headset-button service need the app opened once.
- Some transcripts start with "..."; the repetition guard does not exist yet.
- A note has one language. It is detected once, at the start, and the whole note is transcribed in it, so a note that switches
  language halfway (for example the second half in Dutch) gets that part translated or dropped. Per-segment language
  detection is an idea of **low priority**; the benchmark's mixed-language clips ([BENCHMARK.md](BENCHMARK.md)) will show how
  much it matters. Until then: one note per language.
- Search covers the current text of a note, not its older versions or the Markdown file it was appended to.
- Only tested on one phone model; CPU variants without i8mm/fp16/dotprod are verified to work but are slower.
- The widget and tile "add" buttons, voice start in Android Auto and the daily cleanup job's schedule are untested.
- The first recording from Android Auto in a real car (2026-10-28) ended after a fraction of a second: the car took the audio
  focus away and the note stopped. The fix is on its way and has to be tried in the car again.

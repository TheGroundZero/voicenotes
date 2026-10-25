<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Roadmap

Voice Notes records voice notes hands-free (while driving, for example), transcribes them offline on the device and
exports them as Markdown. This page says what exists, what is next, and the design rules that decisions are measured
against. Build and release instructions are in [BUILDING.md](BUILDING.md) and [RELEASING.md](RELEASING.md).

## Design rules

- **Private by default.** No analytics, no accounts, no cloud. The only network use is downloading a speech model when
  the user asks for it (HTTPS, pinned revision, SHA-256 checked). Only `ModelDownloader.kt` may use network APIs, and the
  build fails otherwise. Models can also be imported from a file, so the app works with network access revoked.
- **No Google services.** Built for GrapheneOS and plain AOSP; no Play Services, no tracker libraries (checked by the
  build).
- **Never lose a note.** Audio is saved before transcription; a failed transcription keeps the audio.
- **Extensible.** Triggers and transcription engines are separate extension points.
- **Reproducible and verifiable.** Pinned toolchain and dependencies; a clean local build and the CI build of v0.1.0 are byte-identical.

## Done

- Recording from a Quick Settings tile, an in-app button, a home-screen widget, a headset or steering-wheel button, and
  an Android Auto media entry (record only; other audio pauses while recording). Bluetooth microphone when connected.
- Offline transcription with whisper.cpp (beam search, short-note speed-up), language auto-detection among the user's
  preferred languages, a speech check (Silero VAD) that skips silent recordings, re-transcription with kept versions.
- Model management: download with checksum, import, delete; one recommended model and a smaller fallback.
- Battery-aware transcription (pauses below 15 %, resumes at 25 % or on charger, none in battery saver, manual always
  allowed); optional automatic removal of old recordings.
- Share and export: share sheet, Markdown export, append to a user-chosen file.
- Backup and restore (version 0.2.0): one file, optionally encrypted, merge or replace, checked before and after ([BACKUP.md](BACKUP.md)).
- Release readiness: GPL-3.0-or-later with REUSE metadata, pinned toolchain script, dependency verification, a check
  script (licences, lint, tests, privacy guards), run-time CPU variant selection, shrunk release build, store texts.

## Next, in order

1. **Publishing, remaining part.** v0.1.0 is released (Tangled, GitHub mirror, Obtainium). Still to do: ask the F-Droid
   team the open questions ([fdroid/questions-to-fdroid.md](fdroid/questions-to-fdroid.md)) and, if they agree, submit
   the app.
2. **Target SDK 36.** Upgrade as its own step with a full regression pass (background jobs, foreground services, audio
   focus), before the in-car validation.
3. **Labels.** Give notes user-defined labels (a note can have several) and filter the list by label (through the search bar, see Search). Labels are created
   and renamed in the app, removed from a note without deleting the label, and part of export and backup.
4. **Search.** One search bar. Words match what was said, in the transcripts. A `label:` prefix matches label names
   only, for example `label:shopping milk` finds notes labelled "shopping" whose transcript contains "milk". Every
   other word matches transcript text only, so a note that merely *says* "shopping" is not found by `label:shopping`.
   There is no separate label filter: while typing `label:` the bar suggests the existing labels, and tapping a label on
   a note fills the bar with its `label:` term.
5. **In-car validation.** Headset button alongside a music app, Android Auto on a real head unit, car hands-free
   microphone, speech-check tuning for road noise, a guard against repetition loops in transcripts.
6. **Automated model benchmarking.** A repeatable harness that compares new speech models (Whisper family first, engine
   and model kept swappable) on word error rate, hallucinations, language identification, speed and memory, using only
   generated or openly licensed speech. Recordings of the maintainers' own voices are never uploaded or published.
7. **Colour themes (for fun, last).** A few extra schemes next to the system light and dark theme and dynamic colour:
   high contrast, "Matrix" and "Dracula". Chosen in the app, with the default staying "follow the system".

## Known gaps

- No boot receiver: after a reboot, waiting notes and the headset-button service need the app opened once.
- Some transcripts start with "..."; the repetition guard does not exist yet.
- Only tested on one phone model; CPU variants without i8mm/fp16/dotprod are verified to work but are slower.
- The widget and tile "add" buttons, voice start in Android Auto and the daily cleanup job's schedule are untested.

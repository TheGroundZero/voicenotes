# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.5.0] - 2026-10-26

### Changed

- **Target Android 16 (API 36)**, as Google Play and newer phones expect. Nothing changes for you: recording from the
  button, the tile and a headset, the model download, the transcription jobs that wait for the battery, the back
  button and every screen were checked on a phone running Android 16. Builds now use Android Gradle Plugin 8.9.3 and
  Gradle 8.11.1 (the first versions that support API 36).

## [0.4.0] - 2026-10-25

### Added

- **Sorting:** the note list can show the oldest notes first (menu → *Show oldest first*, and back with *Show newest
  first*); the choice is remembered. Every day has a header ("Today", "Yesterday", then the date), also in search
  results. A new note is shown at the top or the bottom, depending on the order, unless you are reading elsewhere.

## [0.3.0] - 2026-10-25

### Added

- **Labels:** give a note any number of your own labels (a note's ⋯ menu → Labels…, or the Labels screen in the menu to
  create, rename and delete them). Labels show as chips on the note; tapping one searches for it.
- **Search:** the search icon opens one search bar. Words match what was said (current text, ignoring case and accents);
  `label:name` (or `label:"two words"`) matches labels only, never the text, and typing `label:` suggests your labels.
  `label:shopping milk` means labelled "shopping" and containing "milk".
- Backups now carry the labels (backup format version 2; version 1 files from 0.2.0 are still read), and the
  Markdown export lists a note's labels as `#tags`.

### Changed

- The notes database is upgraded when the app first starts (version 4 to 5; your notes are kept). Going back to 0.2.0
  afterwards is not possible without restoring a backup.

## [0.2.0] - 2026-10-25

### Added

- **Backup & restore** (menu): save all notes, every transcript version and, optionally, the recordings as one file in a
  place you pick (Nextcloud and other providers work through Android's file picker). The file can be encrypted with a
  passphrase (AES-256-GCM; on by default; a lost passphrase cannot be recovered). The backup is read back and checked
  right after it is written. Restoring shows what the file holds first, can add to your notes (default) or replace
  them, skips notes you already have, and changes nothing if anything is wrong with the file. See `docs/BACKUP.md`.

## [0.1.0] - 2026-10-23

First public version (0.1.0). Everything below is new.

### Added

- **Recording** from an in-app button, a Quick Settings tile, a home-screen widget, a headset's or car's play/pause
  button, and Android Auto (a media app with a Record button; other audio pauses while a note is recorded). Start and
  stop are signalled by tones, and a notification shows a running note.
- **Offline transcription** with whisper.cpp and Whisper models. Language detection among the languages you prefer
  (English, Dutch, French and others); beam search and a short-note speed-up; the best available CPU variant is
  chosen when the app starts.
- **Speech check** (Silero VAD): recordings with no speech are marked "No speech detected" instead of producing
  invented text, with "Transcribe anyway" when you disagree.
- **Transcript versions:** re-transcribe with another model or language, edit by hand, switch between versions;
  nothing is overwritten.
- **Models:** download with SHA-256 verification (Wi-Fi only unless you allow mobile data), import from a file,
  delete. Two models are offered: Large v3 Turbo (874 MB, most accurate) and Small (487 MB).
- **Battery aware transcription:** pauses below 15 %, resumes at 25 % or on the charger, never in battery saver; a
  manual "Transcribe" always works. Notes recorded meanwhile wait and are transcribed later.
- **Sharing:** share text or audio, append to a Markdown file you choose, export everything.
- **Microphone choice:** optionally record through a Bluetooth headset or a car's hands-free system; the list shows
  which microphone recorded each note.
- **Storage:** an overview and an optional automatic removal of recordings 1 week to 1 year after transcription
  (text and versions are kept; off by default), plus removing a single recording.
- **Privacy & permissions** and **About** screens that explain every permission and list all open-source components.

### Privacy and trust

- No account, analytics, advertising or tracking; the only network use is the model download you start. The build
  fails if any other code uses the network or if a tracking library is added.
- Free software under GPL-3.0-or-later with REUSE licence metadata; pinned toolchain, Gradle wrapper and
  dependency checksums; clean release builds are byte-for-byte reproducible on the same toolchain.

### Known limitations

- Android 14 or newer and 64-bit ARM only. Phones without the dot-product instructions are untested; processors
  without i8mm transcribe about 2.4 times slower than the developer's Pixel 10.
- Tested on one phone (GrapheneOS), one headset and the Android Auto Desktop Head Unit; not yet in a real car's head
  unit. After a reboot, open the app once so waiting notes and the headset button are picked up again.
- Some transcripts start with "..." and a repetition guard does not exist yet.

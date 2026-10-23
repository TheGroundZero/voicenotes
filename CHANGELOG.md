# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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

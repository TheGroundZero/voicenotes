# Contributing

Thank you for looking. This is a small personal project; changes that fit its goals (private, offline, minimal) are
welcome.

## Before you start

- Open an issue on the repository to discuss anything bigger than a small fix.
- Read [docs/BUILDING.md](docs/BUILDING.md) to set up the tools, and [docs/ROADMAP.md](docs/ROADMAP.md) for the design rules and the open work.

## Rules that protect the privacy promise

These are checked by the build (`scripts/check.sh`) and will fail a change that breaks them:

- **Network access only in `ModelDownloader.kt`.** No other file may use network APIs, and the manifest may request no
  other network-related permission (only `INTERNET`, `ACCESS_NETWORK_STATE`, and `BLUETOOTH_CONNECT` for choosing a
  Bluetooth microphone).
- **No Google Play Services, Firebase, analytics, crash reporting, advertising or tracking libraries.**
- Anything that makes data leave the phone needs an explicit action by the user and must be described in `PRIVACY.md`
  and on the in-app *Privacy & permissions* screen.

## Code and commits

- Kotlin, Jetpack Compose, the style of the surrounding code. Comments say *why*, not what.
- Every source file starts with `SPDX-License-Identifier: GPL-3.0-or-later`. Run `scripts/check.sh` before sending a change.
- Keep commits small and explain the reason in the message. Test on a real device when you touch recording,
  transcription, Bluetooth or Android Auto; say in the message what you tested.
- By contributing you agree that your contribution is licensed under GPL-3.0-or-later. Sign off your commits
  (`git commit -s`) to state that you have the right to submit them (Developer Certificate of Origin).

## Reporting problems

Say which phone and Android version, what you did, and what you expected. **Do not attach recordings or transcripts
you would not want to be public.**

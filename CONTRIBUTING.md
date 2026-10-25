# Contributing

Thank you for looking. This is a small personal project; changes that fit its goals (private, offline, minimal) are
welcome.

## Before you start

- Report bugs and discuss anything bigger than a small fix in the [issue tracker](https://tangled.org/sequr.be/voicenotes/issues/).
- User-visible changes go under "Unreleased" in [CHANGELOG.md](CHANGELOG.md); `scripts/release.sh` turns that into the dated release section.
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
- Keep commits small and explain the reason in the message (see *Commit messages* below). Test on a real device when you touch recording,
  transcription, Bluetooth or Android Auto; say in the message what you tested.
- By contributing you agree that your contribution is licensed under GPL-3.0-or-later, and you state that you have the
  right to submit it. Commits do not need a `Signed-off-by` line.

## Branches and pull requests

`main` only changes through a pull request. Work on a feature branch named after the commit type and the topic
(`feat/note-search`, `fix/waiting-notes-after-reboot`, `docs/roadmap`, `ci/...`), push the branch to Tangled and open a
pull request there. Delete the source branch only after the pull request's pipeline has finished: the merge rewrites commit
IDs, and a pipeline that starts for a commit that no longer exists fails in its clone step ("expected 'packfile'"). Run `scripts/check.sh` first; the CI on Tangled only repeats the light checks. After the merge,
a Tangled workflow (`sync-github.yml`) copies `main` and version tags to the GitHub mirror by itself. There GitHub runs
the full checks and builds a development APK, kept 14 days as a workflow artifact (it installs next to the real app as
`be.sequr.voicenotes.releasecheck`); only a version tag publishes a signed release.

Tangled cannot protect a branch on the server yet ([issue #432](https://tangled.org/tangled.org/core/issues/432)), so
`scripts/install-hooks.sh` installs a `pre-push` hook that refuses direct pushes to `main`. Install it once per clone.

## Commit messages

[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/): `type(scope): summary`, in the imperative,
then a body that says why. Types: `feat`, `fix`, `docs`, `build`, `ci`, `test`, `refactor`, `perf`, `chore`. Mark a
breaking change with `!` after the type or a `BREAKING CHANGE:` footer. Suggested scopes: `recording`, `transcription`,
`models`, `export`, `auto` (Android Auto), `ui`, `build`, `fdroid`. User-visible `feat` and `fix` commits also get a line
under "Unreleased" in `CHANGELOG.md`. The initial commit predates this rule and is the only exception.

## Reporting problems

Say which phone and Android version, what you did, and what you expected. **Do not attach recordings or transcripts
you would not want to be public.**

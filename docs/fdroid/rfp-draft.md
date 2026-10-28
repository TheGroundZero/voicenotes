<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# F-Droid: readiness and the request for packaging (draft)

Where we stand against the [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) and the
[submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/) (read 2026-10-28). The build recipe is
[be.sequr.voicenotes.yml](be.sequr.voicenotes.yml). The team's reply of 2026-10-27 is in [questions-to-fdroid.md](questions-to-fdroid.md).

## The one blocker: the age of the project

The policy asks for **at least three months of active development**, unless the app has proven users, the author maintains
other F-Droid apps, or it was made for an event. The first commit is from **2026-10-23**, so the earliest date that criterion
is met is **2027-01-23**. None of the exceptions applies. This is very likely what "the app is not ready for inclusion yet"
meant. Until then the app is available through GitHub releases and Obtainium.

## Checklist

| Requirement | State |
|---|---|
| FLOSS licence | GPL-3.0-or-later, with SPDX headers and `LICENSES/`; REUSE-style (`REUSE.toml`). |
| Public source, builds from it | Yes; `docs/BUILDING.md`; the release APK is byte-identical between CI and a clean local build (`scripts/verify-reproducible.sh`). |
| Prebuilt binaries | The bundled speech-check model is gone with `feat/vad-download` (downloaded on request, or imported). Left: `gradle/wrapper/gradle-wrapper.jar` (checked against a pinned checksum in `scripts/check.sh`; reviewers may want it deleted in the recipe). Dependencies come from Maven Central and Google Maven, with dependency verification. |
| Native code | whisper.cpp is a git submodule pinned to tag v1.9.5, one patch applied by `scripts/setup-third-party.sh` (the recipe's `prebuild`); NDK and CMake via sdkmanager (confirmed by the team). |
| No trackers, ads or Play Services | `verifyNoTrackers` in the build; `verifyNetworkIsolation` fails the build if network code appears anywhere but `ModelDownloader.kt` or if unexpected network permissions are requested. |
| Downloads of executable code | None. Only model data (MIT) is downloaded, on request, over HTTPS, at a pinned revision with a pinned SHA-256. |
| Anti-feature NonFreeNet | Not declared. The team said it may apply unless users can load the files from storage: every model and the speech check can be imported from a file. Confirm in the request. |
| Artwork and fonts | No bitmap or font files in the app's resources. The vector icons for the microphone source and search are Material Design icons (Apache-2.0, annotated in `REUSE.toml`); the microphone glyph (also the launcher and store icon) is the Material "mic" and is annotated Apache-2.0 too (fixed in the same branch). |
| Donation link | `.github/FUNDING.yml` (branch `chore/funding`, not pushed yet); the recipe has `Donate:`. |
| Own application ID | `be.sequr.voicenotes`, from the domain sequr.be. |
| Fastlane metadata | Title, descriptions, 20 screenshots and `changelogs/<versionCode>.txt` (2 to 7). The release script must add `8.txt` for 0.6.0. Tags are `vX.Y.Z`. |
| Reproducible build | Recipe has `Binaries` and `AllowedAPKSigningKeys` (the key in `README.md`); never run through F-Droid's tooling. |
| Maintained, works as described | Maintained. **Not yet true for the car:** the description says "for the car", and recording from Android Auto is not validated (a fix is on `fix/car-focus-loss`, waiting for a test in the car). Validate before submitting. |
| Active for three months | **No, until 2027-01-23.** |

## Before the request is opened

1. Merge `feat/vad-download` and release it (0.6.0); then no binary data is left in the APK.
2. Validate recording from Android Auto in the car (fix on `fix/car-focus-loss`), so the description is true.
3. Push `chore/funding` so the donation link can be verified.
4. Run F-Droid's tools on the recipe, as the guide says. **Done on 2026-10-28:** `lint`, `readmeta` and `checkupdates` pass (see *Trying F-Droid's tools*); `fdroid build` is still to do.
5. Wait for 2027-01-23 (or ask the team whether anything else counts as "proven users").

## Trying F-Droid's tools (2026-10-28)

Run with the official image `registry.gitlab.com/fdroid/docker-executable-fdroidserver:master` (digest
`sha256:3dbc5a72cae3597d712b9bb02e44acf36ed88cd6cad76ee2f0853b1d4136fa1a`, built 2026-10-25) in a container without any mounts: the recipe,
fdroiddata's `config/categories.yml` and empty stand-ins for the category icons it lists go in through stdin as a tar archive. The image's
entrypoint sources `/etc/profile.d/bsenv.sh` and then runs `$fdroidserver/fdroid`; do the same when overriding the entrypoint.

| Command | Result |
|---|---|
| `fdroid lint be.sequr.voicenotes` | passes, **with fdroiddata's real `config/categories.yml`**; the first run without it called valid categories invalid |
| `fdroid readmeta` | passes |
| `fdroid rewritemeta` | only strips comments and moves `Binaries` and `ndk` into the canonical order: the submitted file must be its output |
| `fdroid checkupdates` | clones the GitHub mirror, finds the tags `v0.3.0` to `v0.6.0`, resolves `v0.6.0` to `7fa77b8`, leaves `CurrentVersion 0.6.0` / `8` unchanged |
| `fdroid build` | **not run yet**: it needs the Android SDK and NDK inside the container and a few GB of memory |

What the run changed in the recipe: the categories are now `Note`, `Recorder` and `Speech Recognizer` (the current list is much more
detailed than `Writing` and `Multimedia`, which are still valid), and `Repo` is the GitHub mirror.

Still to do: `fdroid build` itself. It needs the Android SDK and NDK inside the container (the image has none), network access for the
dependencies and the whisper.cpp submodule, and a few GB of memory.

## The request (text to post in the fdroiddata submission queue)

> **Voice Notes** (`be.sequr.voicenotes`): private voice notes, transcribed on the phone
>
> - Source: https://tangled.org/sequr.be/voicenotes (mirror: https://github.com/TheGroundZero/voicenotes), GPL-3.0-or-later, tags `vX.Y.Z`
> - I am the author and I ask for packaging.
> - What it does: records a voice note (app button, quick-settings tile, widget, headset or car buttons, Android Auto) and
>   transcribes it on the device with whisper.cpp. No account, no analytics, no cloud.
> - Network: only to download speech models on request, from huggingface.co (HTTPS, pinned revision and SHA-256). Models can
>   also be imported from a file. As the team suggested, I did not declare NonFreeNet for that; please tell me if you
>   still want it.
> - Binaries: none bundled (the speech-check model used to be; it is now downloaded or imported like the Whisper models).
> - Build: Gradle with dependency verification; whisper.cpp as a git submodule (`submodules: true`) plus one patch applied by
>   `scripts/setup-third-party.sh`; NDK 27.2.12479018 and CMake 3.22.1 from sdkmanager.
> - Reproducible: the release APK is byte-identical between CI and a clean local build; recipe has `Binaries` and
>   `AllowedAPKSigningKeys`.
> - Recipe draft: `docs/fdroid/be.sequr.voicenotes.yml` in the repository.
> - Quality criteria: active development since 2026-10-23, so I understand the three-month rule; I am opening the request at
>   the earliest date it is met.

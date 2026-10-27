# Releasing

Status: used for v0.1.0 (2026-10-23), v0.2.0 to v0.4.0 (2026-10-25) and v0.5.0 and v0.5.1 (2026-10-26 and 27). Each signed APK built by GitHub Actions was byte-identical to a clean local build.

## Versions

- Semantic versioning, tags `vX.Y.Z`.
- `versionName` and `versionCode` are written as **plain numbers/strings** in `app/build.gradle.kts` on purpose: F-Droid
  reads them from the tagged source. `versionCode` goes up by one for every release, including pre-releases.
- `scripts/release.sh X.Y.Z` shows what a release would change; `scripts/release.sh X.Y.Z --apply` does it (refuses to
  run with uncommitted changes or failing checks). It dates the changelog section with the day it runs: if the release
  is published on a later day, correct the date.
- Each release has a store changelog `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters at most)
  and a section in `CHANGELOG.md`.

## The signing key

The key signs every update; **if it is lost, existing users cannot update**, and if it leaks, someone else can publish
updates. Create it once, on a trusted machine, and keep at least two encrypted copies in different places:

```sh
keytool -genkeypair -v -keystore voicenotes-release.jks -alias voicenotes -keyalg RSA -keysize 4096 -validity 10950
```

Then create `keystore.properties` next to `build.gradle.kts` (it is git-ignored; never commit it or the key):

```properties
storeFile=/absolute/path/to/voicenotes-release.jks
storePassword=...
keyAlias=voicenotes
keyPassword=...
```

Without that file `assembleRelease` produces an *unsigned* APK, which is what F-Droid expects (it signs with its own key).
Consequence: **an APK signed with your key and an APK built and signed by F-Droid cannot update each other.** Pick one
channel per phone, or let F-Droid publish your signed APK, which it can only do if its own build of the same commit
is byte-identical to yours (recipe fields `Binaries` and `AllowedAPKSigningKeys`).

### Reproducible builds

The unsigned release APK is reproducible **when it is built from scratch**: on 2026-10-23 complete rebuilds in the development
tree and in copies of the source in two other directories (one of them hidden, one with a git repository and one without)
gave byte-identical files; an earlier copy built with a freshly installed toolchain and an empty Gradle cache matched an
incremental build of the same source. **An incremental build can differ from a clean one** (the app code compiled by Kotlin
and R8 comes out slightly different), so always make a release with `./gradlew clean assembleRelease` (or
`--rerun-tasks`), never on top of earlier builds.
On 2026-10-23 the signed v0.1.0 APK built by the GitHub Actions runner (Ubuntu 24.04, its own toolchain install) and a clean local build on a different machine were **byte-identical, signature included** (RSA signatures are deterministic). A fourth thing is needed for that: `dependenciesInfo { includeInApk = false }`, because the Android Gradle Plugin otherwise embeds a dependency list encrypted with a Google key and with fresh randomness, which differs on every build (and is rejected by F-Droid).
Three more things made it so (keep them): `-ffile-prefix-map` and `--build-id=none` for the native code, git switched off for
ggml's build stamp (it would write the commit and a "-dirty" flag into the library), and `vcsInfo.include = false`
for the APK. `scripts/verify-reproducible.sh` repeats the test (it sets `keystore.properties` aside while it runs, because it compares unsigned files); run it before a release and after every change of the toolchain (the move to Android Gradle Plugin 8.9.3 and Gradle 8.11.1 for API 36 was checked this way). What has **not** been tested:
another operating system, a different NDK or JDK build than the pinned ones, and F-Droid's own build server. A signed APK is the unsigned one plus a signature, so compare *unsigned* files (or use `apksigcopier`).

## Building a release

```sh
source env.sh
scripts/check.sh
./gradlew assembleRelease          # app/build/outputs/apk/release/
```

To try a release build on a phone without replacing the real app, use `./gradlew assembleReleaseCheck`: it is the same
shrunk build under the application ID `be.sequr.voicenotes.releasecheck`, signed with the debug key, installed next to the
real app with its own data.

## Publishing

1. Merge the release pull request on Tangled, then tag the merged commit and push the tag to Tangled only
   (`git push origin vX.Y.Z`; never tag a feature-branch commit, the merge rewrites commit IDs).
2. The Tangled workflow `sync-github.yml` pushes the tag to the GitHub mirror, where `release.yml` builds, signs from
   repository secrets and attaches `voicenotes-X.Y.Z.apk` and its SHA-256 to a release; Obtainium follows that release
   page. Every merge to `main` is synced the same way and starts `dev.yml` (full checks, development APK as a
   14-day artifact, never a release). The sync needs the Tangled pipeline secret `GITHUB_MIRROR_SSH_KEY` (private half
   of a deploy key with write access on the GitHub repository, listed as a bypass actor in the ruleset that protects
   `main` there).
3. F-Droid (optional): the recipe in `docs/fdroid/` goes to F-Droid's data repository; F-Droid builds from the tag.

## Store screenshots

They live in `fastlane/metadata/android/en-US/images/phoneScreenshots/`, numbered, in dark and light. Taken from the
`releasecheck` build (`./gradlew assembleReleaseCheck`, installed next to the real app) seeded with invented notes, never
with real recordings or the maintainer's own app. Rules learned the hard way:

- Crop the status bar and the navigation bar, switch the system theme for the second set, and keep the keyboard out of
  the picture (its suggestion strip can show a personal e-mail address). Shrink the PNGs with `pngquant`.
- **Add new screenshots under new numbers; never rename existing ones.** A Tangled pull request counts a renamed image as
  a new one and refuses to carry that much image data ("Some binary files are too large to carry in this patch", and
  merging then leaves them out). Keep each pull request to a few small images.

## Checklist before a release

- [ ] `scripts/check.sh` passes; dependency updates reviewed (`gradle/verification-metadata.xml` changes are read, not just accepted)
- [ ] after a change of the toolchain or of dependencies: one build with an empty Gradle home
      (`GRADLE_USER_HOME=$(mktemp -d) ./gradlew --no-daemon lintDebug testDebugUnitTest assembleRelease`), because CI starts
      with an empty cache and needs files (such as `.module` metadata) that a warm local cache never fetches
- [ ] on a real phone: recording from the tile and the in-app button, a transcription in each preferred language, silence, a
      long note, share and export, Android Auto on the Desktop Head Unit
- [ ] `CHANGELOG.md`, the store changelog and `PRIVACY.md` match what the release does
- [ ] the signed APK installs over the previous release (same signing key) without losing notes

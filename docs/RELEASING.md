# Releasing

Status: a draft process. Nothing here has been run end to end yet (there is no public repository and no signing key).

## Versions

- Semantic versioning, tags `vX.Y.Z`.
- `versionName` and `versionCode` are written as **plain numbers/strings** in `app/build.gradle.kts` on purpose: F-Droid
  reads them from the tagged source. `versionCode` goes up by one for every release, including pre-releases.
- `scripts/release.sh X.Y.Z` shows what a release would change; `scripts/release.sh X.Y.Z --apply` does it (refuses to
  run with uncommitted changes or failing checks).
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
Three things made that so (keep them): `-ffile-prefix-map` and `--build-id=none` for the native code, git switched off for
ggml's build stamp (it would write the commit and a "-dirty" flag into the library), and `vcsInfo.include = false`
for the APK. `scripts/verify-reproducible.sh` repeats the test; run it before a release. What has **not** been tested:
a different machine or operating system, a different NDK or JDK build than the pinned ones, and F-Droid's own build
server. A signed APK is the unsigned one plus a signature, so compare *unsigned* files (or use `apksigcopier`).

## Building a release

```sh
source env.sh
scripts/check.sh
./gradlew assembleRelease          # app/build/outputs/apk/release/
```

To try a release build on a phone without replacing the real app, use `./gradlew assembleReleaseCheck`: it is the same
shrunk build under the application ID `be.sequr.voicenotes.releasecheck`, signed with the debug key, installed next to the
real app with its own data.

## Publishing (planned)

1. Tag and push to Tangled (`git push origin main --follow-tags`).
2. The GitHub mirror (optional) builds, signs from repository secrets and attaches `voicenotes-X.Y.Z.apk` and its
   SHA-256 to a release; Obtainium follows that release page. See `.github/workflows/release.yml` (draft).
3. F-Droid (optional): the recipe in `docs/fdroid/` goes to F-Droid's data repository; F-Droid builds from the tag.

## Checklist before a release

- [ ] `scripts/check.sh` passes; dependency updates reviewed (`gradle/verification-metadata.xml` changes are read, not just accepted)
- [ ] on a real phone: recording from the tile and the in-app button, a transcription in each preferred language, silence, a
      long note, share and export, Android Auto on the Desktop Head Unit
- [ ] `CHANGELOG.md`, the store changelog and `PRIVACY.md` match what the release does
- [ ] the signed APK installs over the previous release (same signing key) without losing notes

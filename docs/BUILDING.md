# Building Voice Notes

## What you need

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (Temurin 17.0.20.1 used) | newer JDKs do not work with the pinned Android Gradle Plugin |
| Android SDK | platform 35, build-tools 35.0.0, platform-tools | command-line tools 11076708 |
| Android NDK | 27.2.12479018 | the native speech code is built with CMake |
| CMake | 3.22.1 | from the SDK manager |
| Gradle | 8.9 | the wrapper downloads it and checks its SHA-256 |
| git | any | the whisper.cpp source is a submodule |

On Linux x86_64 `scripts/setup-toolchain.sh` installs the JDK and the SDK pieces into `~/toolchain` (override with
`TOOLCHAIN_DIR`) and verifies every download against a checksum written in the script. You must accept the Android SDK
licences yourself (`ACCEPT_ANDROID_SDK_LICENSES=yes`). On other systems install the same versions yourself (Android
Studio can install the SDK, NDK and CMake) and set `JAVA_HOME` and `ANDROID_HOME`.

## Steps

```sh
git clone --recurse-submodules https://tangled.org/sequr.be/voicenotes voicenotes
cd voicenotes
scripts/setup-third-party.sh      # checks the whisper.cpp version and applies our patch (safe to repeat)
scripts/setup-toolchain.sh        # once; Linux x86_64 only
source env.sh                     # in every new shell
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
scripts/check.sh                  # lint, unit tests and the privacy guards
```

If Gradle cannot download its distribution with a certificate error, your Java does not trust the site's certificate
authority; `env.sh` points Java at the Debian system store when it exists. Do not disable certificate checking.

## Speech models

The APK contains no speech model except the small voice-activity detector. To transcribe you need one of the models in
`ModelCatalog.kt`: download it in the app (*Models* screen, needs network access and about 0.5 to 0.9 GB), or fetch it
yourself and import it from a file. The catalog lists the exact files, sizes and SHA-256 checksums. For development
you can also copy a model into a debug build with adb:

```sh
adb push ggml-small.bin /data/local/tmp/
adb shell "run-as be.sequr.voicenotes sh -c 'mkdir -p files/models && cp /data/local/tmp/ggml-small.bin files/models/' ; rm /data/local/tmp/ggml-small.bin"
```

## Testing on a phone

Enable *Wireless debugging* (or USB debugging) and `adb install -r app/build/outputs/apk/debug/app-debug.apk`. To try a
shrunk release build without touching the real app, use `./gradlew assembleReleaseCheck`: it installs next to it as
`be.sequr.voicenotes.releasecheck` with its own data. Android
Auto can be tried without a car with the Desktop Head Unit: `WITH_DHU=yes scripts/setup-toolchain.sh`, then
`scripts/dhu.sh start` (see the comments in that script for the phone-side steps).

## Native code

`app/src/main/cpp` builds whisper.cpp (from `third_party/whisper.cpp`, pinned to v1.9.5 plus
`third_party/patches/0001-whisper-set-audio-ctx.patch`) and a small JNI library. The build refuses to start if the
patch is missing. ggml builds the processor-specific code as several backend libraries (`libggml-cpu-android_*.so`)
and the JNI code (`whisper_jni.cpp`, `nativeLoadBackends`) loads the best one the phone's processor supports, chosen from
the capabilities the kernel reports: i8mm, then dot-product with half-precision floats, then dot-product, then the
baseline. ggml's own automatic choice picks an SVE2 variant on a Pixel 10, which is about twice as slow, so it is not
used. The libraries must be extracted at install time (`useLegacyPackaging`), because ggml loads them by path.

## Reproducibility

The release APK built from scratch should be byte-identical wherever it is built with the pinned tools (incremental builds can differ, so release with `./gradlew clean assembleRelease`); `scripts/verify-reproducible.sh` checks
that (see `docs/RELEASING.md`). Do not add anything to the build that embeds a time, a path, a host name or a git state.

## Releases

Signing, the release process and the reproducibility check are in [RELEASING.md](RELEASING.md).

<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Questions for the F-Droid team (draft)

To: team@f-droid.org (or a request-for-packaging issue in fdroiddata; replace the repository URL once it exists)

Subject: Voice Notes (be.sequr.voicenotes): three packaging questions before I submit

Hello,

I'd like to submit Voice Notes, an offline voice-note app (GPL-3.0-or-later, source: https://tangled.org/sequr.be/voicenotes). It transcribes
on the device with whisper.cpp (a git submodule, built with the NDK). Draft recipe: `docs/fdroid/be.sequr.voicenotes.yml`.
Three questions:

1. **Bundled model file.** The APK contains one 885 KB binary data file, the Silero VAD v5.1.2 speech detector (MIT,
   converted for whisper.cpp, checksum pinned, taken from <https://huggingface.co/ggml-org/whisper-vad>). It is not
   built from source in our tree. Is that acceptable, or must it be fetched and converted during the build?
2. **Model downloads.** On request, the app downloads Whisper speech models (MIT) from huggingface.co. HTTPS only,
   pinned revision and SHA-256, no other network use, no tracking. Which anti-feature, if any, applies (NonFreeNet?)?
3. **Build server.** The build needs NDK 27.2.12479018 and CMake 3.22.1. Are both available, or should the recipe
   install them? Clean builds are byte-identical across machines on our side (`scripts/verify-reproducible.sh`),
   so we would like to enable reproducible-build verification.

Thanks,
Sequr

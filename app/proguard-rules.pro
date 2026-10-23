# SPDX-License-Identifier: GPL-3.0-or-later
# Rules for the release build (code shrinking). The default Android rules already keep native method names and
# the classes the manifest names. Add a rule here only together with the failure it fixes.

# JNI: the native library looks up these Kotlin methods by name (Java_be_sequr_voicenotes_WhisperBridge_*).
-keep class be.sequr.voicenotes.WhisperBridge { native <methods>; }

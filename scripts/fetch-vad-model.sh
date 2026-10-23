#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Re-downloads the Silero voice-activity-detection model bundled in the app and checks it against the committed copy,
# so anyone can see that app/src/main/assets/ggml-silero-v5.1.2.bin is the unmodified upstream file.
# Source: https://huggingface.co/ggml-org/whisper-vad (the model is MIT licensed, (c) 2020-present Silero Team).
set -euo pipefail
cd "$(dirname "$0")/.."
FILE=app/src/main/assets/ggml-silero-v5.1.2.bin
SHA256=29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
curl -fsSL "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin" -o "$TMP"
echo "$SHA256  $TMP" | sha256sum -c --quiet - && echo "upstream download matches the pinned checksum"
echo "$SHA256  $FILE" | sha256sum -c --quiet - && echo "committed model matches the pinned checksum"

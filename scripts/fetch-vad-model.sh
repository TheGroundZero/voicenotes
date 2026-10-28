#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The speech-check model (Silero VAD, MIT, (c) 2020-present Silero Team) is not part of the app: the app downloads it from
# Hugging Face, like the speech models, at the revision and with the checksum pinned in ModelCatalog.kt. This script reads those
# pins, downloads the same file and checks its size and SHA-256, so anyone can see the catalog entry is the genuine upstream file.
#   scripts/fetch-vad-model.sh [DEST]      download, verify, keep at DEST (default models/ggml-silero-v5.1.2.bin, used by the benchmark)
#   scripts/fetch-vad-model.sh --offline   only check that the pins in the catalog are well formed (no network; used by check.sh)
set -euo pipefail
cd "$(dirname "$0")/.."
CATALOG=app/src/main/kotlin/be/sequr/voicenotes/ModelCatalog.kt
# The speechCheck entry of the catalog, in plain bash: the CI image has no sed or awk.
block=""; inside=0
while IFS= read -r line; do
  [[ "$line" == *"val speechCheck = ModelSpec("* ]] && inside=1
  if (( inside )); then block+="$line"$'\n'; [[ "$line" == "    )" ]] && break; fi
done < "$CATALOG"
[ -n "$block" ] || { echo "FAILED: no speechCheck entry in $CATALOG"; exit 1; }
URL="$(grep -o 'url = "[^"]*"' <<<"$block" | cut -d'"' -f2)"
SHA256="$(grep -o 'sha256 = "[^"]*"' <<<"$block" | cut -d'"' -f2)"
SIZE="$(grep -o 'sizeBytes = [0-9_]*' <<<"$block" | grep -o '[0-9_]*$' | tr -d _)"
[[ "$URL" =~ ^https://huggingface\.co/ggml-org/whisper-vad/resolve/[0-9a-f]{40}/ggml-silero-v5\.1\.2\.bin$ ]] || { echo "FAILED: the catalog URL is not pinned to a revision: $URL"; exit 1; }
[[ "$SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "FAILED: the catalog SHA-256 is malformed: $SHA256"; exit 1; }
[[ "$SIZE" =~ ^[0-9]+$ ]] || { echo "FAILED: the catalog size is malformed: $SIZE"; exit 1; }
if [ "${1:-}" = "--offline" ]; then echo "ok (pins well formed)"; exit 0; fi
DEST="${1:-models/ggml-silero-v5.1.2.bin}"
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
curl -fsSL "$URL" -o "$TMP"
[ "$(stat -c %s "$TMP")" = "$SIZE" ] || { echo "FAILED: the download has another size than the catalog says"; exit 1; }
echo "$SHA256  $TMP" | sha256sum -c --quiet - || { echo "FAILED: the download does not match the catalog checksum"; exit 1; }
mkdir -p "$(dirname "$DEST")"; mv "$TMP" "$DEST"; trap - EXIT
echo "upstream file matches the catalog pins; saved to $DEST"

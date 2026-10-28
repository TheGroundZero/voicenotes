#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the command-line benchmark tool (benchmark/vn_bench.cpp) for this machine into build-vn-bench/.
# Needs cmake, ninja (or make) and a C++ compiler; the Android SDK's cmake in ~/toolchain is used if there is none in PATH.
#   scripts/benchmark-build.sh
#   build-vn-bench/vn-bench --model models/ggml-small.bin --vad models/ggml-silero-v5.1.2.bin clip.wav   (scripts/fetch-vad-model.sh downloads that file)
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/setup-third-party.sh >/dev/null
sdk_cmake="${ANDROID_HOME:-$HOME/toolchain/android-sdk}/cmake/3.22.1/bin"
if ! command -v cmake >/dev/null && [ -x "$sdk_cmake/cmake" ]; then export PATH="$sdk_cmake:$PATH"; fi
generator=()
if command -v ninja >/dev/null; then generator=(-G Ninja); fi
cmake -S benchmark -B build-vn-bench "${generator[@]}" -DCMAKE_BUILD_TYPE=Release
cmake --build build-vn-bench --target vn-bench -j "$(nproc)"
echo "built: build-vn-bench/vn-bench"

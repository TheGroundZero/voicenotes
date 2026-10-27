#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs a speech model through the benchmark (docs/BENCHMARK.md): builds the command-line tool and the corpus if they are missing,
# then scores the model and writes benchmark/results/<date>-<model>.json.
#   scripts/benchmark.sh models/ggml-small.bin              # the whole corpus (hours for the big models)
#   scripts/benchmark.sh models/ggml-small.bin --smoke      # the 40-clip subset first (minutes)
#   python3 benchmark/compare.py benchmark/results/A.json benchmark/results/B.json   # the verdict against a default
set -euo pipefail
cd "$(dirname "$0")/.."
model="${1:?usage: scripts/benchmark.sh MODEL.bin [--smoke] [other run_benchmark.py options]}"; shift
smoke=()
if [ "${1:-}" = "--smoke" ]; then smoke=(--smoke); shift; fi
[ -x build-vn-bench/vn-bench ] || scripts/benchmark-build.sh
if [ ! -f benchmark/work/corpus/manifest.jsonl ]; then python3 benchmark/make_corpus.py "${smoke[@]}"; fi
python3 benchmark/run_benchmark.py --model "$model" "$@"

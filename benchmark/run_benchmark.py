#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Runs one speech model over the corpus with vn-bench (the app's own transcription code), scores every clip and writes
benchmark/results/<date>-<model>.json plus a Markdown summary on stdout. See docs/BENCHMARK.md. Standard library only.

  python3 benchmark/run_benchmark.py --model models/ggml-small.bin [--corpus benchmark/work/corpus] [--audio-ctx auto|full|N]

The non-speech clips are run twice: with the speech check (what a user sees) and without it (the model alone)."""
import argparse
import datetime
import hashlib
import json
import pathlib
import platform
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent
sys.path.insert(0, str(ROOT))
from vnbench import report  # noqa: E402


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 22), b""):
            h.update(block)
    return h.hexdigest()


def run_bench(args, files, with_vad):
    command = [str(REPO / "build-vn-bench" / "vn-bench"), "--model", args.model, "--language", args.language,
               "--preferred", args.preferred, "--threads", str(args.threads), "--audio-ctx", args.audio_ctx]
    if with_vad:
        command += ["--vad", args.vad]
    done = subprocess.run(command + files, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if done.returncode != 0:
        sys.exit("vn-bench failed:\n%s" % done.stderr[-2000:])
    model, clips, summary = None, {}, None
    for line in done.stdout.splitlines():
        if not line.startswith("{"):
            continue
        j = json.loads(line)
        if j["type"] == "model":
            model = j
        elif j["type"] == "clip":
            clips[pathlib.Path(j["file"]).name] = j
        elif j["type"] == "summary":
            summary = j
    return model, clips, summary


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--model", required=True)
    p.add_argument("--id", help="name in the results (default: the file name without ggml- and .bin)")
    p.add_argument("--corpus", default=str(ROOT / "work" / "corpus"))
    p.add_argument("--vad", default=str(REPO / "app" / "src" / "main" / "assets" / "ggml-silero-v5.1.2.bin"))
    p.add_argument("--language", default="auto")
    p.add_argument("--preferred", default="en,nl,fr")
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--audio-ctx", default="auto")
    p.add_argument("--out", default=str(ROOT / "results"))
    p.add_argument("--limit", type=int)
    args = p.parse_args()
    if not (REPO / "build-vn-bench" / "vn-bench").exists():
        sys.exit("build the tool first: scripts/benchmark-build.sh")

    corpus = pathlib.Path(args.corpus)
    manifest = [json.loads(line) for line in open(corpus / "manifest.jsonl", encoding="utf-8")]
    if args.limit:
        manifest = manifest[:args.limit]
    by_file = {c["file"]: c for c in manifest}
    files = [str(corpus / c["file"]) for c in manifest]

    model_line, clips, summary = run_bench(args, files, with_vad=True)
    rows = []
    for name, clip in by_file.items():
        if name in clips and "result" in clips[name]:
            rows.append(report.clip_row(clip, clips[name]["result"], clips[name]["audioSeconds"]))
    raw_rows = []
    quiet = [str(corpus / c["file"]) for c in manifest if c["kind"] == "non-speech"]
    if quiet:
        _, raw, _ = run_bench(args, quiet, with_vad=False)
        raw_rows = [report.clip_row(by_file[n], j["result"], j["audioSeconds"]) for n, j in raw.items() if "result" in j]
    result_summary = report.summarize(rows, raw_rows)

    model_id = args.id or pathlib.Path(args.model).name.removeprefix("ggml-").removesuffix(".bin")
    stamp = datetime.date.today().isoformat()
    suffix = "" if args.audio_ctx == "auto" else "-ctx-" + args.audio_ctx
    out = {
        "schema": 1, "date": stamp,
        "model": {"id": model_id, "file": pathlib.Path(args.model).name, "bytes": pathlib.Path(args.model).stat().st_size, "sha256": sha256_file(args.model)},
        "engine": "whisper.cpp (vn-bench, the app's transcription core)",
        "settings": {"language": args.language, "preferred": args.preferred, "threads": args.threads, "audioCtx": args.audio_ctx, "beamSize": 5},
        "host": {"machine": platform.machine(), "system": platform.platform(), "python": platform.python_version()},
        "corpus": json.loads((corpus / "corpus.json").read_text()) if (corpus / "corpus.json").exists() else {},
        "loadSeconds": model_line["loadSeconds"], "peakMemoryKb": summary["peakMemoryKb"],
        "summary": result_summary, "clips": rows, "rawNonSpeech": raw_rows,
    }
    pathlib.Path(args.out).mkdir(parents=True, exist_ok=True)
    path = pathlib.Path(args.out) / ("%s-%s%s.json" % (stamp, model_id, suffix))
    path.write_text(json.dumps(out, indent=1, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")

    s = result_summary
    pct = lambda v: "n/a" if v is None else "%.1f %%" % (100 * v)
    print("# %s (%d clips, audio window %s)" % (model_id, s["clips"], args.audio_ctx))
    print("- weighted word error rate: **%s**; character error rate %s; language identification %s" % (pct(s["weightedWer"]), pct(s["charErrorRate"]), pct(s["languageAccuracy"])))
    print("- non-speech clips with any output: %s with the speech check, %s without it; repetition loops %d (raw: %s)" % (
        pct(s["hallucinationRateGated"]), pct(s.get("hallucinationRateRaw")), s["repetitionLoops"], s.get("repetitionLoopsRaw", "n/a")))
    print("- real-time factor on this machine %.2f, load %.1f s, peak memory %.0f MB" % (s["realTimeFactor"], model_line["loadSeconds"], summary["peakMemoryKb"] / 1024))
    print("\n| cell | WER |\n|---|---|")
    for cell, v in s["werByCell"].items():
        print("| %s | %s |" % (cell, pct(v)))
    print("\nwritten: %s" % path)


if __name__ == "__main__":
    main()

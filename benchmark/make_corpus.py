#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Builds the benchmark corpus (docs/BENCHMARK.md) into benchmark/work/corpus: one 16 kHz mono WAV per clip of the plan in
vnbench/corpus.py, and manifest.jsonl with the clip, its duration and the SHA-256 of its audio.

  python3 benchmark/make_corpus.py --smoke            # the 40-clip subset, a few minutes (downloads ~700 MB of voices once)
  python3 benchmark/make_corpus.py                    # everything (886 clips)

Piper (MIT, archived release 2023.11.14-2) and the voices are downloaded into benchmark/cache (ignored by git), checked against
the SHA-256 pinned here and in benchmark/voices.tsv, and Piper runs offline. Standard library only."""
import argparse
import hashlib
import json
import multiprocessing
import os
import pathlib
import subprocess
import sys
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from vnbench import audio, build, corpus  # noqa: E402

CACHE = ROOT / "cache"
PIPER_URL = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz"
PIPER_SHA256 = "a50cb45f355b7af1f6d758c1b360717877ba0a398cc8cbe6d2a7a3a26e225992"   # recorded at the first download
PIPER = CACHE / "piper" / "piper" / "piper"
VOICE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/%s/%s"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def download(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "voicenotes-benchmark"})
    with urllib.request.urlopen(request, timeout=120) as response, open(partial, "wb") as out:
        for block in iter(lambda: response.read(1 << 20), b""):
            out.write(block)
    partial.rename(target)


def ensure_piper():
    tarball = CACHE / "piper-dl" / "piper_linux_x86_64.tar.gz"
    if not tarball.exists():
        print("downloading Piper ...", flush=True)
        download(PIPER_URL, tarball)
    if sha256_file(tarball) != PIPER_SHA256:
        sys.exit("Piper archive does not match the pinned SHA-256: %s" % tarball)
    if not PIPER.exists():
        with tarfile.open(tarball) as t:
            for member in t.getmembers():
                if not member.name.startswith("piper/") or ".." in pathlib.PurePosixPath(member.name).parts:
                    sys.exit("unexpected path in the Piper archive: %s" % member.name)
            t.extractall(CACHE / "piper", filter="data")
    return PIPER


def ensure_voice(voice):
    model = CACHE / "voices" / (voice["id"] + ".onnx")
    config = model.with_name(model.name + ".json")
    if not model.exists() or sha256_file(model) != voice["sha256"]:
        print("downloading voice %s (%d MB) ..." % (voice["id"], voice["bytes"] // 1_000_000), flush=True)
        download(VOICE_URL % (voice["path"], voice["id"] + ".onnx"), model)
        if sha256_file(model) != voice["sha256"]:
            model.unlink()
            sys.exit("voice %s does not match its pinned SHA-256" % voice["id"])
    if not config.exists():
        download(VOICE_URL % (voice["path"], voice["id"] + ".onnx.json"), config)
    return model, config


def tts_key(voice, speaker, length_scale, text):
    return hashlib.sha256(("%s|%s|%s|%s" % (voice, speaker, length_scale, text)).encode()).hexdigest()[:20]


def clip_key(clip):
    return tts_key(clip["voice"], clip["speaker"], clip["length_scale"], clip["reference"])


def synthesize(jobs, voices_by_id):
    """jobs: {key: (voice id, speaker, length_scale, text)} of the ones still missing. One Piper run per voice and speed."""
    groups = {}
    for key, (voice, speaker, scale, text) in jobs.items():
        groups.setdefault((voice, speaker, scale), []).append((key, text))
    (CACHE / "tts22").mkdir(parents=True, exist_ok=True)
    for (voice, speaker, scale), items in sorted(groups.items(), key=lambda kv: str(kv[0])):
        model, _ = ensure_voice(voices_by_id[voice])
        lines = []
        for key, text in items:
            entry = {"text": text, "output_file": str(CACHE / "tts22" / (key + ".wav"))}
            if speaker is not None:
                entry["speaker_id"] = speaker
            lines.append(json.dumps(entry))
        print("speaking %d sentences with %s%s, speed %s" % (len(items), voice, "" if speaker is None else " speaker %s" % speaker, scale), flush=True)
        done = subprocess.run([str(ensure_piper()), "--model", str(model), "--json-input", "--length_scale", str(scale)],
                              input="\n".join(lines) + "\n", capture_output=True, text=True)
        if done.returncode != 0:
            sys.exit("Piper failed for %s:\n%s" % (voice, done.stderr[-2000:]))


def _to_16k(args):
    key, rate = args
    source = CACHE / "tts22" / (key + ".wav")
    samples, source_rate = audio.read_wav(str(source))
    (CACHE / "tts16").mkdir(parents=True, exist_ok=True)
    audio.write_wav(str(CACHE / "tts16" / (key + ".wav")), audio.resample(samples, source_rate, audio.SAMPLE_RATE))


def _load16(key):
    return audio.read_wav(str(CACHE / "tts16" / (key + ".wav")))[0]


def _compose(task):
    clip, own_key, source_keys, seed, out_dir = task
    sources = [_load16(k) for k in source_keys]
    if clip["kind"] == "speech":
        samples = build.compose_speech(clip, _load16(own_key), sources, seed)
    else:
        samples = build.compose_non_speech(clip, sources, seed)
    path = pathlib.Path(out_dir) / (clip["id"] + ".wav")
    audio.write_wav(str(path), samples)
    record = dict(clip, file=path.name, seconds=round(len(samples) / audio.SAMPLE_RATE, 3), audioSha256=sha256_file(path),
                  ttsKey=own_key, babbleKeys=source_keys)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--smoke", action="store_true", help="only the 40-clip smoke subset")
    parser.add_argument("--limit", type=int, help="only the first N clips (for trying things out)")
    parser.add_argument("--out", default=str(ROOT / "work" / "corpus"))
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 1)
    parser.add_argument("--seed", type=int, default=corpus.DEFAULT_SEED)
    args = parser.parse_args()

    voices = corpus.load_voices(ROOT / "voices.tsv")
    voices_by_id = {v["id"]: v for v in voices}
    sentences = {lang: corpus.load_sentences(ROOT / "sentences" / ("%s.tsv" % lang)) for lang in corpus.LANGUAGES}
    plan = corpus.plan(sentences, voices, seed=args.seed)
    clips = corpus.smoke_subset(plan) if args.smoke else plan
    if args.limit:
        clips = clips[:args.limit]

    # speech for the babble: other clean renditions, same language for speech clips, any language for the others
    renditions = {}                                           # key -> (language, voice, speaker, scale, text) of every rendition 0
    for c in plan:
        if c["kind"] == "speech" and c["rendition"] == 0 and c["category"] not in ("oneword-padded", "oneword"):
            renditions[clip_key(c)] = (c["language"], c["voice"], c["speaker"], c["length_scale"], c["reference"])
    pool = sorted(renditions)
    tasks, needed = [], {}
    for c in clips:
        own = clip_key(c) if c["kind"] == "speech" else None
        if own:
            needed[own] = (c["voice"], c["speaker"], c["length_scale"], c["reference"])
        candidates = [k for k in pool if k != own and (c["kind"] != "speech" or renditions[k][0] == c["language"])]
        rng = build.rng_for(args.seed, c["id"] + "|babble")
        sources = sorted(rng.sample(candidates, 4)) if (c["kind"] == "non-speech" and c["recipe"] in ("babble", "tv")) or c.get("noise") == "babble" else []
        for k in sources:
            _, voice, speaker, scale, text = renditions[k]
            needed[k] = (voice, speaker, scale, text)
        tasks.append((c, own, sources, args.seed, args.out))

    missing = {k: v for k, v in needed.items() if not (CACHE / "tts22" / (k + ".wav")).exists()}
    print("%d clips, %d renditions needed, %d to speak" % (len(clips), len(needed), len(missing)), flush=True)
    if missing:
        synthesize(missing, voices_by_id)
    todo = [(k, None) for k in needed if not (CACHE / "tts16" / (k + ".wav")).exists()]
    with multiprocessing.Pool(args.jobs) as pool_:
        if todo:
            print("resampling %d renditions to 16 kHz" % len(todo), flush=True)
            pool_.map(_to_16k, todo)
        pathlib.Path(args.out).mkdir(parents=True, exist_ok=True)
        print("composing %d clips with %d processes" % (len(tasks), args.jobs), flush=True)
        records = pool_.map(_compose, tasks, chunksize=4)

    out = pathlib.Path(args.out)
    with open(out / "manifest.jsonl", "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    summary = {"seed": args.seed, "clips": len(records), "piperSha256": PIPER_SHA256, "python": sys.version.split()[0],
               "voices": {v["id"]: {"onnxSha256": v["sha256"], "licence": v["licence"], "dataset": v["dataset"]} for v in voices
                          if any(r.get("voice") == v["id"] for r in records)}}
    (out / "corpus.json").write_text(json.dumps(summary, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    hours = sum(r["seconds"] for r in records) / 3600
    print("done: %d clips, %.2f hours of audio in %s" % (len(records), hours, out))


if __name__ == "__main__":
    main()

# SPDX-License-Identifier: GPL-3.0-or-later
"""The plan of the benchmark corpus: which clips exist. Pure and deterministic (no audio is made here), so the same
sentences, voices and seed always give the same list, and the plan can be tested. docs/BENCHMARK.md explains the layers."""
import hashlib

LANGUAGES = ("en", "nl", "fr")
CONDITIONS = (("clean", None), ("snr20", 20), ("snr10", 10), ("snr5", 5))
NOISES = ("rumble", "pink", "babble")
LENGTH_SCALES = (1.0, 0.85)          # Piper: above 1 is slower; the second rendition is spoken about 15 % faster
DEFAULT_SEED = 20261027


def load_sentences(path):
    """[{"id", "category", "text"}] from a tab-separated sentence file (lines starting with # are comments)."""
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            sentence_id, category, text = line.rstrip("\n").split("\t")
            rows.append({"id": sentence_id, "category": category, "text": text})
    return rows


def load_voices(path):
    """[{"id", "language", "path", "quality", "rate", "speakers" (list of speaker ids or [None]), "licence", ...}]."""
    voices = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            c = line.rstrip("\n").split("\t")
            voices.append({
                "id": c[0], "language": c[1], "path": c[2], "quality": c[3], "rate": int(c[4]),
                "speakers": [None] if c[5] == "-" else [int(x) for x in c[5].split(",")],
                "licence": c[6], "dataset": c[7], "bytes": int(c[8]), "sha256": c[9],
            })
    return voices


def voice_slots(voices, lang):
    """Every (voice id, speaker id) available for a language, in file order."""
    return [(v["id"], s) for v in voices if v["language"] == lang for s in v["speakers"]]


def _pick(seed, *parts, count):
    digest = hashlib.sha256(("%d|%s" % (seed, "|".join(map(str, parts)))).encode()).digest()
    return int.from_bytes(digest[:4], "big") % count


# Clips without speech: the model must write nothing. level_dbfs is the rms level of the noise itself.
NON_SPEECH = (
    [{"id": "ns-silence-%02ds" % s, "recipe": "silence", "seconds": s} for s in (4, 8, 15)]
    + [{"id": "ns-%s-%ddb-%02ds" % (kind, -level, s), "recipe": kind, "seconds": s, "level_dbfs": level}
       for kind in ("rumble", "pink") for level, s in ((-45, 6), (-35, 8), (-25, 12))]
    + [{"id": "ns-babble-%ddb" % -level, "recipe": "babble", "seconds": 10, "level_dbfs": level} for level in (-35, -25)]
    + [{"id": "ns-tv-rumble", "recipe": "tv", "seconds": 12, "level_dbfs": -30}]            # speech nobody wants over a rumble
    + [{"id": "ns-rumble-long", "recipe": "rumble", "seconds": 35, "level_dbfs": -35},      # the full-window path
       {"id": "ns-pink-long", "recipe": "pink", "seconds": 35, "level_dbfs": -35},
       {"id": "ns-rumble-short", "recipe": "rumble", "seconds": 2, "level_dbfs": -30},      # short noisy notes are where loops start
       {"id": "ns-pink-short", "recipe": "pink", "seconds": 3, "level_dbfs": -30}]
)


def plan(sentences_by_lang, voices, seed=DEFAULT_SEED, renditions=2):
    """The list of clips (dicts). Speech clips: every sentence, `renditions` times with different voices and speeds, each in
    every condition (clean and three signal-to-noise ratios). Then the clips without speech, and each one-word sentence once
    with a long silence around it."""
    clips = []
    for lang in LANGUAGES:
        slots = voice_slots(voices, lang)
        if not slots:
            raise ValueError("no voices for %s" % lang)
        for index, sentence in enumerate(sentences_by_lang[lang]):
            for r in range(renditions):
                voice, speaker = slots[(index + r * (len(slots) // 2 + 1)) % len(slots)]
                noise = NOISES[_pick(seed, sentence["id"], r, count=len(NOISES))]
                for condition, snr in CONDITIONS:
                    clips.append({
                        "id": "%s-r%d-%s" % (sentence["id"], r, condition), "kind": "speech", "language": lang,
                        "sentence": sentence["id"], "category": sentence["category"], "reference": sentence["text"],
                        "voice": voice, "speaker": speaker, "length_scale": LENGTH_SCALES[r % len(LENGTH_SCALES)],
                        "rendition": r, "condition": condition, "snr_db": snr, "noise": noise,
                    })
            if sentence["category"] == "oneword":
                voice, speaker = slots[index % len(slots)]
                clips.append({
                    "id": "%s-padded" % sentence["id"], "kind": "speech", "language": lang, "sentence": sentence["id"],
                    "category": "oneword-padded", "reference": sentence["text"], "voice": voice, "speaker": speaker,
                    "length_scale": 1.0, "rendition": 0, "condition": "clean", "snr_db": None, "noise": None,
                    "pad_silence_seconds": 12,
                })
    for spec in NON_SPEECH:
        clips.append(dict(spec, kind="non-speech", language=None, category="non-speech", condition=None, reference=""))
    ids = [c["id"] for c in clips]
    if len(ids) != len(set(ids)):
        raise ValueError("clip ids are not unique")
    return clips


def smoke_subset(clips, per_language=6):
    """A small deterministic subset for quick runs: the first sentences clean and at 5 dB, and a few non-speech clips."""
    chosen = []
    for lang in LANGUAGES:
        speech = [c for c in clips if c["kind"] == "speech" and c["language"] == lang and c["rendition"] == 0 and c["category"] != "oneword-padded"]
        sentences = []
        for c in speech:
            if c["sentence"] not in sentences:
                sentences.append(c["sentence"])
        for sentence in sentences[:per_language]:
            chosen += [c for c in speech if c["sentence"] == sentence and c["condition"] in ("clean", "snr5")]
    chosen += [c for c in clips if c["id"] in ("ns-silence-08s", "ns-rumble-35db-08s", "ns-rumble-short", "ns-pink-long")]
    return chosen

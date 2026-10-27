# SPDX-License-Identifier: GPL-3.0-or-later
"""Turns the per-clip results of one model into the numbers of docs/BENCHMARK.md. A clip row is a plain dict (see clip_row)."""
from collections import defaultdict

from . import metrics


def clip_row(clip, result, audio_seconds):
    """One scored row from a manifest clip and the JSON `result` vn-bench printed for it."""
    row = {
        "id": clip["id"], "kind": clip["kind"], "language": clip.get("language"), "category": clip["category"],
        "condition": clip.get("condition"), "text": result["text"], "noSpeech": result["noSpeech"],
        "detectedLanguage": result["language"], "audioSeconds": audio_seconds,
        "vadSeconds": result["vadSeconds"], "detectSeconds": result["detectSeconds"],
        "transcribeSeconds": result["transcribeSeconds"], "audioCtx": result["audioCtx"],
        "repetition": metrics.has_repetition(result["text"]),
    }
    if clip["kind"] == "speech":
        row["edits"], row["refLen"] = metrics.word_errors(clip["reference"], result["text"], clip["language"])
        row["charEdits"], row["charLen"] = metrics.char_errors(clip["reference"], result["text"], clip["language"])
        row["languageOk"] = result["language"] == clip["language"]
    else:
        row["hallucination"] = metrics.is_hallucination(result["text"])
    return row


def cells(rows):
    """{(language, condition): {clip id: (edits, reference length)}} over the speech clips."""
    out = defaultdict(dict)
    for r in rows:
        if r["kind"] == "speech":
            out[(r["language"], r["condition"])][r["id"]] = (r["edits"], r["refLen"])
    return out


def cell_weights(cell_keys):
    """Equal weight per language and per condition (docs/BENCHMARK.md): a cell weighs 1 / (languages * conditions)."""
    languages = {k[0] for k in cell_keys}
    conditions = {k[1] for k in cell_keys}
    return {k: 1.0 / (len(languages) * len(conditions)) for k in cell_keys}


def weighted_wer(cell_data):
    rates = {k: metrics.micro_rate(list(v.values())) for k, v in cell_data.items()}
    rates = {k: r for k, r in rates.items() if r is not None}
    return metrics.weighted_mean(rates, cell_weights(rates)), rates


def summarize(rows, raw_rows=None):
    """The headline numbers of one run. `raw_rows` are the non-speech clips run again without the speech check."""
    cell_data = cells(rows)
    overall, rates = weighted_wer(cell_data) if cell_data else (None, {})
    by_category = defaultdict(list)
    for r in rows:
        if r["kind"] == "speech":
            by_category[r["category"]].append((r["edits"], r["refLen"]))
    non_speech = [r for r in rows if r["kind"] == "non-speech"]
    speech = [r for r in rows if r["kind"] == "speech"]
    audio = sum(r["audioSeconds"] for r in rows)
    work = sum(r["vadSeconds"] + r["detectSeconds"] + r["transcribeSeconds"] for r in rows)
    summary = {
        "weightedWer": overall,
        "werByCell": {"%s/%s" % k: v for k, v in sorted(rates.items())},
        "werByCategory": {k: metrics.micro_rate(v) for k, v in sorted(by_category.items())},
        "charErrorRate": metrics.micro_rate([(r["charEdits"], r["charLen"]) for r in speech]) if speech else None,
        "languageAccuracy": metrics.language_accuracy([(True, r["languageOk"]) for r in speech]) if speech else None,
        "hallucinationRateGated": (sum(r["hallucination"] for r in non_speech) / len(non_speech)) if non_speech else None,
        "repetitionLoops": sum(1 for r in rows if r["repetition"]),
        "realTimeFactor": work / audio if audio else None,
        "clips": len(rows),
    }
    if raw_rows:
        summary["hallucinationRateRaw"] = sum(1 for r in raw_rows if r["hallucination"]) / len(raw_rows)
        summary["repetitionLoopsRaw"] = sum(1 for r in raw_rows if r["repetition"])
    return summary

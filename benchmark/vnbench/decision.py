# SPDX-License-Identifier: GPL-3.0-or-later
"""The decision rule of docs/BENCHMARK.md as code, so it is applied the way it was written down before any result existed.
Change the rule only by changing the page first."""
import random

from . import report

MIN_RELATIVE_IMPROVEMENT = 0.10
WORSE_BY = 0.01                       # one percentage point of word error rate, in one language-and-condition cell
LANGUAGE_ID_TOLERANCE = 0.01
MAX_REAL_TIME_FACTOR = 1.5
MAX_PEAK_MEMORY_GB = 2.0
MAX_DOWNLOAD_GB = 1.0


def _cell_rate(rows, picks):
    edits = sum(rows[i][0] for i in picks)
    length = sum(rows[i][1] for i in picks)
    return edits / length if length else None


def paired_cells(default_rows, candidate_rows):
    """For each cell present in both runs: two aligned lists of (edits, length) over the clips both runs scored."""
    default_cells, candidate_cells = report.cells(default_rows), report.cells(candidate_rows)
    out = {}
    for key in sorted(set(default_cells) & set(candidate_cells)):
        ids = sorted(set(default_cells[key]) & set(candidate_cells[key]))
        if ids:
            out[key] = ([default_cells[key][i] for i in ids], [candidate_cells[key][i] for i in ids])
    return out


def bootstrap(pairs, resamples=10_000, seed=20261026, confidence=0.95):
    """Clips are resampled with replacement inside every cell. Returns the weighted difference candidate - default of the
    word error rate (and its interval), the same difference per cell with intervals, and both weighted rates."""
    weights = report.cell_weights(list(pairs))
    rng = random.Random(seed)
    weighted_diffs = []
    cell_diffs = {k: [] for k in pairs}
    for _ in range(resamples):
        total = 0.0
        for key, (d, c) in pairs.items():
            picks = [rng.randrange(len(d)) for _ in d]
            rate_d, rate_c = _cell_rate(d, picks), _cell_rate(c, picks)
            if rate_d is None or rate_c is None:
                continue
            cell_diffs[key].append(rate_c - rate_d)
            total += weights[key] * (rate_c - rate_d)
        weighted_diffs.append(total)

    def interval(values):
        values = sorted(values)
        return values[int((1 - confidence) / 2 * len(values))], values[min(len(values) - 1, int((1 + confidence) / 2 * len(values)))]

    def point(index):
        rates = {k: _cell_rate(v[index], range(len(v[index]))) for k, v in pairs.items()}
        return sum(weights[k] * rates[k] for k in rates if rates[k] is not None), rates

    default_wer, default_rates = point(0)
    candidate_wer, candidate_rates = point(1)
    return {
        "defaultWer": default_wer, "candidateWer": candidate_wer, "difference": candidate_wer - default_wer,
        "interval": interval(weighted_diffs),
        "cells": {k: {"difference": candidate_rates[k] - default_rates[k], "interval": interval(cell_diffs[k])} for k in pairs},
    }


def decide(default_rows, candidate_rows, default_summary, candidate_summary, device=None, licence=None, resamples=10_000):
    """Applies rules 1 to 5. `device` is {"realTimeFactor", "peakMemoryGb", "downloadGb"} measured on the phone (None: not yet
    measured). `licence` is {"redistributable": bool, "pinnedUrl": bool, "sha256": bool}. The verdict is one of
    "worth adding", "not worth adding" (with the first rule that failed) or "pending" (rules 1-3 passed, 4 or 5 unknown)."""
    pairs = paired_cells(default_rows, candidate_rows)
    if not pairs:
        raise ValueError("the two runs share no scored speech clips")
    boot = bootstrap(pairs, resamples=resamples)
    rules = []

    relative = (boot["defaultWer"] - boot["candidateWer"]) / boot["defaultWer"] if boot["defaultWer"] else 0.0
    rules.append({"rule": 1, "passed": relative >= MIN_RELATIVE_IMPROVEMENT and boot["interval"][1] < 0,
                  "detail": "weighted WER %.1f %% -> %.1f %% (%.0f %% relative; interval of the difference %.2f to %.2f points)" % (
                      100 * boot["defaultWer"], 100 * boot["candidateWer"], 100 * relative, 100 * boot["interval"][0], 100 * boot["interval"][1])})

    worse = [("%s/%s" % k, v) for k, v in boot["cells"].items() if v["difference"] >= WORSE_BY and v["interval"][0] > 0]
    rules.append({"rule": 2, "passed": not worse,
                  "detail": "significantly worse in: %s" % (", ".join("%s (+%.1f points)" % (k, 100 * v["difference"]) for k, v in worse) or "no cell")})

    d, c = default_summary, candidate_summary
    halluc_ok = (c["hallucinationRateGated"] or 0) <= (d["hallucinationRateGated"] or 0)
    loops_ok = c["repetitionLoops"] <= d["repetitionLoops"]
    lid_ok = (c["languageAccuracy"] or 0) >= (d["languageAccuracy"] or 0) - LANGUAGE_ID_TOLERANCE
    rules.append({"rule": 3, "passed": halluc_ok and loops_ok and lid_ok,
                  "detail": "hallucination %s, repetition loops %s -> %s, language identification %s -> %s" % (
                      "not higher" if halluc_ok else "HIGHER", d["repetitionLoops"], c["repetitionLoops"],
                      d["languageAccuracy"], c["languageAccuracy"])})

    if device is None:
        rules.append({"rule": 4, "passed": None, "detail": "not measured on the phone yet"})
    else:
        ok = (device["realTimeFactor"] <= MAX_REAL_TIME_FACTOR and device["peakMemoryGb"] <= MAX_PEAK_MEMORY_GB
              and device["downloadGb"] <= MAX_DOWNLOAD_GB)
        rules.append({"rule": 4, "passed": ok, "detail": "real-time factor %.2f, peak memory %.2f GB, download %.2f GB" % (
            device["realTimeFactor"], device["peakMemoryGb"], device["downloadGb"])})
    if licence is None:
        rules.append({"rule": 5, "passed": None, "detail": "licence, pinned URL and checksum not checked yet"})
    else:
        rules.append({"rule": 5, "passed": all(licence.values()), "detail": ", ".join("%s=%s" % kv for kv in sorted(licence.items()))})

    failed = [r for r in rules if r["passed"] is False]
    if failed:
        verdict = "not worth adding (rule %d)" % failed[0]["rule"]
    elif any(r["passed"] is None for r in rules):
        verdict = "pending (rules 1-3 passed; the others are not known yet)" if all(r["passed"] for r in rules[:3]) else "pending"
    else:
        verdict = "worth adding"
    return {"verdict": verdict, "rules": rules, "bootstrap": boot}

# SPDX-License-Identifier: GPL-3.0-or-later
"""The measures of docs/BENCHMARK.md: error rates, bootstrap intervals, hallucinations, repetition loops."""
import random

from .normalize import normalize


def levenshtein(a, b):
    """Edit distance between two sequences (substitution, insertion and deletion cost 1)."""
    if len(a) < len(b):
        a, b = b, a
    previous = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        current = [i]
        for j, y in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (x != y)))
        previous = current
    return previous[-1]


def word_errors(reference, hypothesis, lang):
    """(edits, reference length) over normalised words."""
    ref, hyp = normalize(reference, lang), normalize(hypothesis, lang)
    return levenshtein(ref, hyp), len(ref)


def char_errors(reference, hypothesis, lang):
    """(edits, reference length) over the normalised text as characters, words joined by single spaces."""
    ref, hyp = " ".join(normalize(reference, lang)), " ".join(normalize(hypothesis, lang))
    return levenshtein(ref, hyp), len(ref)


def micro_rate(pairs):
    """Sum of edits over sum of reference lengths (a long clip counts more than a short one); None if nothing to score."""
    edits = sum(e for e, _ in pairs)
    length = sum(n for _, n in pairs)
    return edits / length if length else None


def weighted_mean(rates, weights):
    """Weighted mean of per-cell rates; both are dicts with the same keys (the weights need not sum to one)."""
    if set(rates) != set(weights):
        raise ValueError("rates and weights must cover the same cells")
    total = sum(weights.values())
    return sum(rates[k] * weights[k] for k in rates) / total


def paired_bootstrap(default, candidate, resamples=10_000, seed=20261026, confidence=0.95):
    """Compares two systems on the same clips. `default` and `candidate` are equally long lists of (edits, reference
    length) per clip. Returns the two micro rates, the difference candidate - default with its percentile interval,
    and the relative improvement ((default - candidate) / default). The clips are resampled with replacement."""
    if len(default) != len(candidate) or not default:
        raise ValueError("need the same non-empty list of clips for both systems")
    n = len(default)
    rng = random.Random(seed)
    diffs = []
    for _ in range(resamples):
        picks = [rng.randrange(n) for _ in range(n)]
        length = sum(default[i][1] for i in picks)
        if length == 0:
            continue
        diffs.append((sum(candidate[i][0] for i in picks) - sum(default[i][0] for i in picks)) / length)
    diffs.sort()
    low = diffs[int((1 - confidence) / 2 * len(diffs))]
    high = diffs[min(len(diffs) - 1, int((1 + confidence) / 2 * len(diffs)))]
    rate_default, rate_candidate = micro_rate(default), micro_rate(candidate)
    return {
        "default": rate_default,
        "candidate": rate_candidate,
        "difference": rate_candidate - rate_default,
        "interval": (low, high),
        "relative_improvement": (rate_default - rate_candidate) / rate_default if rate_default else None,
    }


def is_hallucination(raw_text):
    """On a clip without speech, any output at all is invented text (also "..." or a lone word)."""
    return bool(raw_text and raw_text.strip())


def has_repetition(raw_text, min_repeats=4, max_ngram=8):
    """True if some run of 1 to `max_ngram` whitespace-separated tokens repeats `min_repeats` or more times in a row.
    Works on the raw transcript: a loop of "..." is empty after normalisation but is still a loop."""
    tokens = raw_text.lower().split()
    for n in range(1, max_ngram + 1):
        for start in range(0, len(tokens) - n * min_repeats + 1):
            block = tokens[start:start + n]
            if all(tokens[start + k * n:start + (k + 1) * n] == block for k in range(1, min_repeats)):
                return True
    return False


def language_accuracy(pairs):
    """Share of clips whose detected language equals the known one; `pairs` are (expected, detected)."""
    return sum(1 for expected, detected in pairs if expected == detected) / len(pairs) if pairs else None

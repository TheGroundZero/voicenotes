# SPDX-License-Identifier: GPL-3.0-or-later
"""Assembles one corpus clip from synthesized speech (16 kHz samples) following the plan in corpus.py. Every note the
app records starts with its own start tone, so every clip does: tone, a short gap, then the content. Standard library only."""
import hashlib
import random

from . import audio

LEAD_GAP_SECONDS = 0.3          # between the tone and the speech
TAIL_SECONDS = 0.5              # after the speech
SPEECH_LEVEL_DBFS = -22.0       # rms of the speech before noise is added (typical for a phone a little away from the mouth)
PEAK_LIMIT = 0.95


def rng_for(seed, name):
    return random.Random(int.from_bytes(hashlib.sha256(("%d|%s" % (seed, name)).encode()).digest()[:8], "big"))


def level_speech(samples, level_dbfs=SPEECH_LEVEL_DBFS):
    """The speech scaled to a common rms level (peaks kept under PEAK_LIMIT), so voices are comparable."""
    level = audio.rms(samples)
    if not level:
        return list(samples)
    gain = 10 ** (level_dbfs / 20) / level
    peak = max(abs(s) for s in samples) * gain
    if peak > PEAK_LIMIT:
        gain *= PEAK_LIMIT / peak
    return [s * gain for s in samples]


def _noise(kind, seconds, rng, babble_sources):
    if kind == "rumble":
        return audio.rumble_noise(seconds, rng)
    if kind == "pink":
        return audio.pink_noise(seconds, rng)
    if kind == "babble":
        return audio.babble(babble_sources, seconds, rng)
    raise ValueError("unknown noise kind %r" % kind)


def _scaled(samples, level_dbfs):
    level = audio.rms(samples)
    return [s * 10 ** (level_dbfs / 20) / level for s in samples] if level else samples


def lead():
    return audio.beep() + [0.0] * int(LEAD_GAP_SECONDS * audio.SAMPLE_RATE)


def compose_speech(clip, speech, babble_sources, seed):
    """Tone, gap, the leveled speech and a tail, with noise at the clip's signal-to-noise ratio if it has one. The ratio refers
    to the speech alone; the noise runs under the whole clip, tone included."""
    rng = rng_for(seed, clip["id"])
    speech = level_speech(speech)
    floor = rng_for(seed, clip["id"] + "|floor")
    body = speech
    if clip.get("pad_silence_seconds"):
        pad = audio.silence(clip["pad_silence_seconds"], rng=floor)
        body = pad + speech + audio.silence(clip["pad_silence_seconds"], rng=floor)
    head, tail = lead(), audio.silence(TAIL_SECONDS, rng=floor)
    clip_samples = head + body + tail
    if clip.get("snr_db") is None:
        return clip_samples
    noise = _noise(clip["noise"], len(clip_samples) / audio.SAMPLE_RATE, rng, babble_sources)
    noise = (noise + [0.0] * len(clip_samples))[:len(clip_samples)]
    span = slice(len(head), len(head) + len(body))              # the ratio is measured on the speech (and its padding)
    scale = audio.noise_scale(clip_samples[span], noise[span], clip["snr_db"])
    return [s + scale * x for s, x in zip(clip_samples, noise)]


def compose_non_speech(clip, babble_sources, seed):
    """Tone, gap and then silence or noise up to the clip's length."""
    rng = rng_for(seed, clip["id"])
    head = lead()
    seconds = max(0.5, clip["seconds"] - len(head) / audio.SAMPLE_RATE)
    recipe = clip["recipe"]
    if recipe == "silence":
        body = audio.silence(seconds, rng=rng)
    elif recipe in ("rumble", "pink"):
        body = _scaled(_noise(recipe, seconds, rng, None), clip["level_dbfs"])
    elif recipe == "babble":
        body = _scaled(_noise("babble", seconds, rng, babble_sources), clip["level_dbfs"])
    elif recipe == "tv":                                       # speech nobody asked for, over a rumble
        talk = _scaled(_noise("babble", seconds, rng, babble_sources), clip["level_dbfs"])
        hum = _scaled(_noise("rumble", seconds, rng, None), -40.0)
        body = [a + b for a, b in zip(talk, hum)]
    else:
        raise ValueError("unknown recipe %r" % recipe)
    return head + body

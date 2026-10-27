# SPDX-License-Identifier: GPL-3.0-or-later
"""Audio helpers for building the benchmark corpus: 16 kHz mono sample lists (floats in -1..1), WAV files, resampling,
the app's start beep, generated noise and mixing at a given signal-to-noise ratio. Standard library only, so everything is
written for clarity first; a clip takes well under a second."""
import array
import math
import operator
import random
import wave

SAMPLE_RATE = 16000


def read_wav(path):
    """(samples, rate) of a mono PCM16 WAV file; stereo is refused."""
    with wave.open(path, "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise ValueError("%s: expected mono 16-bit PCM" % path)
        data = array.array("h")
        data.frombytes(w.readframes(w.getnframes()))
        return [s / 32768.0 for s in data], w.getframerate()


def write_wav(path, samples, rate=SAMPLE_RATE):
    data = array.array("h", (max(-32768, min(32767, int(round(s * 32768.0)))) for s in samples))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(data.tobytes())


def rms(samples):
    return math.sqrt(sum(s * s for s in samples) / len(samples)) if samples else 0.0


def dbfs(value):
    return 20 * math.log10(value) if value > 0 else float("-inf")


def resample(samples, rate_in, rate_out=SAMPLE_RATE, half_width=16):
    """Windowed-sinc resampling by a rational factor (Blackman window, 2 * half_width taps)."""
    if rate_in == rate_out:
        return list(samples)
    g = math.gcd(rate_in, rate_out)
    up, down = rate_out // g, rate_in // g          # output sample m sits at input position m * down / up
    cutoff = min(1.0, rate_out / rate_in)           # low-pass below the new Nyquist when going down
    tables = []
    for phase in range(up):
        frac = (phase * down % up) / up
        taps = []
        for k in range(-half_width + 1, half_width + 1):
            x = k - frac
            window = 0.42 + 0.5 * math.cos(math.pi * x / half_width) + 0.08 * math.cos(2 * math.pi * x / half_width)
            sinc = cutoff if x == 0 else math.sin(math.pi * cutoff * x) / (math.pi * x)
            taps.append(sinc * window)
        tables.append(taps)
    padded = [0.0] * half_width + list(samples) + [0.0] * (half_width + 1)
    out = []
    for m in range(int(len(samples) * up / down)):
        base = m * down // up                        # index of the sample just before the position, in the original
        out.append(sum(map(operator.mul, tables[m * down % up], padded[base + 1:base + 1 + 2 * half_width])))
    return out


def beep(rate=SAMPLE_RATE, frequency=1200.0, seconds=0.2, level_dbfs=-12.0):
    """The app's start tone (Android TONE_PROP_BEEP: 1200 Hz, 200 ms) as the microphone hears it, with short fades."""
    n = int(rate * seconds)
    amplitude = 10 ** (level_dbfs / 20)
    fade = int(0.01 * rate)
    out = []
    for i in range(n):
        gain = min(1.0, i / fade, (n - 1 - i) / fade)
        out.append(amplitude * gain * math.sin(2 * math.pi * frequency * i / rate))
    return out


def silence(seconds, rate=SAMPLE_RATE, floor_dbfs=-70.0, rng=None):
    """Near-digital silence with a very low noise floor (real microphones never give exact zeros)."""
    rng = rng or random.Random(0)
    amplitude = 10 ** (floor_dbfs / 20)
    return [rng.gauss(0, amplitude) for _ in range(int(seconds * rate))]


def _one_pole_lowpass(samples, cutoff_hz, rate=SAMPLE_RATE):
    a = math.exp(-2 * math.pi * cutoff_hz / rate)
    y, out = 0.0, []
    for x in samples:
        y = a * y + (1 - a) * x
        out.append(y)
    return out


def _normalise(samples, target_rms=0.1):
    level = rms(samples)
    return [s * target_rms / level for s in samples] if level else samples


def white_noise(seconds, rng, rate=SAMPLE_RATE):
    return _normalise([rng.gauss(0, 1) for _ in range(int(seconds * rate))])


def pink_noise(seconds, rng, rate=SAMPLE_RATE):
    """Equal energy per octave (Paul Kellet's filter on white noise)."""
    b0 = b1 = b2 = b3 = b4 = b5 = b6 = 0.0
    out = []
    for _ in range(int(seconds * rate)):
        w = rng.gauss(0, 1)
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        out.append(b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362)
        b6 = w * 0.115926
    return _normalise(out)


def rumble_noise(seconds, rng, rate=SAMPLE_RATE):
    """A vehicle-like rumble: energy mostly below 300 Hz (engine, road, a train on its track), a little hiss above."""
    n = int(seconds * rate)
    low = _one_pole_lowpass(_one_pole_lowpass([rng.gauss(0, 1) for _ in range(n)], 200.0), 200.0)
    hiss = _one_pole_lowpass([rng.gauss(0, 1) for _ in range(n)], 2500.0)
    low_level, hiss_level = rms(low) or 1.0, rms(hiss) or 1.0
    return _normalise([a / low_level + 0.05 * b / hiss_level for a, b in zip(low, hiss)])


def babble(clips, seconds, rng, rate=SAMPLE_RATE):
    """Speech nobody asked for: several speech clips (16 kHz) overlapped at random offsets, looped to `seconds`."""
    n = int(seconds * rate)
    out = [0.0] * n
    for clip in clips:
        if not clip:
            continue
        offset = rng.randrange(len(clip))
        for i in range(n):
            out[i] += clip[(offset + i) % len(clip)]
    return _normalise(out)


def mix_at_snr(signal, noise, snr_db):
    """The signal plus noise scaled so that rms(signal) / rms(noise) is `snr_db` decibels. The noise is looped or cut to
    the length of the signal. The signal's level is measured over the whole signal passed in, so pass only the speech
    (not the beep or the silence around it) if that is what the ratio should refer to."""
    if not noise:
        return list(signal)
    looped = (noise * (len(signal) // len(noise) + 1))[:len(signal)]
    scale = rms(signal) / (rms(looped) * 10 ** (snr_db / 20)) if rms(looped) else 0.0
    return [s + scale * x for s, x in zip(signal, looped)]

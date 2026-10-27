# SPDX-License-Identifier: GPL-3.0-or-later
import math
import os
import random
import tempfile
import unittest

from vnbench import audio


def sine(frequency, seconds, rate, amplitude=0.5):
    return [amplitude * math.sin(2 * math.pi * frequency * i / rate) for i in range(int(seconds * rate))]


def goertzel_power(samples, frequency, rate=audio.SAMPLE_RATE):
    """Power of one frequency in the signal (Goertzel), per sample."""
    k = 2 * math.cos(2 * math.pi * frequency / rate)
    s1 = s2 = 0.0
    for x in samples:
        s1, s2 = x + k * s1 - s2, s1
    return (s1 * s1 + s2 * s2 - k * s1 * s2) / len(samples) ** 2


class WavTest(unittest.TestCase):
    def test_round_trip_within_quantisation(self):
        samples = sine(440, 0.1, 16000)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "x.wav")
            audio.write_wav(path, samples)
            back, rate = audio.read_wav(path)
        self.assertEqual(rate, 16000)
        self.assertEqual(len(back), len(samples))
        self.assertLess(max(abs(a - b) for a, b in zip(samples, back)), 1 / 16384)

    def test_clipping_instead_of_wrapping(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "x.wav")
            audio.write_wav(path, [2.0, -2.0])
            back, _ = audio.read_wav(path)
        self.assertGreater(back[0], 0.99)
        self.assertLess(back[1], -0.99)


class ResampleTest(unittest.TestCase):
    def test_length_and_tone_survive_22050_to_16000(self):
        source = sine(1000, 0.5, 22050)
        out = audio.resample(source, 22050, 16000)
        self.assertLess(abs(len(out) - 8000), 3)
        middle = out[500:-500]
        self.assertAlmostEqual(audio.rms(middle), 0.5 / math.sqrt(2), delta=0.01)       # amplitude kept
        self.assertGreater(goertzel_power(middle, 1000), 100 * goertzel_power(middle, 3000))   # still a 1 kHz tone

    def test_frequencies_above_the_new_nyquist_are_removed(self):
        out = audio.resample(sine(10000, 0.3, 22050), 22050, 16000)
        self.assertLess(audio.rms(out[500:-500]), 0.02)

    def test_same_rate_is_a_copy(self):
        self.assertEqual(audio.resample([0.1, 0.2], 16000, 16000), [0.1, 0.2])


class GeneratorTest(unittest.TestCase):
    def test_beep_is_the_apps_tone(self):
        b = audio.beep()
        self.assertEqual(len(b), 3200)                                          # 200 ms
        self.assertAlmostEqual(audio.dbfs(audio.rms(b)), -12 - 3.01, delta=0.5)  # sine rms is 3 dB under its peak
        self.assertGreater(goertzel_power(b, 1200), 50 * goertzel_power(b, 2400))

    def test_noise_is_deterministic_per_seed_and_normalised(self):
        a = audio.pink_noise(1, random.Random(5))
        self.assertEqual(a, audio.pink_noise(1, random.Random(5)))
        self.assertNotEqual(a, audio.pink_noise(1, random.Random(6)))
        self.assertAlmostEqual(audio.rms(a), 0.1, places=3)

    def test_rumble_is_low_pink_is_in_between_white_is_flat(self):
        rng = random.Random(1)
        rumble, pink, white = audio.rumble_noise(2, rng), audio.pink_noise(2, rng), audio.white_noise(2, rng)
        ratio = lambda x: goertzel_power(x, 100) / goertzel_power(x, 3000)
        self.assertGreater(ratio(rumble), 100)
        self.assertGreater(ratio(pink), 3)
        self.assertLess(ratio(pink), ratio(rumble))
        self.assertLess(ratio(white), 4)
        self.assertGreater(ratio(white), 0.25)

    def test_silence_is_very_quiet_but_not_digital_zero(self):
        s = audio.silence(1)
        self.assertEqual(len(s), 16000)
        self.assertLess(audio.dbfs(audio.rms(s)), -60)
        self.assertGreater(audio.rms(s), 0)

    def test_babble_has_the_requested_length_and_level(self):
        clips = [sine(f, 1.0, 16000) for f in (200, 300, 400)]
        out = audio.babble(clips, 3.0, random.Random(2))
        self.assertEqual(len(out), 48000)
        self.assertAlmostEqual(audio.rms(out), 0.1, places=3)


class MixTest(unittest.TestCase):
    def test_snr_is_exact(self):
        signal = sine(500, 1.0, 16000, 0.3)
        noise = audio.pink_noise(1, random.Random(3))
        for snr in (20, 10, 5, 0):
            mixed = audio.mix_at_snr(signal, noise, snr)
            added = [m - s for m, s in zip(mixed, signal)]
            self.assertAlmostEqual(audio.dbfs(audio.rms(signal)) - audio.dbfs(audio.rms(added)), snr, delta=0.01)

    def test_short_noise_is_looped(self):
        mixed = audio.mix_at_snr([0.1] * 100, [0.5, -0.5], 10)
        self.assertEqual(len(mixed), 100)

    def test_no_noise_changes_nothing(self):
        self.assertEqual(audio.mix_at_snr([0.1, 0.2], [], 10), [0.1, 0.2])


if __name__ == "__main__":
    unittest.main()

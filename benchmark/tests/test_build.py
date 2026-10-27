# SPDX-License-Identifier: GPL-3.0-or-later
import math
import unittest

from vnbench import audio, build


def tone(seconds, frequency=300.0, amplitude=0.3):
    return [amplitude * math.sin(2 * math.pi * frequency * i / 16000) for i in range(int(seconds * 16000))]


def speech_clip(**overrides):
    clip = {"id": "x-r0-clean", "kind": "speech", "snr_db": None, "noise": None}
    clip.update(overrides)
    return clip


class SpeechClipTest(unittest.TestCase):
    def test_every_clip_starts_with_the_start_tone(self):
        out = build.compose_speech(speech_clip(), tone(1.0), [], 1)
        self.assertGreater(audio.rms(out[:3200]), 0.1)                       # the 200 ms tone
        self.assertLess(audio.rms(out[3300:4700]), 0.001)                    # the gap after it
        self.assertAlmostEqual(len(out) / 16000, 0.2 + 0.3 + 1.0 + 0.5, delta=0.01)

    def test_speech_is_leveled(self):
        quiet = build.compose_speech(speech_clip(), tone(1.0, amplitude=0.01), [], 1)
        loud = build.compose_speech(speech_clip(), tone(1.0, amplitude=0.9), [], 1)
        speech = lambda x: x[8000:8000 + 16000]
        self.assertAlmostEqual(audio.dbfs(audio.rms(speech(quiet))), -22.0, delta=0.2)
        self.assertAlmostEqual(audio.dbfs(audio.rms(speech(loud))), -22.0, delta=1.5)   # the peak limit may lower a loud one
        self.assertLessEqual(max(abs(s) for s in loud), 1.0)

    def test_noise_is_mixed_at_the_requested_ratio_on_the_speech(self):
        base = build.compose_speech(speech_clip(), tone(2.0), [], 1)
        for snr in (20, 10, 5):
            noisy = build.compose_speech(speech_clip(id="x-r0-snr", snr_db=snr, noise="pink"), tone(2.0), [], 1)
            self.assertEqual(len(noisy), len(base))
            added = [n - b for n, b in zip(noisy, base)]
            span = slice(8000, 8000 + 32000)
            ratio = audio.dbfs(audio.rms(base[span])) - audio.dbfs(audio.rms(added[span]))
            self.assertAlmostEqual(ratio, snr, delta=0.05)

    def test_all_noise_kinds_work_and_are_deterministic(self):
        sources = [tone(1.0, f) for f in (200, 250, 330, 410)]
        for kind in ("rumble", "pink", "babble"):
            clip = speech_clip(id="y-" + kind, snr_db=10, noise=kind)
            a = build.compose_speech(clip, tone(1.0), sources, 7)
            self.assertEqual(a, build.compose_speech(clip, tone(1.0), sources, 7))
            self.assertNotEqual(a, build.compose_speech(clip, tone(1.0), sources, 8))

    def test_padded_one_word_notes_have_long_silence_around_the_word(self):
        out = build.compose_speech(speech_clip(pad_silence_seconds=12), tone(0.6), [], 1)
        self.assertAlmostEqual(len(out) / 16000, 0.5 + 12 + 0.6 + 12 + 0.5, delta=0.01)
        self.assertLess(audio.rms(out[8000 + 16000:8000 + 12 * 16000 - 16000]), 0.001)


class NonSpeechClipTest(unittest.TestCase):
    def clip(self, **kw):
        base = {"id": "ns-test", "kind": "non-speech", "recipe": "silence", "seconds": 4}
        base.update(kw)
        return base

    def test_length_includes_the_start_tone(self):
        out = build.compose_non_speech(self.clip(seconds=8), [], 1)
        self.assertAlmostEqual(len(out) / 16000, 8.0, delta=0.01)
        self.assertGreater(audio.rms(out[:3200]), 0.1)

    def test_silence_is_quiet_noise_has_its_level(self):
        silent = build.compose_non_speech(self.clip(), [], 1)
        self.assertLess(audio.dbfs(audio.rms(silent[8000:])), -60)
        for recipe in ("rumble", "pink"):
            out = build.compose_non_speech(self.clip(recipe=recipe, level_dbfs=-35, seconds=6), [], 1)
            self.assertAlmostEqual(audio.dbfs(audio.rms(out[8000:])), -35, delta=0.2)

    def test_babble_and_television(self):
        sources = [tone(1.0, f) for f in (200, 250, 330, 410)]
        babble = build.compose_non_speech(self.clip(recipe="babble", level_dbfs=-30, seconds=6), sources, 1)
        self.assertAlmostEqual(audio.dbfs(audio.rms(babble[8000:])), -30, delta=0.2)
        tv = build.compose_non_speech(self.clip(recipe="tv", level_dbfs=-30, seconds=6), sources, 1)
        self.assertGreater(audio.dbfs(audio.rms(tv[8000:])), -31)

    def test_unknown_recipe_is_refused(self):
        with self.assertRaises(ValueError):
            build.compose_non_speech(self.clip(recipe="rain"), [], 1)


if __name__ == "__main__":
    unittest.main()

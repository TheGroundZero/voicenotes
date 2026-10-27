# SPDX-License-Identifier: GPL-3.0-or-later
import pathlib
import unittest
from collections import Counter

from vnbench import corpus

ROOT = pathlib.Path(__file__).resolve().parent.parent


class PlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.voices = corpus.load_voices(ROOT / "voices.tsv")
        cls.sentences = {l: corpus.load_sentences(ROOT / "sentences" / ("%s.tsv" % l)) for l in corpus.LANGUAGES}
        cls.clips = corpus.plan(cls.sentences, cls.voices)

    def test_voices_have_acceptable_licences_and_pinned_hashes(self):
        self.assertGreaterEqual(len(self.voices), 10)
        for v in self.voices:
            self.assertIn(v["licence"], ("CC0", "public domain", "CC BY 4.0"), v["id"])
            self.assertEqual(len(v["sha256"]), 64, v["id"])
            self.assertTrue(v["dataset"].startswith("http"), v["id"])
        for lang in corpus.LANGUAGES:
            self.assertGreaterEqual(len(corpus.voice_slots(self.voices, lang)), 4, lang)

    def test_belgian_dutch_voices_are_included(self):
        self.assertTrue(any(v["id"].startswith("nl_BE-") for v in self.voices))

    def test_counts(self):
        speech = [c for c in self.clips if c["kind"] == "speech" and c["category"] != "oneword-padded"]
        for lang in corpus.LANGUAGES:
            sentences = len(self.sentences[lang])
            self.assertEqual(sum(1 for c in speech if c["language"] == lang), sentences * 2 * len(corpus.CONDITIONS))
        self.assertEqual(len([c for c in self.clips if c["category"] == "oneword-padded"]), 6)
        self.assertEqual(len([c for c in self.clips if c["kind"] == "non-speech"]), len(corpus.NON_SPEECH))

    def test_ids_are_unique_and_the_plan_is_deterministic(self):
        ids = [c["id"] for c in self.clips]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(self.clips, corpus.plan(self.sentences, self.voices))

    def test_another_seed_changes_noise_choices_only(self):
        other = corpus.plan(self.sentences, self.voices, seed=1)
        self.assertEqual([c["id"] for c in other], [c["id"] for c in self.clips])
        self.assertNotEqual([c.get("noise") for c in other], [c.get("noise") for c in self.clips])

    def test_the_references_are_the_sentences(self):
        by_id = {s["id"]: s["text"] for sents in self.sentences.values() for s in sents}
        for c in self.clips:
            if c["kind"] == "speech":
                self.assertEqual(c["reference"], by_id[c["sentence"]])
            else:
                self.assertEqual(c["reference"], "")

    def test_renditions_use_different_voices_and_speeds(self):
        for sentence in self.sentences["nl"]:
            first = next(c for c in self.clips if c["id"] == "%s-r0-clean" % sentence["id"])
            second = next(c for c in self.clips if c["id"] == "%s-r1-clean" % sentence["id"])
            self.assertNotEqual((first["voice"], first["speaker"]), (second["voice"], second["speaker"]))
            self.assertNotEqual(first["length_scale"], second["length_scale"])

    def test_conditions_of_a_rendition_share_voice_speed_and_noise(self):
        for c in self.clips:
            if c["kind"] == "speech" and c["condition"] != "clean":
                clean = next(x for x in self.clips if x["id"] == c["id"].rsplit("-", 1)[0] + "-clean")
                self.assertEqual((c["voice"], c["speaker"], c["length_scale"], c["noise"]), (clean["voice"], clean["speaker"], clean["length_scale"], clean["noise"]))
                self.assertIn(c["snr_db"], (20, 10, 5))

    def test_all_three_noise_kinds_and_every_voice_are_used(self):
        self.assertEqual({c["noise"] for c in self.clips if c["kind"] == "speech" and c["condition"] != "clean"}, set(corpus.NOISES))
        used = Counter(c["voice"] for c in self.clips if c["kind"] == "speech")
        for v in self.voices:
            self.assertGreater(used[v["id"]], 0, v["id"])

    def test_non_speech_covers_the_known_failure_cases(self):
        ids = {c["id"] for c in self.clips if c["kind"] == "non-speech"}
        for needed in ("ns-rumble-long", "ns-pink-long", "ns-rumble-short", "ns-tv-rumble", "ns-silence-15s"):
            self.assertIn(needed, ids)

    def test_smoke_subset_is_small_and_covers_every_language(self):
        smoke = corpus.smoke_subset(self.clips)
        self.assertLessEqual(len(smoke), 50)
        self.assertEqual({c["language"] for c in smoke if c["kind"] == "speech"}, set(corpus.LANGUAGES))
        self.assertTrue(any(c["kind"] == "non-speech" for c in smoke))


if __name__ == "__main__":
    unittest.main()

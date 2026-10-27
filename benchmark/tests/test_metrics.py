# SPDX-License-Identifier: GPL-3.0-or-later
import unittest

from vnbench.metrics import (char_errors, has_repetition, is_hallucination, language_accuracy, levenshtein, micro_rate,
                             paired_bootstrap, weighted_mean, word_errors)


class EditDistanceTest(unittest.TestCase):
    def test_known_distances(self):
        self.assertEqual(levenshtein("kitten", "sitting"), 3)
        self.assertEqual(levenshtein([], ["a", "b"]), 2)
        self.assertEqual(levenshtein(["a", "b"], ["a", "b"]), 0)
        self.assertEqual(levenshtein("abc", ""), 3)

    def test_symmetric(self):
        self.assertEqual(levenshtein("flaw", "lawn"), levenshtein("lawn", "flaw"))


class ErrorRateTest(unittest.TestCase):
    def test_word_errors(self):
        self.assertEqual(word_errors("a b c", "a x c", "en"), (1, 3))
        self.assertEqual(word_errors("a b c", "", "en"), (3, 3))
        self.assertEqual(word_errors("", "hello", "en"), (1, 0))
        self.assertEqual(word_errors("Hello, World!", "hello world", "en"), (0, 2))

    def test_char_errors(self):
        self.assertEqual(char_errors("abc", "abd", "en"), (1, 3))

    def test_micro_rate_weights_long_clips_more(self):
        self.assertAlmostEqual(micro_rate([(1, 2), (9, 100)]), 10 / 102)
        self.assertIsNone(micro_rate([(0, 0)]))

    def test_weighted_mean(self):
        self.assertAlmostEqual(weighted_mean({"en": 0.1, "nl": 0.2, "fr": 0.3}, {"en": 1, "nl": 1, "fr": 1}), 0.2)
        self.assertAlmostEqual(weighted_mean({"a": 0.0, "b": 1.0}, {"a": 3, "b": 1}), 0.25)
        with self.assertRaises(ValueError):
            weighted_mean({"a": 1}, {"b": 1})

    def test_whisper_style_digits_score_as_correct(self):
        cases = [
            ("en", "The repair will cost three hundred and forty euros, and the tyres another two hundred and ten.",
             "The repair will cost 340 euros, and the tyres another 210."),
            ("en", "The code for the lock is four seven one nine, and the reference number is eight two zero five five.",
             "The code for the lock is 4719, and the reference number is 82055."),
            ("en", "Send the package to twenty two Baker Street, flat three, in London.",
             "Send the package to 22 Baker Street, flat 3, in London."),
            ("nl", "De herstelling kost driehonderdveertig euro en de banden nog eens tweehonderd en tien.",
             "De herstelling kost 340 euro en de banden nog eens 210."),
            ("nl", "Stuur het pakket naar Kerkstraat tweeëntwintig, bus drie, in Antwerpen.",
             "Stuur het pakket naar Kerkstraat 22, bus 3, in Antwerpen."),
            ("fr", "La réparation coûtera trois cent quarante euros et les pneus encore deux cent dix.",
             "La réparation coûtera 340 euros et les pneus encore 210."),
            ("fr", "Envoie le colis au vingt-deux, rue des Écoles, appartement trois, à Bruxelles.",
             "Envoie le colis au 22, rue des Écoles, appartement 3, à Bruxelles."),
        ]
        for lang, reference, transcript in cases:
            self.assertEqual(word_errors(reference, transcript, lang)[0], 0, transcript)

    def test_a_wrong_number_is_an_error(self):
        self.assertEqual(word_errors("cost three hundred and forty euros", "cost 350 euros", "en")[0], 1)


class BootstrapTest(unittest.TestCase):
    def test_identical_systems_have_a_zero_interval(self):
        clips = [(1, 10), (0, 8), (2, 12), (0, 5)]
        result = paired_bootstrap(clips, clips, resamples=500)
        self.assertEqual(result["difference"], 0)
        self.assertEqual(result["interval"], (0.0, 0.0))

    def test_a_clearly_better_candidate_excludes_zero(self):
        default = [(3, 10)] * 40
        candidate = [(1, 10)] * 40
        result = paired_bootstrap(default, candidate, resamples=500)
        self.assertLess(result["interval"][1], 0)                 # difference candidate - default is negative
        self.assertAlmostEqual(result["relative_improvement"], 2 / 3)

    def test_a_noisy_small_difference_includes_zero(self):
        default = [(e, 10) for e in (0, 2, 0, 3, 1, 0, 2, 1)]
        candidate = [(e, 10) for e in (1, 1, 0, 2, 2, 0, 1, 2)]
        result = paired_bootstrap(default, candidate, resamples=2000)
        low, high = result["interval"]
        self.assertLessEqual(low, 0)
        self.assertGreaterEqual(high, 0)

    def test_same_seed_same_result(self):
        default, candidate = [(2, 10), (1, 10), (0, 10)], [(1, 10), (1, 10), (0, 10)]
        self.assertEqual(paired_bootstrap(default, candidate, resamples=300), paired_bootstrap(default, candidate, resamples=300))

    def test_mismatched_clips_are_refused(self):
        with self.assertRaises(ValueError):
            paired_bootstrap([(1, 2)], [(1, 2), (0, 3)])


class DetectorTest(unittest.TestCase):
    def test_hallucination_is_any_output(self):
        self.assertTrue(is_hallucination("..."))
        self.assertTrue(is_hallucination(" You "))
        self.assertFalse(is_hallucination(""))
        self.assertFalse(is_hallucination("  \n"))

    def test_repetition_loops(self):
        self.assertTrue(has_repetition("... ... ... ... ... ..."))
        self.assertTrue(has_repetition("thank you thank you thank you thank you"))
        self.assertTrue(has_repetition("a b c d a b c d a b c d a b c d"))
        self.assertFalse(has_repetition("Ask not what your country can do for you. Ask what you can do for your country."))
        self.assertFalse(has_repetition("no no no yes"))            # three repeats is not a loop
        self.assertFalse(has_repetition(""))

    def test_language_accuracy(self):
        self.assertAlmostEqual(language_accuracy([("en", "en"), ("nl", "fr"), ("fr", "fr"), ("nl", "nl")]), 0.75)
        self.assertIsNone(language_accuracy([]))


if __name__ == "__main__":
    unittest.main()

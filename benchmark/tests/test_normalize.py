# SPDX-License-Identifier: GPL-3.0-or-later
import unittest

from vnbench.normalize import normalize


class NormalizeTest(unittest.TestCase):
    def test_case_punctuation_and_accents(self):
        self.assertEqual(normalize("Hello, World! It's “fine”.", "en"), ["hello", "world", "it's", "fine"])
        self.assertEqual(normalize("Il a dit : « c’est l’été »", "fr"), ["il", "a", "dit", "c'est", "l'été"])
        self.assertNotEqual(normalize("ete", "fr"), normalize("été", "fr"))        # a missing accent is an error
        self.assertEqual(normalize("cœur", "fr"), ["coeur"])

    def test_hyphens_separate_words(self):
        self.assertEqual(normalize("tire-bouchon", "fr"), ["tire", "bouchon"])

    def test_fillers_are_dropped_per_language(self):
        self.assertEqual(normalize("Um, so uh I think, er, yes", "en"), ["so", "i", "think", "yes"])
        self.assertEqual(normalize("Euh, dus eh ik denk", "nl"), ["dus", "ik", "denk"])
        self.assertEqual(normalize("Er zijn twee", "nl"), ["er", "zijn", "2"])      # "er" is a real Dutch word

    def test_english_numbers(self):
        self.assertEqual(normalize("three hundred and forty euros", "en"), ["340", "euros"])
        self.assertEqual(normalize("340 euros", "en"), ["340", "euros"])
        self.assertEqual(normalize("twenty two and fourteen", "en"), ["22", "14"])
        self.assertEqual(normalize("one thousand two hundred thirty four", "en"), ["1234"])

    def test_numbers_are_split_at_punctuation(self):
        self.assertEqual(normalize("twenty two, fourteen", "en"), ["22", "14"])
        self.assertEqual(normalize("three hundred forty, four", "en"), ["340", "4"])

    def test_spoken_digit_sequences(self):
        self.assertEqual(normalize("four seven one nine", "en"), ["4719"])
        self.assertEqual(normalize("4 7 1 9", "en"), ["4719"])
        self.assertEqual(normalize("4719", "en"), ["4719"])
        self.assertEqual(normalize("oh seven seven double oh", "en"), ["07700"])
        self.assertEqual(normalize("oh well", "en"), ["oh", "well"])                # a lone "oh" is not a number

    def test_clock_times_are_two_numbers(self):
        self.assertEqual(normalize("seven forty", "en"), ["7", "40"])
        self.assertEqual(normalize("7:40", "en"), ["7", "40"])

    def test_dutch_numbers(self):
        self.assertEqual(normalize("driehonderdveertig", "nl"), ["340"])
        self.assertEqual(normalize("tweehonderd en tien", "nl"), ["210"])
        self.assertEqual(normalize("tweehonderdtien", "nl"), ["210"])
        self.assertEqual(normalize("tweeëntwintig", "nl"), ["22"])
        self.assertEqual(normalize("twee en twintig", "nl"), ["22"])
        self.assertEqual(normalize("honderd en vier", "nl"), ["104"])
        self.assertEqual(normalize("honderdvier", "nl"), ["104"])
        self.assertEqual(normalize("twintig voor acht", "nl"), ["20", "voor", "8"])
        self.assertEqual(normalize("vier zeven één negen", "nl"), ["4719"])

    def test_french_numbers(self):
        self.assertEqual(normalize("trois cent quarante", "fr"), ["340"])
        self.assertEqual(normalize("quatre-vingt-dix-sept", "fr"), ["97"])
        self.assertEqual(normalize("soixante et onze", "fr"), ["71"])
        self.assertEqual(normalize("soixante-dix", "fr"), ["70"])
        self.assertEqual(normalize("quatre vingts", "fr"), ["80"])
        self.assertEqual(normalize("vingt et un", "fr"), ["21"])
        self.assertEqual(normalize("vingt-deux", "fr"), ["22"])
        self.assertEqual(normalize("dix sept", "fr"), ["17"])
        self.assertEqual(normalize("deux cent dix", "fr"), ["210"])
        self.assertEqual(normalize("quatre sept un neuf", "fr"), ["4719"])
        self.assertEqual(normalize("huit heures moins vingt", "fr"), ["8", "heures", "moins", "20"])

    def test_unsupported_forms_stay_unequal(self):
        self.assertNotEqual(normalize("the third of November", "en"), normalize("the 3rd of November", "en"))

    def test_unknown_language(self):
        with self.assertRaises(ValueError):
            normalize("x", "de")

    def test_every_sentence_normalises_to_something(self):
        import pathlib
        for lang in ("en", "nl", "fr"):
            path = pathlib.Path(__file__).resolve().parent.parent / "sentences" / ("%s.tsv" % lang)
            rows = [line.rstrip("\n").split("\t") for line in path.read_text(encoding="utf-8").splitlines() if not line.startswith("#")]
            self.assertGreaterEqual(len(rows), 30)
            for row in rows:
                self.assertEqual(len(row), 3, row)
                self.assertTrue(normalize(row[2], lang), row)


if __name__ == "__main__":
    unittest.main()

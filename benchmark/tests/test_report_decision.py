# SPDX-License-Identifier: GPL-3.0-or-later
import unittest

from vnbench import decision, report

LANGS = ("en", "nl", "fr")
CONDITIONS = ("clean", "snr20", "snr10", "snr5")


def rows_for(edits_of, clips_per_cell=30, words=10, extra=None):
    """Speech rows for every cell; edits_of(language, condition, index) says how many word errors the clip has."""
    rows = []
    for lang in LANGS:
        for condition in CONDITIONS:
            for i in range(clips_per_cell):
                rows.append({"id": "%s-%s-%02d" % (lang, condition, i), "kind": "speech", "language": lang, "category": "reminder",
                             "condition": condition, "edits": edits_of(lang, condition, i), "refLen": words, "charEdits": 0, "charLen": 50,
                             "languageOk": True, "noSpeech": False, "text": "x", "repetition": False, "audioSeconds": 5.0,
                             "vadSeconds": 0.1, "detectSeconds": 0.5, "transcribeSeconds": 1.0, "audioCtx": 300})
    return rows + (extra or [])


def non_speech(hallucinate):
    return [{"id": "ns-%d" % i, "kind": "non-speech", "language": None, "category": "non-speech", "condition": None,
             "text": "words" if i < hallucinate else "", "hallucination": i < hallucinate, "repetition": False, "noSpeech": i >= hallucinate,
             "audioSeconds": 8.0, "vadSeconds": 0.1, "detectSeconds": 0.0, "transcribeSeconds": 0.0, "audioCtx": 0} for i in range(10)]


def run(rows):
    return rows, report.summarize(rows)


class ReportTest(unittest.TestCase):
    def test_weighted_wer_gives_each_language_and_condition_the_same_weight(self):
        # English clean has 10x the clips of the others but must still count 1/12 of the result
        rows = rows_for(lambda l, c, i: 0)
        for r in rows:
            if (r["language"], r["condition"]) == ("nl", "snr5"):
                r["edits"] = 10                                   # every word wrong in one of twelve cells
        s = report.summarize(rows)
        self.assertAlmostEqual(s["weightedWer"], 1 / 12)
        self.assertAlmostEqual(s["werByCell"]["nl/snr5"], 1.0)

    def test_summary_counts_hallucinations_loops_and_real_time_factor(self):
        rows = rows_for(lambda l, c, i: 1) + non_speech(3)
        rows[0]["repetition"] = True
        s = report.summarize(rows, raw_rows=non_speech(8))
        self.assertAlmostEqual(s["hallucinationRateGated"], 0.3)
        self.assertAlmostEqual(s["hallucinationRateRaw"], 0.8)
        self.assertEqual(s["repetitionLoops"], 1)
        self.assertAlmostEqual(s["languageAccuracy"], 1.0)
        self.assertAlmostEqual(s["realTimeFactor"], (360 * 1.6 + 10 * 0.1) / (360 * 5.0 + 10 * 8.0))

    def test_clip_row_scores_a_speech_clip_and_a_noise_clip(self):
        result = {"text": "Hello wrld", "language": "en", "noSpeech": False, "vadSeconds": 0.1, "detectSeconds": 0.2, "transcribeSeconds": 0.3, "audioCtx": 300}
        clip = {"id": "a", "kind": "speech", "language": "en", "category": "reminder", "condition": "clean", "reference": "Hello world"}
        row = report.clip_row(clip, result, 2.0)
        self.assertEqual((row["edits"], row["refLen"], row["languageOk"]), (1, 2, True))
        noise = report.clip_row({"id": "n", "kind": "non-speech", "category": "non-speech", "reference": ""}, dict(result, text="..."), 3.0)
        self.assertTrue(noise["hallucination"])
        self.assertTrue(noise["repetition"] is False)


class DecisionTest(unittest.TestCase):
    def verdict(self, default_edits, candidate_edits, default_extra=None, candidate_extra=None, **kw):
        d, ds = run(rows_for(default_edits, extra=default_extra))
        c, cs = run(rows_for(candidate_edits, extra=candidate_extra))
        return decision.decide(d, c, ds, cs, resamples=300, **kw)

    def rule(self, result, number):
        return next(r for r in result["rules"] if r["rule"] == number)

    def test_a_clearly_better_model_passes_rules_1_to_3_and_waits_for_the_phone(self):
        result = self.verdict(lambda l, c, i: 2 if i % 2 else 1, lambda l, c, i: 1 if i % 2 else 0,
                              non_speech(2), non_speech(2))
        for n in (1, 2, 3):
            self.assertTrue(self.rule(result, n)["passed"], self.rule(result, n)["detail"])
        self.assertTrue(result["verdict"].startswith("pending"))

    def test_with_the_phone_and_licence_it_is_worth_adding(self):
        result = self.verdict(lambda l, c, i: 2, lambda l, c, i: 1, device={"realTimeFactor": 1.0, "peakMemoryGb": 1.2, "downloadGb": 0.9},
                              licence={"redistributable": True, "pinnedUrl": True, "sha256": True})
        self.assertEqual(result["verdict"], "worth adding")

    def test_the_same_model_is_not_worth_adding_because_of_rule_1(self):
        result = self.verdict(lambda l, c, i: i % 3, lambda l, c, i: i % 3)
        self.assertEqual(result["verdict"], "not worth adding (rule 1)")

    def test_a_small_improvement_is_not_enough(self):
        result = self.verdict(lambda l, c, i: 2 if i < 10 else 1, lambda l, c, i: 2 if i < 8 else 1)     # 20 -> 18 words wrong in 30
        self.assertFalse(self.rule(result, 1)["passed"])

    def test_better_overall_but_clearly_worse_in_one_cell_fails_rule_2(self):
        worse_cell = lambda l, c, i: 4 if (l, c) == ("nl", "snr5") else 0
        result = self.verdict(lambda l, c, i: 2 if (l, c) != ("nl", "snr5") else 1, worse_cell)
        self.assertTrue(self.rule(result, 1)["passed"])
        self.assertFalse(self.rule(result, 2)["passed"])
        self.assertEqual(result["verdict"], "not worth adding (rule 2)")
        self.assertIn("nl/snr5", self.rule(result, 2)["detail"])

    def test_more_hallucinations_fail_rule_3(self):
        result = self.verdict(lambda l, c, i: 2, lambda l, c, i: 1, non_speech(1), non_speech(4))
        self.assertFalse(self.rule(result, 3)["passed"])
        self.assertEqual(result["verdict"], "not worth adding (rule 3)")

    def test_too_slow_on_the_phone_fails_rule_4(self):
        result = self.verdict(lambda l, c, i: 2, lambda l, c, i: 1, device={"realTimeFactor": 2.2, "peakMemoryGb": 1.0, "downloadGb": 0.8})
        self.assertEqual(result["verdict"], "not worth adding (rule 4)")

    def test_runs_without_common_clips_are_refused(self):
        a = rows_for(lambda l, c, i: 0)
        b = [dict(r, id="other-" + r["id"]) for r in a]
        with self.assertRaises(ValueError):
            decision.decide(a, b, report.summarize(a), report.summarize(b), resamples=50)


if __name__ == "__main__":
    unittest.main()

# Speech-model benchmark

Status: the design is agreed (2026-10-26) and steps 1 and 2 are done: the shared native core and the command-line tool
exist and are checked against the app (see *Step 2*). The corpus generator, the scorer and the first results are next.
This page was written before any result existed on purpose: the decision rule below is fixed first, so a number cannot
be read in a convenient way afterwards.

Why: the model catalog ships inside the app, so adding a model costs an app release. We decide with data when a new
model is worth that, by running every candidate through the same test set, in the same code path as the app, and
comparing it with the current default (`large-v3-turbo-q8_0`). The result is a short report and a draft catalog entry
for a person to review, never an automatic change to the app.

## What is measured, and how it is scored

Every clip has a known reference text. The same transcription steps as the app run on it: beam search (size 5), the short
audio window for short notes, the speech check (Silero VAD), and language detection restricted to the preferred
languages (English, Dutch and French here). Each clip also carries the app's own start beep at the beginning, as every
real note does.

**Text normalisation** (applied to reference and transcript before comparing; written once, here, and implemented in
`benchmark/vnbench/normalize.py`): Unicode NFKC, lower case, `’` becomes `'`, every punctuation mark and symbol is removed
except an apostrophe inside a word, hyphens become spaces, `œ` becomes `oe`, accents are kept (a missing accent is an
error), filler words are dropped per language (English `um uh er erm hmm mm`, Dutch `euh eh uhm hm hmm`, French `euh heu hum
hm`), and **number words are turned into digits** so that "three hundred and forty", "driehonderdveertig", "trois cent
quarante" and "340" compare equal (whole numbers up to 9999, including compounds, "tweehonderd en tien", "quatre-vingt-dix-sept",
and spoken digit sequences: "four seven one nine" and "4 7 1 9" are both 4719). Punctuation ends a number, so "twenty two,
fourteen" is 22 and 14. Not converted, so they count as errors, which is conservative: ordinals ("3rd" against "third"), decimals,
thousands separators, and clock times ("9:15" becomes "9 15", which matches a reference spoken as two numbers). Numbers are also
scored as their own category so that the converter cannot hide a model's weakness. (An earlier draft of this page turned digits
into words; turning words into digits tolerates more spoken variants.)

| Measure | Definition |
|---|---|
| **WER**, **CER** | word and character edit distance over the reference length, summed over clips (micro average); 95 % confidence interval by bootstrap over clips (10,000 resamples, fixed seed) |
| **Hallucination rate** | share of non-speech clips (silence, noise, a television talking) with any non-empty transcript, counted both *after* the app's speech check (what a user would see) and *without* it (the model on its own) |
| **Repetition loops** | transcripts in which a word sequence repeats four or more times in a row |
| **Language identification** | accuracy of the restricted detection against the clip's known language (clips with two languages count for the main one) |
| **Speed and memory** | real-time factor (detection plus transcription divided by audio length) and model load time on the Pixel 10, and peak memory (`VmHWM`); download size |

## The decision rule (fixed in advance)

A candidate is **worth adding** only if all of these hold against the current default:

1. Weighted mean WER is lower by at least **10 % relative**, and the 95 % interval of the paired difference (bootstrap
   over the same clips) excludes zero. Weights: **English, Dutch and French one third each** (decided 2026-10-26);
   conditions clean, 20 dB, 10 dB and 5 dB signal-to-noise a quarter each.
2. In no single language-and-condition cell is the candidate *significantly worse*: higher WER by at least one
   percentage point with the paired interval entirely above zero.
3. Hallucination rate (after the speech check) and the number of repetition loops are not higher; language
   identification is not lower by more than one percentage point.
4. On the Pixel 10: real-time factor of at most **1.5** for a 10-second note, peak memory at most **2 GB**, download at most
   **1 GB**.
5. A licence that allows redistribution of the weights (MIT, Apache-2.0, BSD or CC BY), a stable URL pinned to a
   revision and a published SHA-256.

The outcome is one of *worth adding*, *not worth adding* (with the first rule that failed) or *interesting, other engine*
(a model the engine cannot run; it is benchmarked off the phone if possible and handled as a separate decision).
These numbers are proposals: they are reviewed in the pull request that adds this page, before any result is seen.

## The test corpus

Three layers, each with its own licence story, so the repository stays clean:

1. **Original sentences, committed (CC0).** [`benchmark/sentences/`](../benchmark/sentences): 36 per language to start
   with (`en.tsv`, `nl.tsv`, `fr.tsv`), written for this project and invented from start to end (names, streets, numbers).
   Categories: reminders, shopping lists, addresses, amounts, dates and times, names, mixed-language words, short
   fragments, long run-ons, digit groups, hesitations, one-word notes, two languages in one note, a 30-second note and a
   one-minute note. The reference is exact by construction. **You are expected to add real-life sentences.**
2. **Synthetic speech from those sentences, generated, not committed.** A script produces several voices per language at
   two speeds from the sentences. Only a manifest is committed (text, voice, engine version, seed, SHA-256 of the audio).
   The engine is [Piper](https://github.com/rhasspy/piper) (MIT, archived; release 2023.11.14-2, `piper_linux_x86_64.tar.gz`,
   SHA-256 `a50cb45f355b7af1f6d758c1b360717877ba0a398cc8cbe6d2a7a3a26e225992`, recorded at the first download because GitHub
   publishes no digest for it; it is downloaded into the ignored cache, never committed, and runs offline). **Each voice has
   its own licence, derived from its training data, so every voice used is checked and recorded** in
   [`benchmark/voices.tsv`](../benchmark/voices.tsv), read from each voice's model card on 2026-10-27, with the SHA-256 of the
   model file. **Rule: CC0, public domain or CC BY 4.0 only.** Left out: `mike` (CC0, but it needs a newer Piper than the pinned release can load), non-commercial (`ryan`, `hfc_*`, `l2arctic`, `semaine`),
   share-alike (`northern_english_male`, `southern_english_female`, `upmc`), AGPL (`tom`), the Blizzard-licensed `lessac`, and
   everything whose card says only "see URL" (`alan`, `amy`, `jenny_dioco`, `kusal`, `danny`, `arctic`). Chosen: English
   `joe` (CC0), `john` and `cori` (public domain), `alba` (CC BY 4.0); Dutch `nathalie` and `rdh` (**Belgian**, CC0), `pim`,
   `ronnie` (CC0); French `siwis` (CC BY 4.0), `gilles` (CC0, low quality, 16 kHz) and two speakers of `mls` (CC BY 4.0). Synthetic
   speech is cleaner than people: it ranks models but over-estimates real accuracy, so it is never the only layer.
3. **Real human speech from public sources, downloaded by script into an ignored cache, never committed**
   (`benchmark/cache/`). Candidates: Mozilla Common Voice (CC0 for English, Dutch and French; its terms forbid
   re-hosting and identifying speakers, hence the cache) and per-file filtered Wikimedia speech. Licences are checked per
   source before use.

**Private holdout (never leaves the machine, git-ignored in `benchmark/private/`):** your own real notes with corrected
transcripts, as a sanity check. **No recording of anybody's own voice is ever uploaded or committed.**

**The plan** (`benchmark/vnbench/corpus.py`, pure and deterministic, tested): every sentence is made twice (two different
voices, the second about 15 % faster), each in four conditions: clean and mixed with noise at 20, 10 and 5 dB signal-to-noise
(the ratio of the speech's level to the noise's level, measured on the speech only; the noise kind rumble, pink or babble is
fixed per sentence and rendition). That is 36 × 2 × 4 = 288 clips per language, 864 in all, plus the six one-word notes with
12 seconds of silence around them and 16 clips without speech: silence (4, 8, 15 s), rumble and pink noise at three levels,
overlapping babble, babble over a rumble (a television), two 35-second noises (the full-window path) and two short noises of 2 and 3
seconds (where repetition loops have shown up). A smoke subset of 40 clips runs in minutes. Every speech clip starts with the
app's start tone, as real notes do.

**Conditions and special cases.** Each clip is made clean and mixed with noise at 20, 10 and 5 dB (fixed seeds): car-like
low rumble, pink noise, and babble made from other synthetic voices (speech nobody wants). Noise is generated, not
recorded, so it can be published; a recording of a real car or room is private by default and published only if you say
so for that file. Special clips, taken from real failures: pure silence and pure noise of 4 to 15 seconds (expected
output: nothing), noise with a television talking, a one-word note with long silence around it, a note that starts
mid-sentence, two languages in one clip, and notes over 25 seconds (the full-window path).

## The harness

- **One code path.** The transcription steps live in a shared C++ core (`app/src/main/cpp/whisper_core.cpp`) that the
  app's JNI layer and a command-line tool, `vn-bench`, both use, so the benchmark cannot drift from the app. The core
  keeps the engine and model swappable: the model, decoding settings and engine are named in every result file. A unit
  test (`benchmark/tests/core_test.cpp`) fixes the audio-window sizing against the table of the Kotlin test it replaced.
- **Two stages.** *Accuracy* runs on this machine (the same code built for this machine's processor; the weights and
  quantisation are identical, and one subset is run on both to confirm). *Speed and memory* run on the Pixel 10 over adb, only for
  candidates that pass stage 1.
- **Reproducible.** Pinned versions, fixed seeds, checksummed inputs; one command (planned: `scripts/benchmark.sh <model>`;
  today `scripts/benchmark-build.sh` builds the tool), results in `benchmark/results/<date>-<model>.json` plus a Markdown report.
- **Run size.** About 150 to 250 clips per language and condition set; a smoke subset of about 20 clips runs in minutes.
- **No new dependencies in the app or the build.** The scoring and corpus scripts are plain Python 3 with the standard
  library only.

## Running it

```sh
scripts/benchmark-build.sh                                    # the command-line tool (once, about 5 minutes)
python3 benchmark/make_corpus.py --smoke                      # 40 clips; without --smoke all 886 (downloads about 700 MB of voices once)
scripts/benchmark.sh models/ggml-small.bin                    # one model: benchmark/results/<date>-small.json and a summary
scripts/benchmark.sh models/ggml-large-v3-turbo-q8_0.bin --audio-ctx full     # the same model with the full 30 s window
python3 benchmark/compare.py benchmark/results/<default>.json benchmark/results/<candidate>.json [--device phone.json] [--licence licence.json]
```

`compare.py` applies the decision rule above to two result files and prints each rule with its numbers and the verdict. Rules 4
(phone) and 5 (licence) stay "open" until their files are given, and a candidate that fails rule 1, 2 or 3 is "not worth adding"
whatever the phone says. Results are plain JSON with the model's SHA-256, the settings and every clip's raw transcript (the
sentences are synthetic, so nothing private is in them). Result files that justify a decision are committed under
`benchmark/results/`; the generated audio never is.

## Step 2: what was built and checked

- `app/src/main/cpp/whisper_core.{h,cpp}` holds the whole per-note pipeline (speech check with the start beep silenced,
  window sizing, restricted language detection, beam search); `audio_context.h` and `vn_result.h` are plain headers.
  `whisper_jni.cpp` only converts types and calls the core; `WhisperTranscriber.kt` reads the JSON result.
- `benchmark/vn_bench.cpp`, built by `scripts/benchmark-build.sh` into `build-vn-bench/`, prints one JSON line per clip
  (transcript, language, speech seconds, window, timings) and the model load time and peak memory at the end.
- `benchmark/tests/core_test.cpp` replaces the Kotlin `AudioContextTest` (same table, plus the exact 25-second edge)
  and checks the JSON; `scripts/check.sh` runs it when a C++ compiler is present.
- **Phone check (Pixel 10, `small` model, whisper.cpp's public-domain `jfk.wav`, 6 s of silence, 6 s of quiet noise, and a
  33-second note):** the old and the new build gave the same text, language (detected), speech time (7.88 s) and window
  (651); silence and noise were skipped by the speech check in both; the 33-second note used the full window. Timing was
  within 0.1 s. The same clips on this machine through `vn-bench` gave the same text.

## Steps

| Step | What | State |
|---|---|---|
| 1 | This design and the sentences, reviewed | done |
| 2 | Shared native core and `vn-bench`; the app re-tested on a phone | done: same text, language, speech time and window as before on a phone (*Step 2* above) |
| 3a | Scorer: normaliser, error rates, paired bootstrap, hallucination and repetition detectors (`benchmark/vnbench`, 28 tests) | done |
| 3b | Corpus: voices with their licence check, the plan, the audio utilities and `benchmark/make_corpus.py` (downloads and verifies Piper and the voices, speaks the sentences, mixes the noise at exact ratios, writes the clips and a manifest); the 40-clip smoke subset was built and scored end to end | done |
| 3c | Runner (`scripts/benchmark.sh`), report and the decision rule in code (`benchmark/vnbench/decision.py`, 11 tests with made-up results); first results on the smoke subset | done; the full corpus is run on request (hours per large model) |
| 4 | Speed and memory on the Pixel 10 | not started |
| 5 | Finding candidates automatically (new files on Hugging Face, whisper.cpp releases, leaderboards) | later |

## First results (2026-10-28, smoke corpus)

`large-v3-turbo-q8_0` on the 40-clip smoke subset (generated speech, English, Dutch and French, clean and 5 dB noise), a fresh
engine context for every clip as in the app, on the development machine (`benchmark/results/2026-10-28-*.json`):

| Audio window | Weighted WER | Real-time factor |
|---|---|---|
| auto (what the app does today) | 50.6 % | 0.9 |
| full 30 s | 6.5 % | 3.5 |
| 1000 frames | 7.2 % | 2.1 |
| 750 frames | 14.2 % | 1.5 |
| 768 frames | 13.6 % | 2.3 |
| 512 frames | 26.2 % | 1.3 |
| 500 frames | 18.6 % | 1.0 |

Language identification was right for every clip in every run. `compare.py` passes rules 1 to 3 for the full window and for
1000 frames against the app's window (rules 4 and 5, the phone measurements and the licence check, are open).

**A window that differs between language detection and transcription breaks the transcript.** Detecting with the full window
and then transcribing with the automatic one gave 171 % WER and 11 repetition loops (`vn-bench --detect-audio-ctx full`). Cause
(found 2026-10-28, reproduced on single clips): with flash attention, whisper.cpp reads the cross-attention cache in blocks of
256 positions without a mask, so the positions between the window and the next multiple of 256 are used although the encoder
did not write them in that call. They hold zeros on a fresh state, or the leftovers of an earlier, larger encode. A window that
is a multiple of 256 (512, 768) was immune in the tests, 347 and 400 were not. The app uses one window for both steps, so it
is not affected. I also expected the zero padding to hurt accuracy for windows far from a multiple of 256, and tested it: 512 was
**not** better than 500 (26.2 % against 18.6 %, mostly one Dutch cell) and 768 was about the same as 750 (13.6 % against
14.2 %). With 40 clips that is noise, and the hypothesis is not supported. What the numbers do show is that short windows lose
accuracy, steeply below about 750 frames.

These are synthetic clips with wide confidence intervals and timings from a shared machine. **Nothing changes in the app until
the full corpus, real recordings and the Pixel 10 agree.**

Open: which TTS voices pass the licence check, where a scheduled candidate search runs and how you are told, and whether
engines other than Whisper (whisper.cpp also contains NVIDIA Parakeet support) are in scope after the first results.

## Full-corpus results (2026-10-28 and 29, 886 clips)

`large-v3-turbo-q8_0`, the whole corpus (870 speech clips, 1.91 hours of audio, and 16 non-speech clips), a fresh engine context for every
clip, on the development machine (`benchmark/results-full/`). The three audio windows:

| | App today (automatic) | 1000 frames | Full 30 s window |
|---|---|---|---|
| Weighted word error rate | 57.6 % | 28.6 % | **13.3 %** |
| Character error rate | 49.0 % | 19.4 % | **7.1 %** |
| Language identification | 96.2 % | 96.3 % | 96.2 % |
| Repetition loops | 66 | 17 | **3** |
| Real-time factor on this machine | 1.14 | 2.16 | 4.15 |

`compare.py` passes rules 1 to 3 of the decision rule for the full window (77 % relative improvement, no cell significantly worse, fewer
loops) and for 1000 frames (50 %). Rules 4 and 5, the measurements on the Pixel 10 and the licence check, are still open.

Word error rate by clip category (the reference text is what the voice was given):

| Category | Automatic | 1000 frames | Full |
|---|---|---|---|
| reminder | 47.5 % | 5.1 % | 5.1 % |
| shopping | 54.9 % | 6.2 % | 6.0 % |
| numbers | 55.3 % | 6.5 % | 5.3 % |
| amount | 244.1 % | 12.2 % | 9.5 % |
| address | 75.9 % | 11.0 % | 7.7 % |
| datetime | 76.0 % | 14.3 % | 13.0 % |
| hesitation | 45.5 % | 10.7 % | 9.3 % |
| name | 32.2 % | 25.8 % | 23.4 % |
| mixed (two languages in one clip) | 75.9 % | 21.2 % | 20.0 % |
| twolang | 52.9 % | 34.6 % | 31.6 % |
| runon | 12.0 % | 4.4 % | 4.5 % |
| long | 11.7 % | **60.0 %** | 11.8 % |
| verylong | 10.7 % | **49.9 %** | 10.7 % |
| short | 576.0 % | 67.9 % | 53.4 % |
| oneword | 1685.4 % | 218.8 % | 112.5 % |
| oneword-padded | 200.0 % | 283.3 % | 200.0 % |

What it shows:

- **The app's automatic window costs a lot of accuracy on short and medium notes**, here by a factor of four or more in most categories.
- **A fixed 1000-frame window (20 s) breaks long notes** (long 11.7 % to 60.0 %, very long 10.7 % to 49.9 %), because the window cannot cover them.
  The decision rule does not see this: it looks at cells (language and noise level), not at note length.
- **The one-word and very short clips stay bad in every setting** (an insertion of a single word is more than 100 % of a one-word reference).
- **About 19 % of the non-speech clips still produce text with the speech check on**, in all three runs; that is a separate question.

A combined policy, computed from the per-clip results of the three runs (no new run; per clip, take the 1000-frame result when the audio is up
to the limit and the full-window result above it):

| Policy (870 speech clips) | WER | Compute (real-time factor per clip) |
|---|---|---|
| Automatic (the app today) | 57.1 % | 1.16 |
| Fixed 1000 frames | 28.6 % | 2.20 |
| 1000 frames up to 10, 15, 18 or 20 s, full window above | 14.8 % | 2.35 to 2.50 |
| 1000 frames up to 25 s, full window above | 15.7 % | 2.35 |
| Full window always | 13.3 % | 4.25 |

Most clips are short (median 5.7 s, 90th percentile 12.3 s; 6 % are longer than 20 s), so the combined policy keeps almost all of the gain
for about 55 % of the full window's compute. It does not exist in `vn-bench` yet, so this is an estimate, not a measurement.

Caveats: the clips are generated speech with synthetic noise, the timings are from a shared development machine, and the real question for
the app is the speed and memory on the phone (rule 4). **Nothing changes in the app until a policy has been run for real, checked on
real recordings, and measured on the Pixel 10.**

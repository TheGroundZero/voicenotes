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

**Text normalisation** (applied to reference and transcript before comparing; written once, here): Unicode NFKC, lower
case, `’` becomes `'`, every punctuation mark is removed except an apostrophe inside a word, hyphens become spaces,
`œ` becomes `oe`, accents are kept (a missing accent is an error), filler words are dropped (`um uh er hmm`, `euh eh
uhm hm`, `euh heu hum`), and digits are spelled out as words in the clip's language (whole numbers up to 9999; ordinals,
times such as `9:15` and decimals are not converted and count as errors, which is conservative). Numbers are also scored as
their own category so that the converter cannot hide a model's weakness.

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
   several speeds from the sentences. Only a manifest is committed (text, voice, engine version, seed, SHA-256 of the
   audio). The proposed engine is Piper; **each voice has its own licence, derived from its training data, so every
   voice used is checked and recorded** in `benchmark/voices.tsv` and voices with non-commercial or unclear terms are
   excluded. Synthetic speech is cleaner than people: it ranks models but over-estimates real accuracy, so it is never
   the only layer.
3. **Real human speech from public sources, downloaded by script into an ignored cache, never committed**
   (`benchmark/cache/`). Candidates: Mozilla Common Voice (CC0 for English, Dutch and French; its terms forbid
   re-hosting and identifying speakers, hence the cache) and per-file filtered Wikimedia speech. Licences are checked per
   source before use.

**Private holdout (never leaves the machine, git-ignored in `benchmark/private/`):** your own real notes with corrected
transcripts, as a sanity check. **No recording of anybody's own voice is ever uploaded or committed.**

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
| 3 | Corpus generation (voices, noise), scorer, first results for the installed models | not started |
| 4 | Speed and memory on the Pixel 10 | not started |
| 5 | Finding candidates automatically (new files on Hugging Face, whisper.cpp releases, leaderboards) | later |

Open: which TTS voices pass the licence check, where a scheduled candidate search runs and how you are told, and whether
engines other than Whisper (whisper.cpp also contains NVIDIA Parakeet support) are in scope after the first results.

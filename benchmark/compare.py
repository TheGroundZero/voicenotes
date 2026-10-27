#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Applies the decision rule of docs/BENCHMARK.md to two result files (run_benchmark.py) and prints the verdict.

  python3 benchmark/compare.py benchmark/results/<default>.json benchmark/results/<candidate>.json [--device phone.json] [--licence licence.json]

phone.json: {"realTimeFactor": 1.1, "peakMemoryGb": 1.4, "downloadGb": 0.87}   licence.json: {"redistributable": true, "pinnedUrl": true, "sha256": true}"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from vnbench import decision  # noqa: E402


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("default")
    p.add_argument("candidate")
    p.add_argument("--device")
    p.add_argument("--licence")
    p.add_argument("--resamples", type=int, default=10_000)
    args = p.parse_args()
    d, c = (json.load(open(f, encoding="utf-8")) for f in (args.default, args.candidate))
    result = decision.decide(d["clips"], c["clips"], d["summary"], c["summary"],
                             device=json.load(open(args.device)) if args.device else None,
                             licence=json.load(open(args.licence)) if args.licence else None, resamples=args.resamples)
    print("# %s against the default %s" % (c["model"]["id"], d["model"]["id"]))
    for r in result["rules"]:
        mark = {True: "pass", False: "FAIL", None: "open"}[r["passed"]]
        print("- rule %d: **%s**. %s" % (r["rule"], mark, r["detail"]))
    print("\nverdict: **%s**" % result["verdict"])


if __name__ == "__main__":
    main()

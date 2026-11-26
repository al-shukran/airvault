#!/usr/bin/env python3
"""Reads what the design gate wrote (lumio/build/design-gate/*.json) and settles it against design/registry.json.

The Kotlin gate (lumio/src/test/java/app/lumio/ui/design/DesignGate.kt) fails a test on a new violation and on a known violation that now
passes. This script adds what a test cannot see: a registered screen that never ran (a skipped or filtered test is a failure), a registry that
contradicts itself or the frames, and a golden that no row names. It writes the per-element report to build/design-gate/report.md and the job
summary ($GITHUB_STEP_SUMMARY). Usage: check-results.py [--registry design/registry.json] [--results lumio/build/design-gate]
"""
import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOLERANCE_KEYS = {"bandDp", "textPosDp", "fontSizeDp", "lineHeightDp", "letterSpacingDp", "textColor", "sample", "cell", "cellDp", "ink", "iconColor"}
LISTS = ("known_violations", "unregistered", "no_frame")


def registry_errors(reg, frames_dir, golden_names):
    errors = []
    if set(reg.get("tolerance", {})) != TOLERANCE_KEYS:
        errors.append(f"tolerance must hold exactly {sorted(TOLERANCE_KEYS)}")
    screens = reg.get("screens", {})
    for name, row in screens.items():
        spec_file = frames_dir / f"{row.get('frame')}.spec.json"
        if not spec_file.exists():
            errors.append(f"{name}: frame {row.get('frame')} has no {spec_file.name}")
            continue
        spec = json.loads(spec_file.read_text())
        if row.get("theme") not in spec["themes"]:
            errors.append(f"{name}: frame {row['frame']} has no {row.get('theme')} theme")
        if not row.get("test"):
            errors.append(f"{name}: no test class")
        for mask in row.get("masks", []):
            if not (mask.get("name") and mask.get("reason") and mask.get("ref") and len(mask.get("rect", [])) == 4):
                errors.append(f"{name}: a mask needs name, reason, ref and a rect of x, y, w, h")
    for name, row in reg.get("known_violations", {}).items():
        if name not in screens:
            errors.append(f"known_violations: {name} is not a registered screen")
        if not isinstance(row.get("issue"), int):
            errors.append(f"known_violations: {name} needs an issue number")
    for name, row in reg.get("unregistered", {}).items():
        if name in screens:
            errors.append(f"unregistered: {name} is registered")
        if not (row.get("frame") and row.get("reason")):
            errors.append(f"unregistered: {name} needs a frame and a reason")
    for name, reason in reg.get("no_frame", {}).items():
        if name in screens or name in reg.get("unregistered", {}):
            errors.append(f"no_frame: {name} is also registered or unregistered")
        if not reason:
            errors.append(f"no_frame: {name} needs a reason")
    if golden_names is not None:
        named = set(screens) | set(reg.get("unregistered", {})) | set(reg.get("no_frame", {}))
        for name in sorted(golden_names - named):
            errors.append(f"golden {name}: no row in screens, unregistered or no_frame (an approved frame, or the reason it has none)")
        for name in sorted(named - golden_names):
            errors.append(f"registry names {name}, which is no golden of lumio/src/test/screenshots")
    return errors


def result_errors(reg, results_dir):
    errors, results = [], {}
    for name in reg.get("screens", {}):
        f = results_dir / f"{name}.json"
        if not f.exists():
            errors.append(f"{name}: the gate wrote no result (the test was skipped, filtered out or failed before it compared)")
            continue
        results[name] = json.loads(f.read_text())
        known = name in reg.get("known_violations", {})
        if results[name]["violation"] and not known:
            errors.append(f"{name}: a new violation ({len(results[name]['findings'])} differences)")
        if not results[name]["violation"] and known:
            errors.append(f"{name}: matches its frame now, so it leaves known_violations")
    return errors, results


def report(reg, results):
    lines = ["# Design gate", ""]
    bad = {n: r for n, r in results.items() if r["violation"]}
    lines += [f"{len(results)} screens compared with their approved frames: {len(results) - len(bad)} match, {len(bad)} differ "
              f"({sum(1 for n in bad if n in reg['known_violations'])} known violations).", ""]
    lines += ["| Screen | Frame | Theme | Differences | Issue |", "| :- | :- | :- | -: | :- |"]
    for n, r in sorted(results.items()):
        issue = reg["known_violations"].get(n, {}).get("issue")
        lines.append(f"| {n} | {r['frame']} | {r['theme']} | {len(r['findings'])} | {'#' + str(issue) if issue else ''} |")
    for n, r in sorted(bad.items()):
        lines += ["", f"## {n} against {r['frame']} ({r['theme']})", "", "| Layer | Element | Field | Expected | Actual |", "| :- | :- | :- | :- | :- |"]
        for f in r["findings"]:
            for d in f["diffs"]:
                lines.append(f"| {f['layer']} | {f['label']} | {d['field']} | {d['expected']} | {d['actual']} |".replace("\n", " "))
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--registry", default=str(ROOT / "design" / "registry.json"))
    ap.add_argument("--results", default=str(ROOT / "lumio" / "build" / "design-gate"))
    args = ap.parse_args()
    reg = json.loads(Path(args.registry).read_text())
    results_dir = Path(args.results)
    goldens = {p.stem for p in (ROOT / "lumio" / "src" / "test" / "screenshots").glob("*.png")}
    errors = registry_errors(reg, ROOT / "design" / "frames", goldens)
    more, results = result_errors(reg, results_dir)
    errors += more
    text = report(reg, results)
    results_dir.mkdir(parents=True, exist_ok=True)
    (results_dir / "report.md").write_text(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        # The summary holds the table and the screens that differ, cut at the 1 MiB the summary allows; the artifact holds all of it.
        with open(summary, "a") as out:
            out.write(text[:900_000])
    if errors:
        print("design gate failed:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"design gate: {len(results)} screens settled against their frames; the known violations are listed in design/registry.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())

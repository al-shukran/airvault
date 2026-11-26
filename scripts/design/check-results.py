#!/usr/bin/env python3
"""Reads what the design gate wrote (lumio/build/design-gate/*.json) and settles it against design/registry.json.

The Kotlin gate (lumio/src/test/java/app/lumio/ui/design/DesignGate.kt) fails a test on a new violation, on a known violation that now passes
and on a known violation that differs beyond its recorded baseline (the ratchet). This script repeats those from the results and adds what a
test cannot see: a registered screen that never ran (a skipped or filtered test is a failure), a registry that
contradicts itself or the frames, and a golden that no row names. It writes the per-element report to build/design-gate/report.md and the job
summary ($GITHUB_STEP_SUMMARY). Usage: check-results.py [--registry design/registry.json] [--results lumio/build/design-gate] [--record]
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOLERANCE_KEYS = {"bandDp", "textPosDp", "fontSizeDp", "lineHeightDp", "letterSpacingDp", "textColor", "sample", "cell", "regionCell", "cellDp", "ink", "iconColor"}
TOP_KEYS = {"version", "tolerance", "screens", "known_violations", "unregistered", "no_frame"}
ROW_KEYS = {"frame", "theme", "test", "insetBottom", "masks"}
KNOWN_KEYS = {"issue", "findings", "regions", "extra"}  # a known violation: its issue and its ratchet (see ratchet_errors)


def registry_errors(reg, frames_dir, golden_names):
    errors = []
    for key in sorted(set(reg) - TOP_KEYS):
        errors.append(f"registry key {key} is not one the gate reads, so it would switch nothing off but hide something: remove it")
    if set(reg.get("tolerance", {})) != TOLERANCE_KEYS:
        errors.append(f"tolerance must hold exactly {sorted(TOLERANCE_KEYS)}")
    screens = reg.get("screens", {})
    for name, row in screens.items():
        for key in sorted(set(row) - ROW_KEYS):
            errors.append(f"{name}: row key {key} is not one the gate reads: remove it")
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
        for key in sorted(set(row) - KNOWN_KEYS):
            errors.append(f"known_violations: {name} has key {key}, which the gate does not read: remove it")
        if not (isinstance(row.get("findings"), dict) and all(isinstance(v, list) and v and all(isinstance(x, str) for x in v) for v in row["findings"].values())):
            errors.append(f"known_violations: {name} needs `findings`, the fields it differs in per layer:element key")
        if not (isinstance(row.get("regions"), list) and all(isinstance(r, list) and len(r) == 5 for r in row["regions"])):
            errors.append(f"known_violations: {name} needs `regions`, the x, y, width, height and cells of each region")
        if not isinstance(row.get("extra"), int):
            errors.append(f"known_violations: {name} needs `extra`, a count")
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


def counted(finding):
    """The count a finding is ratcheted by when it has no stable element of the frame: a region, or a text the frame lacks. Else None."""
    if finding["layer"] == "region":
        return "regions"
    return "extra" if finding["layer"] == "text" and finding["element"] == "extra" else None


def fields_by_key(findings):
    """`layer:element` to the fields it differs in; a field's name has no place in it (`fill, top (12,34)` is `fill, top`)."""
    out = {}
    for f in findings:
        if counted(f) is None:
            out.setdefault(f"{f['layer']}:{f['element']}", set()).update(d["field"].split(" (")[0] for d in f["diffs"])
    return out


def inside(rect, outer, slack):
    return rect[0] >= outer[0] - slack and rect[1] >= outer[1] - slack and rect[0] + rect[2] <= outer[0] + outer[2] + slack and rect[1] + rect[3] <= outer[1] + outer[3] + slack


def ratchet_errors(known, findings, slack=4):
    """What a known violation may still differ in. Mirrors Baseline in DesignGate.kt: an element or a field that is not recorded, a region that is
    not inside a recorded one (up to `slack` dp) or holds more cells than it, a count above the recorded one, and a recorded element or field that now passes all fail, so
    the baseline shrinks with every fix and a regression cannot hide among a screen's other differences."""
    recorded = {k: set(v) for k, v in known.get("findings", {}).items()}
    now = fields_by_key(findings)
    errors = []
    if set(now) - set(recorded):
        errors.append("new differences, not in the baseline: " + ", ".join(sorted(set(now) - set(recorded))))
    if set(recorded) - set(now):
        errors.append("now match their frame, remove them from `findings` in known_violations: " + ", ".join(sorted(set(recorded) - set(now))))
    for key in sorted(set(now) & set(recorded)):
        if now[key] - recorded[key]:
            errors.append(f"{key} differs in a field that is not in the baseline: " + ", ".join(sorted(now[key] - recorded[key])))
        if recorded[key] - now[key]:
            errors.append(f"{key} now matches in a field that is in the baseline, remove it from `findings`: " + ", ".join(sorted(recorded[key] - now[key])))
    regions = [f for f in findings if counted(f) == "regions"]
    boxes = known.get("regions", [])
    if len(regions) > len(boxes):
        errors.append(f"regions: {len(regions)}, the baseline allows {len(boxes)}")
    for f in regions:
        r = f["rect"]
        if not any(inside(r, b, slack) and f["cells"] <= b[4] for b in boxes):
            errors.append(f"region {int(r[2])}x{int(r[3])} at {int(r[0])},{int(r[1])} with {f['cells']} cells is not inside a region of the baseline, or holds more cells than it")
    texts = sum(1 for f in findings if counted(f) == "extra")
    if texts > known.get("extra", 0):
        errors.append(f"extra texts: {texts}, the baseline allows {known.get('extra', 0)}")
    return errors


def result_errors(reg, results_dir):
    errors, results = [], {}
    for name in reg.get("screens", {}):
        f = results_dir / f"{name}.json"
        if not f.exists():
            errors.append(f"{name}: the gate wrote no result (the test was skipped, filtered out or failed before it compared)")
            continue
        results[name] = json.loads(f.read_text())
        row = reg["screens"][name]
        if (results[name]["frame"], results[name]["theme"]) != (row.get("frame"), row.get("theme")):
            errors.append(f"{name}: the gate compared {results[name]['frame']} ({results[name]['theme']}), the registry says {row.get('frame')} ({row.get('theme')})")
        known = name in reg.get("known_violations", {})
        if results[name]["violation"] and not known:
            errors.append(f"{name}: a new violation ({len(results[name]['findings'])} differences)")
        if not results[name]["violation"] and known:
            errors.append(f"{name}: matches its frame now, so it leaves known_violations")
        if results[name]["violation"] and known:
            errors += [f"{name}: {e}" for e in ratchet_errors(reg["known_violations"][name], results[name]["findings"], reg["tolerance"].get("cellDp", 4))]
    return errors, results


def record(reg_path, reg, results):
    """Writes the baseline of every known violation from the results: its keys and counts, as they are now. Run it after a fix, so the baseline
    shrinks; the guard lets a baseline that only shrinks through without the label, and a baseline that grows needs it."""
    for name, row in reg["known_violations"].items():
        if name in results and results[name]["violation"]:
            findings = results[name]["findings"]
            row["findings"] = {k: sorted(v) for k, v in sorted(fields_by_key(findings).items())}
            row["regions"] = [[int(v) for v in f["rect"]] + [f["cells"]] for f in findings if counted(f) == "regions"]
            row["extra"] = sum(1 for f in findings if counted(f) == "extra")
    text = json.dumps(reg, indent=1, ensure_ascii=False)
    # A list of plain values (a region's box, an element's fields) stays on one line, so a baseline is a line per region and per element.
    Path(reg_path).write_text(re.sub(r"\[\s+([^\[\]{}]*?)\s+\]", lambda m: "[" + re.sub(r"\s*\n\s*", " ", m.group(1)) + "]", text))


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
    ap.add_argument("--record", action="store_true", help="write the baseline of every known violation from the results, then exit")
    args = ap.parse_args()
    reg = json.loads(Path(args.registry).read_text())
    results_dir = Path(args.results)
    goldens = {p.stem for p in (ROOT / "lumio" / "src" / "test" / "screenshots").glob("*.png")}
    if args.record:
        record(args.registry, reg, {n: json.loads((results_dir / f"{n}.json").read_text()) for n in reg["known_violations"] if (results_dir / f"{n}.json").exists()})
        return 0
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

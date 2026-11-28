#!/usr/bin/env python3
"""Measures a screenshot golden against its approved frame, element by element, in dp.

The frames are 360 x 740 px at 1 px = 1 dp; the goldens are drawn at 1.75 px per dp. For each element of the frame (the elements of the frame's
spec.json) the script takes the first 20 dp of its width as a vertical strip, finds the run of ink rows in the strip that holds the element's
middle row (ink is a pixel more than 12 steps from the background in any channel), and finds the run of the golden's strip that is nearest to it.
It prints the top and the bottom of both runs and their difference, in dp. A text's run is its glyphs' ink, not its line box, so the same text
gives the same run in the frame and the golden, whatever the browser's line box. The frame's runs are in bands.json, so the frames need not be
kept. Exit status 1 when a run's middle (the mean of its top and bottom difference, which is steady where a glyph's edge pixel is not) is more than --tol dp (default 1) away, or the golden has no run near it.

  measure-bands.py profile_panel_dark lumio/src/test/screenshots/profile_panel_dark.png
  scripts/design/render-at-density.sh 1 '*ExploreScreenshotTest*' '*ProfilePanelScreenshotTest*'   (renders at 1 px = 1 dp into build/density-1/)
  measure-bands.py --scale 1 explore build/density-1/explore.png                                   (the same at density 2 with "2" and --scale 2)

A screen is gated at density 1 (the frame's own scale) and at density 2, so a .5 px rounding that falls differently at the phone's 1.75 shows. Scale 3 and up
is not supported: runs() merges two runs of ink whose gap is under round(scale) px, which at 3 px per dp joins a line with its neighbour.
  measure-bands.py --build p1-02-dark.png p1-02.spec.json dark --ref 21,19,23 --ymax 720   (prints the entry of bands.json for a frame)
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

THRESHOLD = 12
SCALE = 1.75
STRIP = 20
NEAR = 8


def runs(png, scale, x0, x1, y_max, ref):
    """The (top, bottom) in dp of each run of ink rows in the columns x0 to x1 (dp) above y_max (dp)."""
    px = np.asarray(Image.open(png).convert("RGB")).astype(int)[: int(y_max * scale)]
    strip = px[:, int(x0 * scale): max(int(x1 * scale), int(x0 * scale) + 1), :]
    base = px[:, 2:3, :] if ref is None else np.array(ref).reshape(1, 1, 3)
    rows = np.where((np.abs(strip - base).max(axis=2) > THRESHOLD).any(axis=1))[0]
    if not len(rows):
        return []
    out, start, prev = [], rows[0], rows[0]
    for y in rows[1:]:
        if y - prev - 1 >= max(1, round(scale)):
            out.append((start, prev))
            start = y
        prev = y
    out.append((start, prev))
    return [(a / scale, (b + 1) / scale) for a, b in out]


def build(png, spec, theme, ref, y_max):
    elements = json.loads(Path(spec).read_text())["themes"][theme]["elements"]
    out = []
    for e in elements:
        r = e["rect"]
        if r["w"] > 300 or r["h"] > 300:
            continue
        mid = r["y"] + r["h"] / 2
        hit = [run for run in runs(png, 1, r["x"], r["x"] + min(r["w"], STRIP), y_max, ref) if run[0] <= mid < run[1]]
        if hit:
            out.append({"id": e["id"], "what": e.get("text") or e["kind"], "x": round(r["x"], 2), "top": hit[0][0], "bottom": hit[0][1]})
    return {"ref": ref, "ymax": y_max, "elements": out}


def main():
    here = Path(__file__).with_name("bands.json")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("key", nargs="?", help="the entry of bands.json to measure against")
    ap.add_argument("golden", nargs="?", help="the golden PNG")
    ap.add_argument("--tol", type=float, default=1.0)
    ap.add_argument("--scale", type=float, default=SCALE, help="px per dp of the golden: 1.75, or 1 for a render at 1 px = 1 dp")
    ap.add_argument("--build", nargs=3, metavar=("FRAME_PNG", "SPEC_JSON", "THEME"))
    ap.add_argument("--ref", help="r,g,b of the frame's background; the pixel at the left edge of each row when absent")
    ap.add_argument("--ymax", type=float, default=740)
    a = ap.parse_args()
    ref = [int(v) for v in a.ref.split(",")] if a.ref else None
    if a.build:
        print(json.dumps(build(*a.build[:2], a.build[2], ref, a.ymax)))
        return 0
    entry = json.loads(here.read_text())[a.key]
    print(f"{'id':<4} {'element':<24} {'frame':>13} {'golden':>15} {'d top':>6} {'d bottom':>9} {'d mid':>6}")
    bad = 0
    for e in entry["elements"]:
        found = runs(a.golden, a.scale, e["x"], e["x"] + STRIP, entry["ymax"], entry["ref"])
        mid = (e["top"] + e["bottom"]) / 2
        near = min(found, key=lambda run: abs((run[0] + run[1]) / 2 - mid), default=None)
        label = f"{e['id']:<4} {e['what'][:24]:<24} {e['top']:>6.1f}-{e['bottom']:<6.1f}"
        if near is None or abs((near[0] + near[1]) / 2 - mid) > NEAR:
            print(f"{label} no run of the golden within {NEAR} dp  <-- over")
            bad += 1
            continue
        dt, db = near[0] - e["top"], near[1] - e["bottom"]
        dm = (dt + db) / 2
        flag = "  <-- over" if abs(dm) > a.tol else ""
        bad += bool(flag)
        print(f"{label} {near[0]:>7.2f}-{near[1]:<7.2f} {dt:>+6.2f} {db:>+9.2f} {dm:>+6.2f}{flag}")
    print(f"{a.key}: {bad} of {len(entry['elements'])} elements more than {a.tol} dp off")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

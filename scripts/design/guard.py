#!/usr/bin/env python3
"""Fails a pull request that changes what the design gate measures against, unless it carries the label design:approved-change.

Guarded: any file under design/frames/, the tolerance and the masks in design/registry.json, the frame, theme and bottom inset of an
existing screen row, and any ADDED entry in known_violations, unregistered or no_frame. Removing an entry is always free: those lists only
shrink, and a new screen row only has to pass. Env: BASE_SHA, PR_NUMBER, GH_REPO and GH_TOKEN (the labels are read when the job runs, so a
label added after the push counts on a re-run), or LABELS (a space-separated list, for tests and local runs).
"""
import json
import os
import subprocess
import sys

LABEL = "design:approved-change"
LISTS = ("known_violations", "unregistered", "no_frame")


def run(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def changes(base, head, changed_files):
    """The guarded changes between two registries, as readable lines."""
    out = [f"{f} changed" for f in changed_files if f.startswith("design/frames/")]
    if base.get("tolerance") != head.get("tolerance"):
        out.append("design/registry.json: tolerance changed")
    for name, row in head.get("screens", {}).items():
        old = base.get("screens", {}).get(name)
        if old is None:
            continue
        if old.get("masks", []) != row.get("masks", []):
            out.append(f"design/registry.json: masks of {name} changed")
        for key in ("frame", "theme", "insetBottom"):
            if old.get(key) != row.get(key):
                out.append(f"design/registry.json: {key} of {name} changed")
    for key in LISTS:
        for name in sorted(set(head.get(key, {})) - set(base.get(key, {}))):
            out.append(f"design/registry.json: {name} added to {key}")
    return out


def labels():
    if "LABELS" in os.environ:
        return os.environ["LABELS"].split()
    path = f"repos/{os.environ['GH_REPO']}/issues/{os.environ['PR_NUMBER']}/labels"
    return run("gh", "api", path, "--jq", ".[].name").split()


def main():
    base_sha = os.environ["BASE_SHA"]
    files = run("git", "diff", "--name-only", f"{base_sha}...HEAD").split()
    try:
        base = json.loads(run("git", "show", f"{base_sha}:design/registry.json"))
    except subprocess.CalledProcessError:
        base = {}
    head = json.load(open("design/registry.json"))
    found = changes(base, head, files)
    if not found:
        print("design guard: nothing the gate measures against changed")
        return 0
    if LABEL in labels():
        print(f"design guard: {len(found)} guarded change(s), allowed by the label {LABEL}")
        return 0
    print(f"design guard: this pull request changes what the design gate measures against, and it has no label {LABEL}:")
    for line in found:
        print(f"  - {line}")
    print(f"A screen is fixed to match its frame; a frame, tolerance, mask or known violation changes only with the owner's approval (label {LABEL}).")
    return 1


if __name__ == "__main__":
    sys.exit(main())

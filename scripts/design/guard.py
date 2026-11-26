#!/usr/bin/env python3
"""Fails a pull request that changes what the design gate measures against, unless it carries the label design:approved-change.

The whole of design/registry.json is compared with the merge base. Free without the label: removing an entry from known_violations, unregistered,
no_frame or from a screen's masks (the lists only shrink), a known violation's baseline that only shrinks (fewer keys in `findings`, no higher
`regions` or `extra`, the same issue), and adding a screen row that carries no masks or exclusions and no key but frame, theme, test and
insetBottom. Everything else needs the label: any file under design/frames/, the gate's own code and the workflow and scripts that run it, a
changed tolerance, a removed or renamed screen, a changed row, an added entry in a list, a baseline that grows, a new mask, and any key the
registry did not have. Env: BASE_SHA, PR_NUMBER, GH_REPO and GH_TOKEN (the labels are read when the job runs, so a label added after the push
counts on a re-run), or LABELS (a space-separated list, for tests and local runs).

The job runs this file as it is at BASE_SHA (`git show $BASE_SHA:scripts/design/guard.py | python3 -I -`), so a pull request cannot edit the guard
that judges it; only while the base has no guard (the pull request that adds it) does the job run the checked-out copy.
"""
import json
import os
import subprocess
import sys

LABEL = "design:approved-change"
LISTS = ("known_violations", "unregistered", "no_frame")  # entries are free to remove, never to add or change
RELAXING = ("masks", "exclusions")  # a screen row's lists that switch a region off: free to shrink, never to grow
NEW_ROW_KEYS = {"frame", "theme", "test", "insetBottom"}
RATCHET = ("findings", "regions", "extra")  # a known violation's baseline (scripts/design/check-results.py): free to shrink, never to grow
# The gate's frames and code, and what runs it: the workflow that makes `design` and `design_guard` part of pr-ok, the one that re-runs the guard
# on a label change, the lane, the check of those guards and the hook that swaps the golden for the gate's render.
GUARDED_PATHS = (
    "design/frames/",
    "scripts/design/",
    "lumio/src/test/java/app/lumio/ui/design/",
    "lumio/src/test/java/app/lumio/ui/screenshot/LumioSnapshot.kt",
    "tools/design-frames/render.py",
    ".github/workflows/pr-fast.yml",
    ".github/workflows/design-guard-rerun.yml",
    "scripts/ci/run-partition.sh",
    "scripts/ci/check-workflow-guards.sh",
)


def run(*cmd):
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def row_changes(name, old, row):
    out = []
    for key in sorted(set(old) | set(row)):
        a, b = old.get(key), row.get(key)
        if a == b:
            continue
        if key in RELAXING and isinstance(a or [], list) and isinstance(b or [], list) and all(m in (a or []) for m in (b or [])):
            continue
        out.append(f"design/registry.json: {key} of {name} changed")
    return out


def baseline_shrinks(old, new):
    """True when a known violation's entry changed only by dropping keys from `findings` or lowering a count."""
    if {k: v for k, v in old.items() if k not in RATCHET} != {k: v for k, v in new.items() if k not in RATCHET}:
        return False
    return set(new.get("findings", [])) <= set(old.get("findings", [])) and all(new.get(k, 0) <= old.get(k, 0) for k in RATCHET[1:])


def changes(base, head, changed_files):
    """The guarded changes between two registries, as readable lines."""
    out = [f"{f} changed" for f in changed_files if f.startswith(GUARDED_PATHS)]
    for key in sorted((set(base) | set(head)) - {"screens", *LISTS}):
        if base.get(key) != head.get(key):
            out.append(f"design/registry.json: {key} changed")
    base_screens, head_screens = base.get("screens", {}), head.get("screens", {})
    for name in sorted(set(base_screens) - set(head_screens)):
        out.append(f"design/registry.json: screen {name} removed or renamed")
    for name, row in head_screens.items():
        old = base_screens.get(name)
        if old is None:
            extra = sorted(k for k in row if k not in NEW_ROW_KEYS and not (k in RELAXING and not row[k]))
            if extra:
                out.append(f"design/registry.json: new screen {name} carries {', '.join(extra)}")
        else:
            out += row_changes(name, old, row)
    for key in LISTS:
        before, after = base.get(key, {}), head.get(key, {})
        for name in sorted(set(after) - set(before)):
            out.append(f"design/registry.json: {name} added to {key}")
        for name in sorted(set(after) & set(before)):
            if after[name] != before[name] and not (key == "known_violations" and baseline_shrinks(before[name], after[name])):
                out.append(f"design/registry.json: {name} changed in {key}")
    return out


def labels():
    if "LABELS" in os.environ:
        return os.environ["LABELS"].split()
    path = f"repos/{os.environ['GH_REPO']}/issues/{os.environ['PR_NUMBER']}/labels"
    return run("gh", "api", path, "--jq", ".[].name").split()


def changed_files(merge_base):
    # --no-renames lists both paths of a rename, so a file moved out of a guarded path is still seen.
    return run("git", "diff", "--name-only", "--no-renames", f"{merge_base}...HEAD").split()


def main():
    merge_base = run("git", "merge-base", os.environ["BASE_SHA"], "HEAD").strip()
    files = changed_files(merge_base)
    try:
        base = json.loads(run("git", "show", f"{merge_base}:design/registry.json"))
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

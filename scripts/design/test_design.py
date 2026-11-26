#!/usr/bin/env python3
"""Tests of guard.py and check-results.py. Run by the ci lane: python3 scripts/design/test_design.py (stdlib only)."""
import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


guard = load("guard", "guard.py")
check = load("check_results", "check-results.py")

BASE = {
    "tolerance": {"bandDp": 1},
    "screens": {"a": {"frame": "f1", "theme": "dark", "masks": []}},
    "known_violations": {"a": {"issue": 1}},
    "unregistered": {},
    "no_frame": {},
}


def head(**edit):
    import copy
    out = copy.deepcopy(BASE)
    out.update(edit)
    return out


def screens(**rows):
    return {**BASE["screens"], **rows}


class GuardTest(unittest.TestCase):
    def test_nothing_guarded_changes(self):
        self.assertEqual(guard.changes(BASE, head(), ["lumio/src/main/X.kt"]), [])

    def test_a_removed_known_violation_is_free(self):
        self.assertEqual(guard.changes(BASE, head(known_violations={}), []), [])

    def test_an_added_known_violation_is_guarded(self):
        got = guard.changes(BASE, head(screens=screens(b={"frame": "f2", "theme": "dark"}), known_violations={"a": {"issue": 1}, "b": {"issue": 2}}), [])
        self.assertEqual(got, ["design/registry.json: b added to known_violations"])

    def test_a_changed_known_violation_entry_is_guarded(self):
        self.assertEqual(guard.changes(BASE, head(known_violations={"a": {"issue": 9}}), []), ["design/registry.json: a changed in known_violations"])

    def test_a_loosened_tolerance_is_guarded(self):
        self.assertEqual(guard.changes(BASE, head(tolerance={"bandDp": 3}), []), ["design/registry.json: tolerance changed"])

    def test_a_new_mask_is_guarded(self):
        got = guard.changes(BASE, head(screens={"a": {"frame": "f1", "theme": "dark", "masks": [{"name": "x"}]}}), [])
        self.assertEqual(got, ["design/registry.json: masks of a changed"])

    def test_a_removed_mask_is_free(self):
        base = head(screens={"a": {"frame": "f1", "theme": "dark", "masks": [{"name": "x"}, {"name": "y"}]}})
        self.assertEqual(guard.changes(base, head(screens={"a": {"frame": "f1", "theme": "dark", "masks": [{"name": "x"}]}}), []), [])

    def test_a_remapped_screen_is_guarded(self):
        got = guard.changes(BASE, head(screens={"a": {"frame": "f9", "theme": "dark", "masks": []}}), [])
        self.assertEqual(got, ["design/registry.json: frame of a changed"])

    def test_a_changed_frame_file_is_guarded(self):
        self.assertEqual(guard.changes(BASE, head(), ["design/frames/p1-00.png"]), ["design/frames/p1-00.png changed"])

    def test_a_rename_out_of_a_guarded_path_is_guarded(self):
        with tempfile.TemporaryDirectory() as tmp:
            def git(*args):
                return subprocess.run(["git", "-C", tmp, "-c", "user.name=t", "-c", "user.email=t@t", *args], check=True, capture_output=True, text=True).stdout
            git("init", "-q")
            Path(tmp, "design/frames").mkdir(parents=True)
            Path(tmp, "design/frames/p1-00.png").write_text("frame bytes " * 20)
            git("add", "-A")
            git("commit", "-q", "-m", "base")
            base = git("rev-parse", "HEAD").strip()
            Path(tmp, "other").mkdir()
            git("mv", "design/frames/p1-00.png", "other/p1-00.png")
            git("commit", "-q", "-m", "move")
            cwd = os.getcwd()
            os.chdir(tmp)
            try:
                files = guard.changed_files(base)
            finally:
                os.chdir(cwd)
        self.assertEqual(guard.changes(BASE, head(), files), ["design/frames/p1-00.png changed"])

    def test_the_gates_own_code_is_guarded(self):
        for path in ("scripts/design/check-results.py", "lumio/src/test/java/app/lumio/ui/design/DesignGate.kt", "tools/design-frames/render.py"):
            self.assertEqual(guard.changes(BASE, head(), [path]), [f"{path} changed"])

    def test_what_runs_the_gate_is_guarded(self):
        # The workflow that puts the lane in pr-ok, the lane itself, the check of those guards and the hook that swaps the golden for the gate's render.
        for path in (
            ".github/workflows/pr-fast.yml",
            ".github/workflows/design-guard-rerun.yml",
            "scripts/ci/run-partition.sh",
            "scripts/ci/check-workflow-guards.sh",
            "lumio/src/test/java/app/lumio/ui/screenshot/LumioSnapshot.kt",
        ):
            self.assertEqual(guard.changes(BASE, head(), [path]), [f"{path} changed"])

    def test_other_workflows_and_ci_scripts_are_free(self):
        for path in (".github/workflows/nightly.yml", "scripts/ci/shards.txt", "scripts/ci/README.md"):
            self.assertEqual(guard.changes(BASE, head(), [path]), [])

    def test_a_baseline_that_only_shrinks_is_free(self):
        base = head(known_violations={"a": {"issue": 1, "findings": ["text:e1", "box:e2"], "regions": 3, "extra": 1}})
        shrunk = head(known_violations={"a": {"issue": 1, "findings": ["box:e2"], "regions": 2, "extra": 1}})
        self.assertEqual(guard.changes(base, shrunk, []), [])
        self.assertEqual(guard.changes(base, base, []), [])

    def test_a_baseline_that_grows_is_guarded(self):
        base = head(known_violations={"a": {"issue": 1, "findings": ["text:e1"], "regions": 3, "extra": 1}})
        for grown in (
            {"issue": 1, "findings": ["text:e1", "icon:e9"], "regions": 3, "extra": 1},
            {"issue": 1, "findings": ["text:e1"], "regions": 4, "extra": 1},
            {"issue": 1, "findings": ["text:e1"], "regions": 3, "extra": 2},
            {"issue": 1, "findings": ["text:e1", "icon:e9"], "regions": 1, "extra": 0},
        ):
            self.assertEqual(guard.changes(base, head(known_violations={"a": grown}), []), ["design/registry.json: a changed in known_violations"], grown)

    def test_a_shrinking_baseline_with_another_issue_is_guarded(self):
        base = head(known_violations={"a": {"issue": 1, "findings": ["text:e1"], "regions": 3, "extra": 1}})
        other = head(known_violations={"a": {"issue": 9, "findings": [], "regions": 0, "extra": 0}})
        self.assertEqual(guard.changes(base, other, []), ["design/registry.json: a changed in known_violations"])

    def test_a_new_screen_row_without_masks_is_free(self):
        self.assertEqual(guard.changes(BASE, head(screens=screens(b={"frame": "f2", "theme": "dark", "test": "T", "masks": []})), []), [])

    def test_a_new_screen_row_with_masks_is_guarded(self):
        got = guard.changes(BASE, head(screens=screens(b={"frame": "f2", "theme": "dark", "masks": [{"name": "x"}]})), [])
        self.assertEqual(got, ["design/registry.json: new screen b carries masks"])

    def test_a_new_screen_row_with_exclusions_or_another_key_is_guarded(self):
        got = guard.changes(BASE, head(screens=screens(b={"frame": "f2", "theme": "dark", "exclusions": [1], "skip": True})), [])
        self.assertEqual(got, ["design/registry.json: new screen b carries exclusions, skip"])

    def test_a_removed_screen_is_guarded(self):
        self.assertEqual(guard.changes(BASE, head(screens={}), []), ["design/registry.json: screen a removed or renamed"])

    def test_a_renamed_screen_is_guarded(self):
        got = guard.changes(BASE, head(screens={"z": {"frame": "f1", "theme": "dark"}}), [])
        self.assertEqual(got, ["design/registry.json: screen a removed or renamed"])

    def test_a_new_top_level_key_is_guarded(self):
        self.assertEqual(guard.changes(BASE, head(ignore={"a": 1}), []), ["design/registry.json: ignore changed"])

    def test_a_screen_moved_to_no_frame_is_guarded(self):
        self.assertEqual(guard.changes(BASE, head(no_frame={"c": "kit"}), []), ["design/registry.json: c added to no_frame"])


class ResultsTest(unittest.TestCase):
    def registry(self):
        return {
            "tolerance": {k: 1 for k in check.TOLERANCE_KEYS},
            "screens": {"a": {"frame": "f1", "theme": "dark", "test": "T"}, "b": {"frame": "f1", "theme": "dark", "test": "T"}},
            "known_violations": {"b": {"issue": 2, "findings": ["text:e1", "box:e2"], "regions": 2, "extra": 1}},
            "unregistered": {"c": {"frame": "f1", "reason": "r"}},
            "no_frame": {},
        }

    RECORDED = [("text", "e1"), ("box", "e2"), ("region", "region0"), ("region", "region1"), ("text", "extra")]

    def results(self, found=None, **violations):
        """A result file per screen; a violating screen has the differences [found] (default: what the registry records for b)."""
        import json
        tmp = Path(tempfile.mkdtemp())
        for name, bad in violations.items():
            findings = [{"layer": layer, "element": element} for layer, element in (found or self.RECORDED)] if bad else []
            (tmp / f"{name}.json").write_text(json.dumps({"violation": bad, "findings": findings, "frame": "f1", "theme": "dark"}))
        return tmp

    def test_a_screen_that_never_ran_fails(self):
        errors, _ = check.result_errors(self.registry(), self.results(b=True))
        self.assertEqual(len(errors), 1)
        self.assertIn("a: the gate wrote no result", errors[0])

    def test_a_new_violation_fails(self):
        errors, _ = check.result_errors(self.registry(), self.results(a=True, b=True))
        self.assertEqual(len(errors), 1)
        self.assertIn("a: a new violation", errors[0])

    def test_a_known_violation_that_passes_fails(self):
        errors, _ = check.result_errors(self.registry(), self.results(a=False, b=False))
        self.assertEqual(len(errors), 1)
        self.assertIn("b: matches its frame now", errors[0])

    def test_the_honest_state_passes(self):
        errors, _ = check.result_errors(self.registry(), self.results(a=False, b=True))
        self.assertEqual(errors, [])

    def test_a_new_difference_on_a_known_violation_fails(self):
        errors, _ = check.result_errors(self.registry(), self.results(found=self.RECORDED + [("icon", "e9")], a=False, b=True))
        self.assertEqual(len(errors), 1)
        self.assertIn("b: new differences, not in the baseline: icon:e9", errors[0])

    def test_more_regions_or_extra_texts_on_a_known_violation_fail(self):
        errors, _ = check.result_errors(self.registry(), self.results(found=self.RECORDED + [("region", "region2")], a=False, b=True))
        self.assertEqual(errors, ["b: regions: 3, the baseline allows 2"])
        errors, _ = check.result_errors(self.registry(), self.results(found=self.RECORDED + [("text", "extra")], a=False, b=True))
        self.assertEqual(errors, ["b: extra texts: 2, the baseline allows 1"])

    def test_a_recorded_difference_that_passes_now_fails(self):
        errors, _ = check.result_errors(self.registry(), self.results(found=[d for d in self.RECORDED if d != ("text", "e1")], a=False, b=True))
        self.assertEqual(len(errors), 1)
        self.assertIn("b: now match their frame, remove them from `findings` in known_violations: text:e1", errors[0])

    def test_fewer_regions_than_recorded_pass(self):
        errors, _ = check.result_errors(self.registry(), self.results(found=[d for d in self.RECORDED if d[1] != "region1"], a=False, b=True))
        self.assertEqual(errors, [])

    def test_recording_writes_the_baseline_from_the_results(self):
        import json
        reg = self.registry()
        reg["known_violations"]["b"] = {"issue": 2}
        path = Path(tempfile.mkdtemp()) / "registry.json"
        results = {"b": {"violation": True, "findings": [{"layer": l, "element": e} for l, e in self.RECORDED]}}
        check.record(path, reg, results)
        self.assertEqual(json.loads(path.read_text())["known_violations"]["b"], {"issue": 2, "findings": ["box:e2", "text:e1"], "regions": 2, "extra": 1})

    def test_a_known_violation_without_a_baseline_fails(self):
        frames = Path(tempfile.mkdtemp())
        (frames / "f1.spec.json").write_text('{"themes": {"dark": {}}}')
        reg = self.registry()
        reg["known_violations"]["b"] = {"issue": 2}
        errors = check.registry_errors(reg, frames, None)
        self.assertEqual(len(errors), 3)
        self.assertTrue(all(e.startswith("known_violations: b needs") for e in errors))

    def test_a_result_for_another_frame_fails(self):
        tmp = self.results(a=False, b=True)
        (tmp / "a.json").write_text('{"violation": false, "findings": [], "frame": "f9", "theme": "dark"}')
        errors, _ = check.result_errors(self.registry(), tmp)
        self.assertEqual(len(errors), 1)
        self.assertIn("a: the gate compared f9", errors[0])

    def test_a_key_the_gate_does_not_read_fails(self):
        frames = Path(tempfile.mkdtemp())
        (frames / "f1.spec.json").write_text('{"themes": {"dark": {}}}')
        reg = self.registry()
        reg["ignore"] = {"a": 1}
        reg["screens"]["a"]["skip"] = True
        errors = check.registry_errors(reg, frames, None)
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("registry key ignore" in e for e in errors) and any("a: row key skip" in e for e in errors))

    def test_a_golden_with_no_row_fails(self):
        frames = Path(tempfile.mkdtemp())
        (frames / "f1.spec.json").write_text('{"themes": {"dark": {}}}')
        errors = check.registry_errors(self.registry(), frames, {"a", "b", "c", "d"})
        self.assertEqual(len(errors), 1)
        self.assertIn("golden d: no row", errors[0])


if __name__ == "__main__":
    unittest.main()

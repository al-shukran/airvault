#!/usr/bin/env python3
"""Tests of guard.py and check-results.py. Run by the ci lane: python3 scripts/design/test_design.py (stdlib only)."""
import importlib.util
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

    def test_the_gates_own_code_is_guarded(self):
        for path in ("scripts/design/check-results.py", "lumio/src/test/java/app/lumio/ui/design/DesignGate.kt", "tools/design-frames/render.py"):
            self.assertEqual(guard.changes(BASE, head(), [path]), [f"{path} changed"])

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
            "known_violations": {"b": {"issue": 2}},
            "unregistered": {"c": {"frame": "f1", "reason": "r"}},
            "no_frame": {},
        }

    def results(self, **violations):
        tmp = Path(tempfile.mkdtemp())
        for name, bad in violations.items():
            import json
            (tmp / f"{name}.json").write_text(json.dumps({"violation": bad, "findings": [1] if bad else [], "frame": "f1", "theme": "dark"}))
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

    def test_a_golden_with_no_row_fails(self):
        frames = Path(tempfile.mkdtemp())
        (frames / "f1.spec.json").write_text('{"themes": {"dark": {}}}')
        errors = check.registry_errors(self.registry(), frames, {"a", "b", "c", "d"})
        self.assertEqual(len(errors), 1)
        self.assertIn("golden d: no row", errors[0])


if __name__ == "__main__":
    unittest.main()

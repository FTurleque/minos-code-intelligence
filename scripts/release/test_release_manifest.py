#!/usr/bin/env python3
"""Tests of the Authenticode state of the release manifest (AUD-DEP-06): written by create-release-manifest.py, never
presumed, and refused by check-supply-chain.py when it is missing or contradicts the requirement."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

RELEASE = Path(__file__).resolve().parent


def load(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), RELEASE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


creator = load("create-release-manifest")
checker = load("check-supply-chain")


class CreateManifestTest(unittest.TestCase):

    def create(self, *authenticode: str) -> tuple[int, dict | None, str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "VERSION").write_text("version=1.0.0\n", encoding="ascii")
            output = root / "RELEASE-MANIFEST.json"
            result = subprocess.run(
                [sys.executable, str(RELEASE / "create-release-manifest.py"), "--distribution", str(root),
                 "--version", "1.0.0", "--commit", "0" * 40, "--output", str(output), *authenticode],
                capture_output=True, text=True, check=False)
            manifest = json.loads(output.read_text(encoding="utf-8")) if output.is_file() else None
            return result.returncode, manifest, result.stderr

    def test_an_unrequired_signature_is_recorded_as_unsigned(self):
        code, manifest, _ = self.create("--authenticode-required", "0", "--authenticode-state", "unsigned")
        self.assertEqual(0, code)
        self.assertEqual({"required": False, "state": "unsigned"}, manifest["authenticode"])
        self.assertIsNone(checker.authenticode_problem(manifest))

    def test_a_required_signature_is_recorded_as_signed(self):
        code, manifest, _ = self.create("--authenticode-required", "1", "--authenticode-state", "signed")
        self.assertEqual(0, code)
        self.assertEqual({"required": True, "state": "signed"}, manifest["authenticode"])

    def test_the_state_is_never_presumed(self):
        code, manifest, stderr = self.create()
        self.assertNotEqual(0, code)
        self.assertIsNone(manifest)
        self.assertIn("--authenticode-required", stderr)

    def test_a_state_that_contradicts_the_requirement_is_refused(self):
        for arguments in (("1", "unsigned"), ("0", "signed")):
            code, manifest, stderr = self.create("--authenticode-required", arguments[0],
                                                 "--authenticode-state", arguments[1])
            self.assertNotEqual(0, code, arguments)
            self.assertIsNone(manifest)
            self.assertIn("contradicts", stderr)


class CheckManifestTest(unittest.TestCase):

    def test_a_manifest_without_the_state_is_refused(self):
        self.assertIn("does not record its Authenticode state", checker.authenticode_problem({}))
        self.assertIn("does not record its Authenticode state",
                      checker.authenticode_problem({"authenticode": {"state": "unsigned"}}))

    def test_an_unknown_state_or_a_non_boolean_requirement_is_refused(self):
        self.assertIn("invalid", checker.authenticode_problem({"authenticode": {"required": False, "state": "partial"}}))
        self.assertIn("invalid", checker.authenticode_problem({"authenticode": {"required": "no", "state": "unsigned"}}))

    def test_a_contradiction_is_refused(self):
        self.assertIn("contradicts", checker.authenticode_problem({"authenticode": {"required": True, "state": "unsigned"}}))

    def test_a_consistent_state_is_accepted(self):
        self.assertIsNone(checker.authenticode_problem({"authenticode": {"required": True, "state": "signed"}}))


if __name__ == "__main__":
    unittest.main()

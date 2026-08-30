#!/usr/bin/env python3
"""Unit tests for the pure decision logic in check-docker-upgrade-evidence.py.

Only select_evidence() and validate_manifest() are exercised here: they take plain data
structures and return a plain result, with no gh/subprocess/network calls, so they are fully
testable without real GitHub or Docker infrastructure. find_evidence()/main() are the I/O-driving
wrappers around them and are exercised for real every time the Release Promotion Gate workflow
actually runs - that end-to-end execution is stronger evidence than a mock could provide.
"""

from __future__ import annotations

import unittest

from importlib import util as importlib_util
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-docker-upgrade-evidence.py"
_SPEC = importlib_util.spec_from_file_location("check_docker_upgrade_evidence", _MODULE_PATH)
_MODULE = importlib_util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)

select_evidence = _MODULE.select_evidence
validate_manifest = _MODULE.validate_manifest

CANDIDATE = "a" * 40
OTHER_SHA = "b" * 40
PREVIOUS = "c" * 40
WRONG_PREVIOUS = "d" * 40


def artifact(name: str, expired: bool = False, head_sha: str = CANDIDATE, artifact_id: int = 1) -> dict:
    return {"id": artifact_id, "name": name, "expired": expired, "workflow_run": {"id": 100, "head_sha": head_sha}}


class SelectEvidenceTest(unittest.TestCase):
    def test_matches_exact_candidate_prefix(self):
        matches = select_evidence([artifact(f"docker-upgrade-{CANDIDATE}-1")], CANDIDATE)
        self.assertEqual(len(matches), 1)

    def test_rejects_artifact_for_a_different_sha(self):
        matches = select_evidence([artifact(f"docker-upgrade-{OTHER_SHA}-1", head_sha=OTHER_SHA)], CANDIDATE)
        self.assertEqual(matches, [])

    def test_rejects_expired_artifact(self):
        matches = select_evidence([artifact(f"docker-upgrade-{CANDIDATE}-1", expired=True)], CANDIDATE)
        self.assertEqual(matches, [])

    def test_rejects_when_run_head_sha_does_not_match_candidate_despite_matching_name(self):
        # Defense in depth: the artifact name alone (attacker- or bug-controllable in principle)
        # is never trusted without the run metadata agreeing.
        matches = select_evidence([artifact(f"docker-upgrade-{CANDIDATE}-1", head_sha=OTHER_SHA)], CANDIDATE)
        self.assertEqual(matches, [])

    def test_ignores_unrelated_artifacts(self):
        matches = select_evidence([artifact("pr-validation-Linux-1"), artifact("docker-release-xyz-1")], CANDIDATE)
        self.assertEqual(matches, [])

    def test_no_artifacts_at_all(self):
        self.assertEqual(select_evidence([], CANDIDATE), [])


class ValidateManifestTest(unittest.TestCase):
    def valid_manifest(self) -> dict:
        return {"formatVersion": 1, "previous": PREVIOUS, "candidate": CANDIDATE, "result": "PASS"}

    def test_accepts_a_valid_matching_manifest(self):
        self.assertIsNone(validate_manifest(self.valid_manifest(), CANDIDATE, None))

    def test_accepts_when_previous_matches_expected(self):
        self.assertIsNone(validate_manifest(self.valid_manifest(), CANDIDATE, PREVIOUS))

    def test_rejects_missing_manifest(self):
        self.assertIsNotNone(validate_manifest(None, CANDIDATE, None))

    def test_rejects_candidate_mismatch(self):
        manifest = self.valid_manifest()
        manifest["candidate"] = OTHER_SHA
        error = validate_manifest(manifest, CANDIDATE, None)
        self.assertIsNotNone(error)
        self.assertIn("candidate", error)

    def test_rejects_non_pass_result(self):
        manifest = self.valid_manifest()
        manifest["result"] = "FAIL"
        error = validate_manifest(manifest, CANDIDATE, None)
        self.assertIsNotNone(error)
        self.assertIn("PASS", error)

    def test_rejects_missing_result_field(self):
        manifest = self.valid_manifest()
        del manifest["result"]
        self.assertIsNotNone(validate_manifest(manifest, CANDIDATE, None))

    def test_rejects_wrong_previous_when_expected_previous_given(self):
        manifest = self.valid_manifest()
        error = validate_manifest(manifest, CANDIDATE, WRONG_PREVIOUS)
        self.assertIsNotNone(error)
        self.assertIn("previous", error)

    def test_ignores_previous_field_when_no_expectation_given(self):
        manifest = self.valid_manifest()
        manifest["previous"] = WRONG_PREVIOUS
        self.assertIsNone(validate_manifest(manifest, CANDIDATE, None))


if __name__ == "__main__":
    unittest.main()

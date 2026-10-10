#!/usr/bin/env python3
"""Self-test of verify-ruleset.py on recorded payloads: no network, no gh."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_MODULE_PATH = Path(__file__).parent / "verify-ruleset.py"
_SPEC = importlib.util.spec_from_file_location("verify_ruleset", _MODULE_PATH)
tool = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(tool)

MANIFEST = {
    "version": 1,
    "rulesets": {
        "Protect": {"branches": ["main"], "checks": [
            {"context": "Verify", "source": "workflow", "workflow": "a.yml"},
            {"context": "Gitleaks", "source": "workflow", "workflow": "b.yml"},
        ]},
        "Promotion": {"branches": ["main"], "checks": [
            {"context": "Evidence", "source": "workflow", "workflow": "c.yml"},
        ]},
    },
}


def ruleset(name: str, *contexts: str, enforcement: str = "active", extra_rules: bool = True) -> dict:
    rules = [{"type": "deletion"}] if extra_rules else []
    rules.append({"type": "required_status_checks", "parameters": {
        "required_status_checks": [{"context": context, "integration_id": 15368} for context in contexts]}})
    return {"name": name, "enforcement": enforcement, "rules": rules}


CONFORMING = [ruleset("Protect", "Verify", "Gitleaks"), ruleset("Promotion", "Evidence")]


class CompareTest(unittest.TestCase):

    def test_matching_rulesets_have_no_problem(self):
        self.assertEqual([], tool.compare(MANIFEST, CONFORMING))

    def test_the_order_and_the_integration_id_do_not_matter(self):
        self.assertEqual([], tool.compare(MANIFEST, [ruleset("Promotion", "Evidence"),
                                                      ruleset("Protect", "Gitleaks", "Verify")]))

    def test_a_missing_check_is_named(self):
        problems = tool.compare(MANIFEST, [ruleset("Protect", "Verify"), ruleset("Promotion", "Evidence")])
        self.assertEqual(1, len(problems), problems)
        self.assertIn("'Gitleaks'", problems[0])

    def test_a_check_in_excess_is_named(self):
        problems = tool.compare(MANIFEST, [ruleset("Protect", "Verify", "Gitleaks", "Mystery"),
                                           ruleset("Promotion", "Evidence")])
        self.assertEqual(1, len(problems), problems)
        self.assertIn("'Mystery'", problems[0])
        self.assertIn("does not declare", problems[0])

    def test_an_absent_ruleset_is_named(self):
        problems = tool.compare(MANIFEST, [ruleset("Protect", "Verify", "Gitleaks")])
        self.assertEqual(1, len(problems), problems)
        self.assertIn("'Promotion'", problems[0])

    def test_a_ruleset_that_is_not_active_is_named(self):
        problems = tool.compare(MANIFEST, [ruleset("Protect", "Verify", "Gitleaks", enforcement="disabled"),
                                           ruleset("Promotion", "Evidence")])
        self.assertEqual(1, len(problems), problems)
        self.assertIn("not active", problems[0])

    def test_a_ruleset_without_a_status_check_rule_misses_everything(self):
        bare = {"name": "Protect", "enforcement": "active", "rules": [{"type": "deletion"}]}
        problems = tool.compare(MANIFEST, [bare, ruleset("Promotion", "Evidence")])
        self.assertEqual(2, len(problems), problems)


class CommandLineTest(unittest.TestCase):

    def run_main(self, payload: object, manifest: object = MANIFEST) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            arguments = ["--manifest", str(root / "manifest.json")]
            if payload is not None:
                (root / "live.json").write_text(payload if isinstance(payload, str) else json.dumps(payload),
                                                encoding="utf-8")
                arguments += ["--from-json", str(root / "live.json")]
            err = io.StringIO()
            with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
                code = tool.main(arguments)
            return code, err.getvalue()

    def test_a_conforming_payload_exits_zero(self):
        self.assertEqual(0, self.run_main(CONFORMING)[0])

    def test_a_difference_exits_one_and_names_the_context(self):
        code, err = self.run_main([ruleset("Protect", "Verify"), ruleset("Promotion", "Evidence")])
        self.assertEqual(1, code)
        self.assertIn("Gitleaks", err)

    def test_an_unreadable_payload_exits_two_never_zero(self):
        for payload in ("{not json", {"rulesets": []}):
            with self.subTest(payload=payload):
                self.assertEqual(2, self.run_main(payload)[0])

    def test_gh_missing_exits_two(self):
        with mock.patch.object(tool.subprocess, "run", side_effect=FileNotFoundError("gh")):
            code, err = self.run_main(None)
        self.assertEqual(2, code)
        self.assertIn("UNAVAILABLE", err)

    def test_gh_failing_or_answering_garbage_exits_two(self):
        failed = mock.Mock(returncode=1, stdout="", stderr="auth")
        garbage = mock.Mock(returncode=0, stdout="<html>", stderr="")
        for completed in (failed, garbage):
            with self.subTest(returncode=completed.returncode):
                with mock.patch.object(tool.subprocess, "run", return_value=completed):
                    code, _ = self.run_main(None)
                self.assertEqual(2, code)

    def test_the_real_gh_path_reads_each_ruleset_by_id(self):
        def fake_run(command, **_):
            if command[:2] == ["git", "remote"]:
                return mock.Mock(returncode=0, stdout="https://github.com/owner/repo.git\n")
            path = command[-1]
            if path == "repos/owner/repo/rulesets":
                return mock.Mock(returncode=0, stdout=json.dumps([{"id": 1}, {"id": 2}]))
            full = {"repos/owner/repo/rulesets/1": CONFORMING[0], "repos/owner/repo/rulesets/2": CONFORMING[1]}
            return mock.Mock(returncode=0, stdout=json.dumps(full[path]))
        with mock.patch.object(tool.subprocess, "run", side_effect=fake_run):
            self.assertEqual(0, self.run_main(None)[0])

    def test_the_declared_manifest_of_the_repository_is_readable(self):
        manifest = json.loads(tool.MANIFEST.read_text(encoding="utf-8"))
        self.assertIn("Protect main & develop", tool.declared(manifest))


if __name__ == "__main__":
    unittest.main()

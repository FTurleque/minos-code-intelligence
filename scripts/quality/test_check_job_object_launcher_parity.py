#!/usr/bin/env python3
"""Self-test of check-job-object-launcher-parity.py, on temporary copies of the real launcher files: one witness per
way the plugin copy can drift from the engine launcher, and the real repository accepted."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import shutil
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-job-object-launcher-parity.py"
_SPEC = importlib.util.spec_from_file_location("check_job_object_launcher_parity", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

REPOSITORY = Path(__file__).resolve().parents[2]
FRAGMENTS = Path(gate.ENGINE_LAUNCHER).parent / "windows-fragments"


class ParityTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for relative in (gate.ASSEMBLER, gate.PLUGIN_LAUNCHER, gate.ENGINE_LAUNCHER + ".template"):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / relative, target)
        shutil.copytree(REPOSITORY / FRAGMENTS, self.root / FRAGMENTS)

    def edit(self, relative: str, old: str, new: str) -> None:
        path = self.root / relative
        text = path.read_bytes().decode("utf-8")
        self.assertIn(old, text)
        path.write_bytes(text.replace(old, new, 1).encode("utf-8"))

    def failures(self) -> list[str]:
        return gate.check(self.root)[0]

    def test_a_copy_of_the_repository_is_accepted_with_its_counters(self):
        failures, identical, zones = gate.check(self.root)
        self.assertEqual([], failures)
        self.assertEqual(len(gate.IDENTICAL_FRAGMENTS), identical)
        self.assertGreater(zones, 0)

    def test_a_shared_fragment_changed_on_the_plugin_side_only_is_refused(self):
        self.edit(gate.PLUGIN_LAUNCHER, "public long PerProcessUserTimeLimit;", "public ulong PerProcessUserTimeLimit;")
        failures = self.failures()
        self.assertTrue(any("no longer carries the engine fragment" in failure for failure in failures), failures)

    def test_a_shared_fragment_changed_on_the_engine_side_only_is_refused(self):
        self.edit((FRAGMENTS / "job-configuration-verify.ps1frag").as_posix(), "(", "( ")
        failures = self.failures()
        self.assertTrue(any("'job-configuration-verify' word for word" in failure for failure in failures), failures)

    def test_a_difference_outside_the_list_is_refused_and_located(self):
        self.edit(gate.PLUGIN_LAUNCHER, "            ConfigureAndVerifyJob(job);",
                  "            ConfigureAndVerifyJob(job);\n            GC.KeepAlive(job);")
        failures = self.failures()
        self.assertEqual(1, len(failures), failures)
        self.assertIn("outside every assumed difference", failures[0])
        self.assertIn("starting 'GC.KeepAlive(job);'", failures[0])

    def test_an_assumed_difference_that_no_longer_exists_is_refused(self):
        self.edit(gate.PLUGIN_LAUNCHER, "MINOS CLI is not a member of the Job Object",
                  "provider is not a member of the MINOS Job Object")
        self.edit(gate.PLUGIN_LAUNCHER, "MINOS CLI Job Object must not allow breakaway",
                  "MINOS ownership Job Object must not allow breakaway")
        self.edit(gate.PLUGIN_LAUNCHER, "Invalid CLI environment entry in ownership plan",
                  "Invalid provider environment entry in ownership plan")
        self.assertEqual(["assumed difference 'owned-process-name' matches no difference any more: remove it"],
                         self.failures())

    def test_an_assumed_difference_that_grows_beyond_its_maximum_is_refused(self):
        self.edit(gate.PLUGIN_LAUNCHER, "MINOS CLI is not a member of the Job Object",
                  "MINOS CLI is not a member of the Job Object\");\n"
                  "            if (!inJob) throw new InvalidOperationException(\"MINOS CLI is still not a member")
        failures = self.failures()
        self.assertEqual(1, len(failures), failures)
        self.assertIn("assumed difference 'owned-process-name' now spans", failures[0])
        self.assertIn("more than its maximum", failures[0])

    def test_a_missing_fragment_or_template_is_refused_never_skipped(self):
        (self.root / FRAGMENTS / "startupinfo-struct.ps1frag").unlink()
        failures = self.failures()
        self.assertEqual(1, len(failures), failures)
        self.assertIn("missing Windows launcher fragment: startupinfo-struct", failures[0])
        (self.root / (gate.ENGINE_LAUNCHER + ".template")).unlink()
        self.assertIn("missing Windows launcher template", self.failures()[0])

    def test_a_missing_plugin_copy_is_refused(self):
        (self.root / gate.PLUGIN_LAUNCHER).unlink()
        self.assertEqual([f"{gate.PLUGIN_LAUNCHER} is missing: nothing can stand for the plugin launcher"],
                         self.failures())

    def test_line_endings_and_trailing_spaces_are_not_differences(self):
        path = self.root / gate.PLUGIN_LAUNCHER
        text = path.read_bytes().decode("utf-8").replace("\r\n", "\n")
        path.write_bytes(text.replace("\n", "  \r\n").encode("utf-8"))
        self.assertEqual([], self.failures())

    def test_the_root_option_and_the_real_repository(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(0, gate.main(["--root", str(REPOSITORY)]))
        self.assertIn("JOB OBJECT LAUNCHER PARITY GATE SUCCESS", stdout.getvalue())
        (self.root / gate.PLUGIN_LAUNCHER).unlink()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(1, gate.main(["--root", str(self.root)]))
        self.assertIn("JOB OBJECT LAUNCHER PARITY GATE FAILED", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()

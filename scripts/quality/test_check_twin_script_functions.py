#!/usr/bin/env python3
"""Self-test of check-twin-script-functions.py: one refused case per rule, on copies of the real scripts."""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_MODULE_PATH = Path(__file__).parent / "check-twin-script-functions.py"
_SPEC = importlib.util.spec_from_file_location("check_twin_script_functions", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

REAL_ROOT = gate.DEFAULT_ROOT
PR_CI = ".github/workflows/pr-ci.yml"
DOCKER_SERVICES = "docker/scripts/configure-m30-docker-services.ps1"
RUNTIME_SETTINGS = "scripts/install/configure-runtime-settings.ps1"
DETECT_CLIENTS = "scripts/install/detect-mcp-clients.ps1"
PROBE_BACKEND = "scripts/install/probe-mcp-backend.ps1"
PROD_MCP = "docker/scripts/prod-mcp.ps1"


def read(root: Path, relative: str) -> str:
    return (root / relative).read_bytes().decode("utf-8")


def write(root: Path, relative: str, text: str) -> None:
    (root / relative).write_bytes(text.encode("utf-8"))


def replace_once(root: Path, relative: str, old: str, new: str) -> None:
    text = read(root, relative)
    if text.count(old) != 1:
        raise AssertionError(f"{relative}: expected exactly one {old!r}")
    write(root, relative, text.replace(old, new))


def function_text(root: Path, relative: str, name: str) -> str:
    """The column-0 definition of `name`, with the file's own line endings."""
    lines = read(root, relative).splitlines(keepends=True)
    start = next(i for i, line in enumerate(lines) if line.startswith(f"function {name}"))
    end = next(i for i in range(start + 1, len(lines)) if lines[i].rstrip() == "}")
    return "".join(lines[start:end + 1])


class CheckTwinScriptFunctionsTest(unittest.TestCase):

    def run_gate(self, mutate=None) -> tuple[list[str], int, int, int]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for perimeter in gate.PERIMETER:
                (root / perimeter).mkdir(parents=True)
                for path in sorted((REAL_ROOT / perimeter).rglob("*.ps1")):
                    target = root / path.relative_to(REAL_ROOT)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(path, target)
            (root / PR_CI).parent.mkdir(parents=True)
            shutil.copyfile(REAL_ROOT / PR_CI, root / PR_CI)
            if mutate is not None:
                mutate(root)
            return gate.check(root)

    def failures(self, mutate=None) -> list[str]:
        return self.run_gate(mutate)[0]

    # ---- accepted ----
    def test_copies_of_the_real_scripts_pass(self):
        failures, entries, compared, scripts = self.run_gate()
        self.assertEqual([], failures)
        self.assertEqual(len(gate.TWINS), entries)
        self.assertEqual(sum(len(files) for _, files in gate.TWINS), compared)
        self.assertGreaterEqual(scripts, 2)

    def test_line_endings_and_trailing_spaces_do_not_count(self):
        def mutate(root: Path):
            text = read(root, RUNTIME_SETTINGS).replace("\r\n", "\n")
            self.assertIn("\n", text)
            write(root, RUNTIME_SETTINGS, text.replace("\n", "   \n"))
        self.assertEqual([], self.failures(mutate))

    # ---- rule 3: the copies of an entry stay identical ----
    def test_a_fix_made_in_one_copy_only_is_refused(self):
        def mutate(root: Path):
            replace_once(root, RUNTIME_SETTINGS, "throw 'MaximumBytes must be positive.'",
                         "throw 'MaximumBytes must be strictly positive.'")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Read-BoundedUtf8 differs", failures[0])
        self.assertIn(DOCKER_SERVICES, failures[0])
        self.assertIn(RUNTIME_SETTINGS, failures[0])

    def test_an_indentation_change_is_a_difference(self):
        def mutate(root: Path):
            replace_once(root, DETECT_CLIENTS, "    if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value }",
                         "  if (-not [string]::IsNullOrWhiteSpace($Value)) { return $Value }")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Get-UserFolderPath differs", failures[0])

    def test_a_listed_file_that_is_missing_is_refused(self):
        failures = self.failures(lambda root: (root / PROD_MCP).unlink())
        self.assertEqual(2, len(failures), failures)  # Test-CeilingOverride and Get-PreservedCeilingOverrides
        self.assertTrue(all(PROD_MCP in failure and "not found" in failure for failure in failures), failures)
        self.assertTrue(any("TWINS entry Test-CeilingOverride" in failure for failure in failures), failures)

    def test_a_listed_function_that_is_gone_is_refused(self):
        def mutate(root: Path):
            replace_once(root, DETECT_CLIENTS, "function Get-UserFolderPath(", "function Get-UserFolderLocation(")
        failures = [f for f in self.failures(mutate) if "TWINS entry" in f]
        self.assertEqual(1, len(failures), failures)
        self.assertIn("TWINS entry Get-UserFolderPath", failures[0])
        self.assertIn("no longer defines", failures[0])

    def test_a_function_defined_twice_in_one_file_is_refused(self):
        def mutate(root: Path):
            write(root, DETECT_CLIENTS, read(root, DETECT_CLIENTS) + "\r\n"
                  + function_text(root, DETECT_CLIENTS, "Get-UserFolderPath"))
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn(f"{DETECT_CLIENTS} defines Get-UserFolderPath 2 times", failures[0])

    def test_an_entry_with_fewer_than_two_files_is_refused(self):
        single = gate.TWINS + (("Get-Lonely", (PROD_MCP,)), ("Get-Repeated", (PROD_MCP, PROD_MCP)))
        with mock.patch.object(gate, "TWINS", single):
            failures = self.failures()
        self.assertEqual(2, len(failures), failures)
        self.assertIn("TWINS entry Get-Lonely: names fewer than two distinct files", failures[0])
        self.assertIn("TWINS entry Get-Repeated", failures[1])

    def test_an_entry_listed_twice_is_refused(self):
        with mock.patch.object(gate, "TWINS", gate.TWINS + (gate.TWINS[0],)):
            failures = self.failures()
        self.assertEqual(1, len(failures), failures)
        self.assertIn("listed twice", failures[0])

    # ---- rule 4: no undeclared identical copy ----
    def test_an_undeclared_identical_copy_is_refused(self):
        def mutate(root: Path):
            write(root, PROBE_BACKEND, read(root, PROBE_BACKEND) + "\r\n"
                  + function_text(root, DETECT_CLIENTS, "Get-UserFolderPath"))
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Get-UserFolderPath is copied identically", failures[0])
        self.assertIn(PROBE_BACKEND, failures[0])
        self.assertIn(DETECT_CLIENTS, failures[0])

    def test_an_identical_copy_outside_the_perimeter_is_not_examined(self):
        def mutate(root: Path):
            (root / "scripts/m99").mkdir(parents=True)
            write(root, "scripts/m99/run.ps1", function_text(root, DETECT_CLIENTS, "Get-UserFolderPath"))
        self.assertEqual([], self.failures(mutate))

    def test_a_homonym_with_another_body_is_not_a_copy(self):
        def mutate(root: Path):
            write(root, PROBE_BACKEND, read(root, PROBE_BACKEND)
                  + "\r\nfunction Get-UserFolderPath([string] $Name) {\r\n    return $Name\r\n}\r\n")
        self.assertEqual([], self.failures(mutate))

    # ---- rules 1 and 2: the perimeter is readable and every definition can be bounded ----
    def test_a_definition_that_cannot_be_bounded_is_refused(self):
        def mutate(root: Path):
            write(root, PROBE_BACKEND, read(root, PROBE_BACKEND) + "\r\nfunction Broken-Tail {\r\n    1\r\n")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn(f"{PROBE_BACKEND}:", failures[0])
        self.assertIn("Broken-Tail cannot be bounded", failures[0])

    def test_a_listed_definition_that_cannot_be_bounded_never_passes_for_identical(self):
        def mutate(root: Path):
            replace_once(root, RUNTIME_SETTINGS, "function Get-FileAccessSecurity(",
                         "function Get-Unclosed {\r\n    1\r\n\r\nfunction Get-FileAccessSecurity(")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Get-Unclosed cannot be bounded", failures[0])

    def test_a_one_line_definition_is_bounded_by_its_own_line(self):
        found = gate.definitions("function Get-One { 1 }\r\nfunction Get-Two {\r\n    2\r\n}\r\n")
        self.assertEqual([("Get-One", 1, "function Get-One { 1 }"), ("Get-Two", 2, "function Get-Two {\n    2\n}")],
                         [(definition.name, definition.line, definition.body) for definition in found])

    def test_a_closing_brace_inside_a_here_string_does_not_end_the_definition(self):
        text = ("function Write-Template {\r\n    $Text = @'\r\nfunction Inside {\r\n}\r\n'@\r\n"
                "    return $Text\r\n}\r\n")
        found = gate.definitions(text)
        self.assertEqual(["Write-Template"], [definition.name for definition in found])
        self.assertTrue(found[0].body.endswith("    return $Text\n}"), found[0].body)

    def test_a_difference_after_a_here_string_of_a_listed_function_is_seen(self):
        def mutate(root: Path):
            for relative in (DETECT_CLIENTS, "scripts/install/configure-mcp-clients.ps1"):
                replace_once(root, relative, "    return [Environment]::GetFolderPath($SpecialFolder)\r\n}",
                             "    $Unused = @\"\r\n}\r\n\"@\r\n    return [Environment]::GetFolderPath($SpecialFolder)\r\n}")
            replace_once(root, DETECT_CLIENTS, "    return [Environment]::GetFolderPath($SpecialFolder)",
                         "    return [Environment]::GetFolderPath('UserProfile')")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Get-UserFolderPath differs", failures[0])

    def test_a_copy_in_a_build_subdirectory_is_still_examined(self):
        def mutate(root: Path):
            (root / "scripts/install/build").mkdir()
            write(root, "scripts/install/build/copy.ps1", function_text(root, DETECT_CLIENTS, "Get-UserFolderPath"))
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("scripts/install/build/copy.ps1", failures[0])

    def test_a_missing_perimeter_directory_is_refused(self):
        failures = self.failures(lambda root: shutil.rmtree(root / "scripts/ci"))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("scripts/ci: perimeter directory not found", failures[0])

    def test_a_script_that_is_not_utf8_is_refused(self):
        failures = self.failures(lambda root: (root / "scripts/ci/broken.ps1").write_bytes(b"function X {\xff}\r\n"))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("scripts/ci/broken.ps1: not readable as UTF-8", failures[0])

    # ---- rule 5: wired in the invariants job ----
    def test_a_gate_that_pr_ci_does_not_run_is_refused(self):
        def mutate(root: Path):
            replace_once(root, PR_CI, "run: python scripts/quality/check-twin-script-functions.py",
                         "run: echo skipped")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("no step running `python scripts/quality/check-twin-script-functions.py`", failures[0])

    def test_a_condition_on_the_step_is_refused(self):
        def mutate(root: Path):
            replace_once(root, PR_CI, "        run: python scripts/quality/test_check_twin_script_functions.py -v",
                         "        if: false\n        run: python scripts/quality/test_check_twin_script_functions.py -v")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("test_check_twin_script_functions.py` has an `if:` condition", failures[0])

    def test_a_condition_on_the_invariants_job_is_refused(self):
        def mutate(root: Path):
            replace_once(root, PR_CI, "  invariants:", "  invariants:\n    if: false")
        failures = self.failures(mutate)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("job `invariants` has an `if:` condition", failures[0])

    def test_without_gate_cli_the_gate_fails_instead_of_falling_back(self):
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory) / _MODULE_PATH.name
            shutil.copyfile(_MODULE_PATH, copy)
            result = subprocess.run([sys.executable, "-I", str(copy), "--root", str(REAL_ROOT)],
                                    capture_output=True, text=True, encoding="utf-8")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("gate_cli", result.stderr)

    def test_the_wiring_check_is_the_one_of_gate_cli(self):
        self.assertFalse(hasattr(gate, "find_ci_wiring_violations"), "the gate must not carry its own copy")
        with mock.patch.object(gate.gate_cli, "find_ci_wiring_violations", return_value=["WIRING SENTINEL"]) as shared:
            failures = self.failures()
        self.assertEqual(["WIRING SENTINEL"], failures)
        self.assertEqual(gate.CI_STEPS, shared.call_args.args[1])

    # ---- the real repository ----
    def test_the_real_repository_is_accepted(self):
        failures, entries, compared, scripts = gate.check(REAL_ROOT)
        self.assertEqual([], failures)
        self.assertEqual(13, entries)
        self.assertEqual(27, compared)
        self.assertGreaterEqual(scripts, 39)

    def test_the_command_line_reports_the_counts(self):
        result = subprocess.run([sys.executable, str(_MODULE_PATH), "--root", str(REAL_ROOT)],
                                capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("entries=13, copies compared=27", result.stdout)


if __name__ == "__main__":
    unittest.main()

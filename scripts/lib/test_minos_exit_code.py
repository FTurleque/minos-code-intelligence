#!/usr/bin/env python3
"""Tests of the shared partial-result exit-code list and of the real script functions that consume it.

The scripts under test cannot be dot-sourced (they run a whole qualification at load time), so each test
extracts the *real* function definitions from the script with the PowerShell parser and runs them against a
fake MINOS executable that exits with a chosen code. Nothing here re-implements a script's logic.
Docs: docs/audit/archive/2026-09/Q25-Q26-SUIVI.md, section 2.
"""
from __future__ import annotations

import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "scripts" / "lib" / "partial-result-commands.json"
HELPER = ROOT / "scripts" / "lib" / "MinosExitCode.ps1"
PWSH = os.environ.get("MINOS_TEST_POWERSHELL") or shutil.which("pwsh") or shutil.which("powershell")

THREW = "THREW"
RETURNED = "RETURNED"


def fake_minos(directory: Path) -> Path:
    """An executable that prints a JSON object and exits with $FAKE_EXIT."""
    if os.name == "nt":
        path = directory / "fake-minos.cmd"
        path.write_text('@echo off\r\necho {"state":"STALE","count":0}\r\nexit /b %FAKE_EXIT%\r\n', encoding="ascii")
    else:
        path = directory / "fake-minos.sh"
        path.write_text('#!/bin/sh\necho \'{"state":"STALE","count":0}\'\nexit "${FAKE_EXIT}"\n', encoding="ascii")
        path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


def run_pwsh(body: str, exit_code: int, directory: Path) -> dict:
    """Runs a PowerShell body that ends by printing one JSON object; returns it."""
    script = directory / "case.ps1"
    script.write_text(body, encoding="utf-8")
    environment = dict(os.environ, FAKE_EXIT=str(exit_code))
    completed = subprocess.run(
        [PWSH, "-NoProfile", "-File", str(script)],
        capture_output=True, text=True, env=environment, timeout=120, check=False)
    lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
    if not lines:
        raise AssertionError(f"no JSON result (exit {completed.returncode})\n{completed.stdout}\n{completed.stderr}")
    return json.loads(lines[-1])


# Extracts the named functions of a script as text, using the PowerShell parser, then defines them in the harness.
EXTRACT = r"""
function Get-ScriptFunctionText([string] $Path, [string[]] $Names) {
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $errors)
    if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
    $found = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Names -contains $node.Name }, $true)
    if (@($found).Count -ne $Names.Count) { throw "missing function(s) in $Path" }
    return (($found | ForEach-Object { $_.Extent.Text }) -join [Environment]::NewLine)
}
"""

OUTCOME = r"""
$script:Warnings = @()
function Write-Warning { param([string] $Message) $script:Warnings += $Message }
try {
    %CALL%
    $result = [ordered]@{ outcome = 'RETURNED'; warned = (@($Warnings | Where-Object { $_ -match 'partial result' }).Count -gt 0) }
} catch {
    $result = [ordered]@{ outcome = 'THREW'; message = $_.Exception.Message; warned = $false }
}
$result | ConvertTo-Json -Compress
"""


def failed_on_exit(result: dict, code: int) -> bool:
    """The script threw *because of the exit code*, not because the harness or the function was broken."""
    return result["outcome"] == THREW and f"exit={code}" in result["message"]


def quote(arguments: list[str]) -> str:
    return ",".join("'" + argument + "'" for argument in arguments)


class HelperTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if PWSH is None:
            raise unittest.SkipTest("PowerShell is not installed")

    def accepted(self, arguments: list[str]) -> list[int]:
        with tempfile.TemporaryDirectory() as tmp:
            body = (f". '{HELPER}'\n"
                    f"$codes = @(Get-MinosAcceptedExitCodes -MinosArguments @({quote(arguments)}))\n"
                    "@{ codes = $codes } | ConvertTo-Json -Compress\n")
            return run_pwsh(body, 0, Path(tmp))["codes"]

    def test_every_listed_command_accepts_the_partial_result_and_nothing_else_does(self):
        spec = json.loads(SPEC.read_text(encoding="utf-8"))
        self.assertEqual(3, spec["partialResultExitCode"])
        self.assertTrue(spec["commands"], "the list must not be empty")
        for command in spec["commands"]:
            self.assertEqual([0, 3], self.accepted(command.split(" ") + ["alpha", "--format", "json"]), command)
        for strict in (["index", "alpha"], ["project", "add", "x"], ["semantic", "status", "alpha"], ["doctor"],
                       ["find-symbol", "alpha", "Foo"], ["project"]):
            self.assertEqual([0], self.accepted(strict), " ".join(strict))


DOT_SOURCES = r"""
param([string] $Path)
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $errors)
$dots = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] -and
    $node.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Dot }, $true)
@{ dotSourced = @($dots | ForEach-Object { $_.Extent.Text }) } | ConvertTo-Json -Compress
"""


class ConsumerWiringTests(unittest.TestCase):
    """The function tests below hand the helper to the harness; this one proves the script really loads it."""

    @classmethod
    def setUpClass(cls):
        if PWSH is None:
            raise unittest.SkipTest("PowerShell is not installed")

    def test_each_script_that_uses_the_shared_helper_dot_sources_it_itself(self):
        for relative in ("scripts/m14/validate-local.ps1", "scripts/m29/run-s5.ps1"):
            with tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp)
                probe = directory / "probe.ps1"
                probe.write_text(DOT_SOURCES, encoding="utf-8")
                completed = subprocess.run(
                    [PWSH, "-NoProfile", "-File", str(probe), str(ROOT / relative)],
                    capture_output=True, text=True, timeout=120, check=False)
                lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
                self.assertTrue(lines, completed.stdout + completed.stderr)
                dot_sourced = json.loads(lines[-1])["dotSourced"]
                dot_sourced = [dot_sourced] if isinstance(dot_sourced, str) else dot_sourced
                self.assertTrue(any("MinosExitCode.ps1" in text for text in dot_sourced),
                                f"{relative} calls the helper's functions but never dot-sources it: {dot_sourced}")


class ScriptFunctionTests(unittest.TestCase):
    """The real functions of the real scripts, against a fake MINOS that exits with a chosen code."""

    @classmethod
    def setUpClass(cls):
        if PWSH is None:
            raise unittest.SkipTest("PowerShell is not installed")

    def m14(self, minos_arguments: list[str], exit_code: int) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            fake = fake_minos(directory)
            call = "$null = Invoke-MinosJson @(" + quote(minos_arguments) + ")"
            body = (
                f". '{HELPER}'\n{EXTRACT}\n"
                f". ([scriptblock]::Create((Get-ScriptFunctionText '{ROOT / 'scripts' / 'm14' / 'validate-local.ps1'}' "
                "@('Invoke-MinosJson'))))\n"
                "function Write-LatestProviderDiagnostics { }\n"
                f"$script:JavaExecutable = '{fake}'; $script:ValidationHome = 'unused'; $script:MinosJar = 'unused.jar'\n"
                + OUTCOME.replace("%CALL%", call))
            return run_pwsh(body, exit_code, directory)

    def s5(self, minos_arguments: list[str], exit_code: int, strict: bool = False) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            fake = fake_minos(directory)
            call = ("$null = Invoke-AdminJson -MinosArguments @(" + quote(minos_arguments) + ") -Label 'case'"
                    + (" -Strict" if strict else ""))
            body = (
                f". '{HELPER}'\n{EXTRACT}\n"
                f". ([scriptblock]::Create((Get-ScriptFunctionText '{ROOT / 'scripts' / 'm29' / 'run-s5.ps1'}' "
                "@('Invoke-NativeCapture', 'Assert-NativeSuccess', 'ConvertFrom-LastJsonLine', 'Invoke-AdminJson'))))\n"
                f"$Docker = '{fake}'; $RuntimeRoot = 'r'; $EnvironmentFile = 'e'; $ComposeFile = 'c'\n"
                + OUTCOME.replace("%CALL%", call))
            return run_pwsh(body, exit_code, directory)

    # m14/validate-local.ps1 -----------------------------------------------------------------------------------

    def test_m14_accepts_the_partial_result_of_index_status_and_says_so(self):
        result = self.m14(["index-status", "m14-java", "--format", "json"], 3)
        self.assertEqual(RETURNED, result["outcome"], result)
        self.assertTrue(result["warned"], "a partial result must be announced, not swallowed")

    def test_m14_success_is_silent(self):
        result = self.m14(["index-status", "m14-java", "--format", "json"], 0)
        self.assertEqual(RETURNED, result["outcome"], result)
        self.assertFalse(result["warned"])

    def test_m14_real_failures_stay_failures(self):
        for code in (1, 2, 4, 137):
            result = self.m14(["index-status", "m14-java", "--format", "json"], code)
            self.assertTrue(failed_on_exit(result, code), (code, result))

    def test_m14_a_command_that_cannot_be_partial_never_accepts_3(self):
        result = self.m14(["index", "m14-java", "--format", "json"], 3)
        self.assertTrue(failed_on_exit(result, 3), result)

    # m29/run-s5.ps1 ---------------------------------------------------------------------------------------------

    def test_s5_accepts_the_partial_result_of_the_commands_that_can_return_it_and_says_so(self):
        for arguments in (["index-status", "m29-s5-polyglot", "--format", "json"], ["project", "list", "--format", "json"]):
            result = self.s5(arguments, 3)
            self.assertEqual(RETURNED, result["outcome"], (arguments, result))
            self.assertTrue(result["warned"], arguments)

    def test_s5_a_fresh_registry_check_does_not_accept_a_partial_inventory(self):
        result = self.s5(["project", "list", "--format", "json"], 3, strict=True)
        self.assertTrue(failed_on_exit(result, 3), ("a registry with unreadable entries is not fresh", result))

    def test_s5_real_failures_stay_failures(self):
        for code in (1, 2, 4):
            result = self.s5(["index-status", "m29-s5-polyglot", "--format", "json"], code)
            self.assertTrue(failed_on_exit(result, code), (code, result))

    def test_s5_a_command_that_cannot_be_partial_never_accepts_3(self):
        result = self.s5(["index", "m29-s5-polyglot", "--format", "json"], 3)
        self.assertTrue(failed_on_exit(result, 3), result)


if __name__ == "__main__":
    sys.exit(unittest.main(verbosity=2))

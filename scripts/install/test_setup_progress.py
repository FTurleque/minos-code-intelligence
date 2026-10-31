#!/usr/bin/env python3
"""Tests of the installer progress protocol emitted by the shipped configuration scripts.

The Windows installer reads ``MINOS-PROGRESS step=<id> state=<state>[ percent=<n>]`` lines from a file whose path
it passes to its configuration scripts as ``-ProgressPath``. Each script carries the same ``Write-SetupProgress``
function, kept identical by
``scripts/quality/check-twin-script-functions.py``; ``configure-m30-docker-services.ps1`` also carries ``Get-OllamaPullPercent``, which reads the download
percentage out of ``ollama pull`` output.

The scripts cannot be dot-sourced (they run a whole configuration at load time), so each test extracts the *real*
function definitions with the PowerShell parser and runs them in a harness. Nothing here re-implements a script's
logic. The cases run under each PowerShell present: Windows PowerShell 5.1 and ``pwsh`` on Windows, ``pwsh``
elsewhere; without any they declare themselves skipped.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EMITTERS = [
    ROOT / "scripts" / "install" / "switch-mcp-backend.ps1",
    ROOT / "scripts" / "install" / "configure-mcp-clients-setup.ps1",
    ROOT / "docker" / "scripts" / "configure-docker-mcp.ps1",
    ROOT / "docker" / "scripts" / "configure-m30-docker-services.ps1",
]
M30 = ROOT / "docker" / "scripts" / "configure-m30-docker-services.ps1"
EMITTER = "Write-SetupProgress"
PERCENT = "Get-OllamaPullPercent"

DECODING = {"encoding": "utf-8", "errors": "replace"}

EXTRACT = r"""
function Get-ScriptFunctionText([string] $Path, [string] $Name) {
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $errors)
    if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
    $found = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name }, $true))
    if ($found.Count -ne 1) { throw "expected one $Name in $Path, found $($found.Count)" }
    return $found[0].Extent.Text
}
"""


def runtimes() -> list[tuple[str, str]]:
    """(label, executable) of every PowerShell the scripts must work under on this host."""
    override = os.environ.get("MINOS_TEST_POWERSHELL")
    if override:
        return [("override", override)]
    found = []
    if os.name == "nt":
        legacy = (Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "WindowsPowerShell" / "v1.0"
                  / "powershell.exe")
        if legacy.is_file():
            found.append(("Windows PowerShell 5.1", str(legacy)))
    core = shutil.which("pwsh")
    if core:
        found.append(("pwsh", core))
    return found


def quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def run_harness(executable: str, body: str, directory: Path) -> dict:
    script = directory / "harness.ps1"
    script.write_text("$ErrorActionPreference = 'Stop'\n" + EXTRACT + body, encoding="utf-8-sig")
    completed = subprocess.run(
        [executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)],
        capture_output=True, timeout=120, check=False, **DECODING)
    lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
    if completed.returncode != 0 or not lines:
        raise AssertionError(f"harness failed (exit {completed.returncode})\n{completed.stdout}\n{completed.stderr}")
    return json.loads(lines[-1])


class SetupProgressTest(unittest.TestCase):

    def setUp(self) -> None:
        self.runtimes = runtimes()
        if not self.runtimes:
            self.skipTest("no PowerShell on this host")

    def test_emitter_writes_only_the_closed_vocabulary(self) -> None:
        for script in EMITTERS:
            for label, executable in self.runtimes:
                with self.subTest(script=script.name, runtime=label), tempfile.TemporaryDirectory() as temp:
                    progress = Path(temp) / "progress.txt"
                    result = run_harness(executable, (
                        f"$ProgressPath = {quote(str(progress))}\n"
                        f". ([scriptblock]::Create((Get-ScriptFunctionText {quote(str(script))} '{EMITTER}')))\n"
                        "Write-SetupProgress -Step 'docker-image' -State 'running'\n"
                        "Write-SetupProgress -Step 'ollama-model' -State 'running' -Percent 42\n"
                        "Write-SetupProgress -Step 'ollama-model' -State 'done' -Percent 100\n"
                        "Write-SetupProgress -Step 'postgres' -State 'skipped'\n"
                        "Write-SetupProgress -Step 'docker-image' -State 'failed'\n"
                        "Write-SetupProgress -Step 'docker-image' -State 'Done'\n"
                        "Write-SetupProgress -Step 'Image Docker' -State 'running'\n"
                        "Write-SetupProgress -Step 'docker-image password=x' -State 'running'\n"
                        "Write-SetupProgress -Step 'docker-image' -State 'running' -Percent 150\n"
                        "Write-SetupProgress -Step 'docker-image' -State 'running' -Percent -5\n"
                        "[ordered]@{ text = [System.IO.File]::ReadAllText($ProgressPath) } | ConvertTo-Json -Compress\n"
                    ), Path(temp))
                    self.assertEqual(
                        "MINOS-PROGRESS step=docker-image state=running\r\n"
                        "MINOS-PROGRESS step=ollama-model state=running percent=42\r\n"
                        "MINOS-PROGRESS step=ollama-model state=done percent=100\r\n"
                        "MINOS-PROGRESS step=postgres state=skipped\r\n",
                        result["text"])

    def test_emitter_without_progress_path_writes_nothing(self) -> None:
        for label, executable in self.runtimes:
            with self.subTest(runtime=label), tempfile.TemporaryDirectory() as temp:
                result = run_harness(executable, (
                    "$ProgressPath = ''\n"
                    f". ([scriptblock]::Create((Get-ScriptFunctionText {quote(str(EMITTERS[0]))} '{EMITTER}')))\n"
                    f"Set-Location -LiteralPath {quote(temp)}\n"
                    "$out = @(Write-SetupProgress -Step 'handshake' -State 'running')\n"
                    "[ordered]@{ output = $out.Count; files = @(Get-ChildItem -LiteralPath . | Where-Object { $_.Name -ne 'harness.ps1' }).Count } | ConvertTo-Json -Compress\n"
                ), Path(temp))
                self.assertEqual({"output": 0, "files": 0}, result)

    def test_ollama_percent_is_read_only_when_printed(self) -> None:
        cases = {
            "plain": ("pulling manifest\npulling 970aa74c0a90:  42% |#####     | 115 MB/274 MB", 42),
            "carriage returns and ANSI": (
                "\x1b[?25lpulling 970aa74c0a90:   3%\r\x1b[Kpulling 970aa74c0a90:  57% \u2595\u2588\u2588\u258f\x1b[?25h", 57),
            "complete": ("pulling 970aa74c0a90: 100% |##########| 274 MB\nverifying sha256 digest\nsuccess", 100),
            "no percentage": ("pulling manifest\nverifying sha256 digest\nwriting manifest\nsuccess", None),
            "out of range": ("pulling 970aa74c0a90: 420%", None),
            "empty": ("", None),
        }
        for label, executable in self.runtimes:
            for name, (text, expected) in cases.items():
                with self.subTest(runtime=label, case=name), tempfile.TemporaryDirectory() as temp:
                    sample = Path(temp) / "sample.txt"
                    sample.write_text(text, encoding="utf-8")
                    result = run_harness(executable, (
                        f". ([scriptblock]::Create((Get-ScriptFunctionText {quote(str(M30))} '{PERCENT}')))\n"
                        f"$value = Get-OllamaPullPercent ([System.IO.File]::ReadAllText({quote(str(sample))}, [System.Text.Encoding]::UTF8))\n"
                        "[ordered]@{ value = $value } | ConvertTo-Json -Compress\n"
                    ), Path(temp))
                    self.assertEqual(expected, result["value"])


if __name__ == "__main__":
    unittest.main()

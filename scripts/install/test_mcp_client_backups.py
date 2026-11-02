#!/usr/bin/env python3
"""Tests of the configuration backups written by the MCP client managers.

Every backup of a client configuration must land in its own file: two backups taken in the same
millisecond (an install followed by an uninstall in one PowerShell session) must not overwrite each
other. Each case extracts the *real* backup functions with the PowerShell parser and calls them in a
tight loop; nothing here re-implements the scripts.

The verification scripts count backup files under ``Set-StrictMode -Version Latest``: a count taken on
a bare ``(Get-ChildItem ...)`` fails when exactly one file is returned. A static case requires every
such count to go through ``@(...)``.

The runtime cases run under Windows PowerShell 5.1 (the installer's runtime) and under ``pwsh``, on
Windows only: elsewhere they declare themselves skipped with their reason.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSTALL = ROOT / "scripts" / "install"
CODEX_SCRIPT = INSTALL / "configure-codex-mcp.ps1"
CLIENTS_SCRIPT = INSTALL / "configure-mcp-clients.ps1"
WINDOWS = os.name == "nt"
SKIP_REASON = "Windows PowerShell 5.1 and pwsh: runs on Verify (windows-2022)"
BACKUP_COUNT = 40
BARE_COUNT = re.compile(r"(?<!@)\(\s*Get-ChildItem\b[^()]*(?:\([^()]*\)[^()]*)*\)\.Count", re.IGNORECASE)


def runtimes() -> list[tuple[str, str]]:
    """(label, executable) of every PowerShell the scripts must work under."""
    found = []
    legacy = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    if legacy.is_file():
        found.append(("Windows PowerShell 5.1", str(legacy)))
    core = shutil.which("pwsh")
    if core:
        found.append(("pwsh", core))
    return found


HARNESS = r"""
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Get-ScriptFunctionText([string] $Path, [string[]] $Names) {
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $errors)
    if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
    $found = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Names -contains $node.Name }, $true))
    $missing = @($Names | Where-Object { @($found | ForEach-Object { $_.Name }) -notcontains $_ })
    if ($missing.Count -gt 0) { throw "missing function(s) in ${Path}: $($missing -join ', ')" }
    return (($found | ForEach-Object { $_.Extent.Text }) -join [Environment]::NewLine)
}
function Write-Log([string] $Message) { }
function Write-IntegrationLog([string] $Message) { }
. ([scriptblock]::Create((Get-ScriptFunctionText '%SCRIPT%' @(%FUNCTIONS%))))

$BackupRoot = Join-Path '%WORK%' 'backups'
$Source = Join-Path '%WORK%' 'config.toml'
$Targets = @()
for ($Index = 0; $Index -lt %COUNT%; $Index++) {
    [System.IO.File]::WriteAllText($Source, "content-$Index", [System.Text.UTF8Encoding]::new($false))
    $Targets += [string](%CALL%)
}
$Files = @(Get-ChildItem -LiteralPath $BackupRoot -File -Recurse)
$Distinct = @($Targets | Sort-Object -Unique)
$Contents = @($Files | ForEach-Object { [System.IO.File]::ReadAllText($_.FullName) } | Sort-Object -Unique)
"files=$($Files.Count);targets=$($Distinct.Count);contents=$($Contents.Count)"
"""


def run_backups(executable: str, script: Path, functions: tuple[str, ...], call: str) -> str:
    work = Path(tempfile.mkdtemp(prefix="minos-mcp-backups-"))
    try:
        body = (HARNESS.replace("%SCRIPT%", str(script).replace("'", "''"))
                .replace("%FUNCTIONS%", ", ".join(f"'{name}'" for name in functions))
                .replace("%WORK%", str(work).replace("'", "''"))
                .replace("%COUNT%", str(BACKUP_COUNT))
                .replace("%CALL%", call))
        harness = work / "harness.ps1"
        harness.write_text(body, encoding="utf-8-sig")
        completed = subprocess.run(
            [executable, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(harness)],
            capture_output=True, text=True, timeout=180, check=False)
        if completed.returncode != 0:
            raise AssertionError(f"harness failed ({completed.returncode}):\n{completed.stdout}\n{completed.stderr}")
        return completed.stdout.strip().splitlines()[-1]
    finally:
        shutil.rmtree(work, ignore_errors=True)


@unittest.skipUnless(WINDOWS, SKIP_REASON)
class BackupUniquenessTest(unittest.TestCase):
    """Backups taken back to back each keep their own file and their own content."""

    expected = f"files={BACKUP_COUNT};targets={BACKUP_COUNT};contents={BACKUP_COUNT}"

    def assert_unique(self, script: Path, functions: tuple[str, ...], call: str) -> None:
        found = runtimes()
        self.assertTrue(found, "no PowerShell runtime found")
        for label, executable in found:
            with self.subTest(runtime=label):
                self.assertEqual(self.expected, run_backups(executable, script, functions, call))

    def test_codex_backups_never_overwrite_each_other(self) -> None:
        self.assert_unique(CODEX_SCRIPT, ("Backup-File", "New-UniqueBackupDirectory"), "Backup-File $Source")

    def test_client_backups_never_overwrite_each_other(self) -> None:
        self.assert_unique(CLIENTS_SCRIPT, ("Backup-Configuration", "New-UniqueBackupDirectory"),
                           "Backup-Configuration 'claude-desktop' $Source")


class StrictModeCountTest(unittest.TestCase):
    """A count of Get-ChildItem results goes through @(...) so that one result still has a Count."""

    def test_no_bare_get_childitem_count(self) -> None:
        offenders = []
        for script in sorted(INSTALL.glob("*.ps1")):
            for number, line in enumerate(script.read_text(encoding="utf-8-sig").splitlines(), start=1):
                if BARE_COUNT.search(line):
                    offenders.append(f"{script.name}:{number}")
        self.assertEqual([], offenders)

    def test_pattern_detects_bare_and_accepts_wrapped_counts(self) -> None:
        self.assertTrue(BARE_COUNT.search("((Get-ChildItem -LiteralPath $B -File -Recurse).Count -ge 2)"))
        self.assertTrue(BARE_COUNT.search("(Get-ChildItem -LiteralPath (Join-Path $A 'b') -File).Count"))
        self.assertFalse(BARE_COUNT.search("(@(Get-ChildItem -LiteralPath $B -File -Recurse).Count -ge 2)"))


if __name__ == "__main__":
    unittest.main()

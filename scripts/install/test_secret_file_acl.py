#!/usr/bin/env python3
"""Tests of the functions that write the PostgreSQL password file in the two installation scripts (AUD-SEC-12).

The scripts cannot be dot-sourced (they configure a whole installation at load time), so each case extracts
the *real* function definitions with the PowerShell parser and runs them in a folder whose ACL grants read
access to the Users group. Nothing here re-implements the scripts. The restriction is observed, or made to
fail, by a PowerShell function named ``icacls.exe`` that shadows the executable: no seam in production code.

Every case runs under Windows PowerShell 5.1 (the installer's runtime) and under ``pwsh``. Windows only:
elsewhere the tests declare themselves skipped with their reason.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSTALL_SCRIPT = ROOT / "scripts" / "install" / "configure-runtime-settings.ps1"
DOCKER_SCRIPT = ROOT / "docker" / "scripts" / "configure-m30-docker-services.ps1"
WINDOWS = os.name == "nt"
SKIP_REASON = "Windows ACLs and icacls.exe: runs on Verify (windows-2022)"
ACL_HELPERS = ("Restrict-FileToCurrentUser", "Get-FileAccessSddl", "Assert-FileRestrictedToCurrentUser",
               "Write-PrivateSecretFile")


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
function Get-ScriptFunctionText([string] $Path, [string[]] $Names) {
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $errors)
    if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
    $found = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Names -contains $node.Name }, $true)
    $missing = @($Names | Where-Object { @($found | ForEach-Object { $_.Name }) -notcontains $_ })
    if ($missing.Count -gt 0) { throw "missing function(s) in ${Path}: $($missing -join ', ')" }
    return (($found | ForEach-Object { $_.Extent.Text }) -join [Environment]::NewLine)
}
function Read-TestSddl([string] $Path) {
    $info = New-Object System.IO.FileInfo $Path
    $sections = [System.Security.AccessControl.AccessControlSections]::Access
    if ($info | Get-Member -Name GetAccessControl -MemberType Method) {
        return $info.GetAccessControl($sections).GetSecurityDescriptorSddlForm($sections)
    }
    return [System.IO.FileSystemAclExtensions]::GetAccessControl($info, $sections).GetSecurityDescriptorSddlForm($sections)
}
$Sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$Expected = "D:PAI(A;;FA;;;$Sid)"
$RealIcacls = (Get-Command icacls.exe -CommandType Application | Select-Object -First 1).Source

# A data folder every local user can read: what a DataRoot on a shared volume looks like.
$Folder = Join-Path '%WORK%' 'secrets'
New-Item -ItemType Directory -Force -Path $Folder | Out-Null
& $RealIcacls $Folder /grant '*S-1-5-32-545:(OI)(CI)R' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'the test could not widen the folder ACL' }
$Target = Join-Path $Folder 'postgres.password'

$script:Mode = '%MODE%'
$script:LengthAtRestriction = -1
$script:ContentAtRestriction = ''
# Shadows icacls.exe: PowerShell resolves a function before an executable of the same name.
function icacls.exe {
    $path = [string] $args[0]
    if (Test-Path -LiteralPath $path) {
        $script:LengthAtRestriction = (Get-Item -LiteralPath $path).Length
        $script:ContentAtRestriction = [System.IO.File]::ReadAllText($path)
    }
    if ($script:Mode -eq 'fail') { $global:LASTEXITCODE = 1; return }
    if ($script:Mode -eq 'noop') { $global:LASTEXITCODE = 0; return }
    & $RealIcacls @args
}

Invoke-Expression (Get-ScriptFunctionText '%SCRIPT%' @(%FUNCTIONS%))
%SETUP%
$result = [ordered]@{ outcome = 'RETURNED'; message = '' }
try {
    %CALL%
} catch {
    $result.outcome = 'THREW'
    $result.message = $_.Exception.Message
}
$result.exists = (Test-Path -LiteralPath $Target)
$result.content = if ($result.exists) { [System.IO.File]::ReadAllText($Target) } else { '' }
$result.sddl = if ($result.exists) { Read-TestSddl $Target } else { '' }
$result.expected = $Expected
$result.lengthAtRestriction = $script:LengthAtRestriction
$result.contentAtRestriction = $script:ContentAtRestriction
$result | ConvertTo-Json -Compress
"""


def run_case(executable: str, script: Path, functions: tuple[str, ...], call: str, mode: str = "observe",
             setup: str = "") -> dict:
    with tempfile.TemporaryDirectory(prefix="minos-secret-acl-") as work:
        body = (HARNESS.replace("%WORK%", work.replace("'", "''"))
                .replace("%MODE%", mode)
                .replace("%SCRIPT%", str(script).replace("'", "''"))
                .replace("%FUNCTIONS%", ", ".join(f"'{name}'" for name in functions))
                .replace("%SETUP%", setup)
                .replace("%CALL%", call))
        case = Path(work) / "case.ps1"
        case.write_text(body, encoding="utf-8-sig")
        completed = subprocess.run(
            [executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(case)],
            capture_output=True, text=True, timeout=180, check=False)
        lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
        if not lines:
            raise AssertionError(f"no JSON result (exit {completed.returncode})\n{completed.stdout}\n{completed.stderr}")
        return json.loads(lines[-1])


EXISTING_INHERITED = "[System.IO.File]::WriteAllText($Target, 'old-password' + [Environment]::NewLine)"
WRITE_SECRET = "Write-PrivateSecretFile $Target ('fixture-password' + [Environment]::NewLine)"
DOCKER_FUNCTIONS = ACL_HELPERS + ("Read-BoundedUtf8", "New-ManagedPassword")


@unittest.skipUnless(WINDOWS, SKIP_REASON)
class SecretFileAclTest(unittest.TestCase):

    def for_each_runtime(self, check) -> None:
        """Runs `check(executable)` under each runtime, one sub-test each: a failure under one does not hide the other."""
        available = runtimes()
        self.assertEqual(["Windows PowerShell 5.1", "pwsh"], [label for label, _ in available],
                         "both PowerShell runtimes are required: the installer runs under 5.1, development under pwsh")
        for label, executable in available:
            with self.subTest(runtime=label):
                check(executable)

    def assert_restricted(self, result: dict) -> None:
        self.assertTrue(result["exists"], result)
        self.assertEqual(result["expected"], result["sddl"], "inheritance cut and one entry, the current SID")


class InstallScriptSecretTest(SecretFileAclTest):
    """configure-runtime-settings.ps1: the installer overwrites the password file."""

    def test_the_secret_is_written_into_a_file_that_is_already_restricted(self):
        def check(executable):
            result = run_case(executable, INSTALL_SCRIPT, ACL_HELPERS, WRITE_SECRET)
            self.assertEqual("RETURNED", result["outcome"], result)
            self.assert_restricted(result)
            self.assertEqual("fixture-password", result["content"].strip())
            self.assertEqual(0, result["lengthAtRestriction"], "the file was restricted while still empty")

        self.for_each_runtime(check)

    def test_a_restriction_that_fails_leaves_no_file_and_never_writes_the_secret(self):
        def check(executable):
            result = run_case(executable, INSTALL_SCRIPT, ACL_HELPERS, WRITE_SECRET, mode="fail")
            self.assertEqual("THREW", result["outcome"], result)
            self.assertFalse(result["exists"], "the file is removed when it cannot be restricted")
            self.assertEqual("", result["contentAtRestriction"], "the secret was never on disk")

        self.for_each_runtime(check)

    def test_a_restriction_that_reports_success_without_effect_is_caught_by_the_verification(self):
        def check(executable):
            result = run_case(executable, INSTALL_SCRIPT, ACL_HELPERS, WRITE_SECRET, mode="noop")
            self.assertEqual("THREW", result["outcome"], result)
            self.assertIn("not restricted to the current user", result["message"])
            self.assertFalse(result["exists"])

        self.for_each_runtime(check)

    def test_an_existing_file_of_inherited_acl_is_replaced_never_written_into(self):
        def check(executable):
            result = run_case(executable, INSTALL_SCRIPT, ACL_HELPERS, WRITE_SECRET, setup=EXISTING_INHERITED)
            self.assertEqual("RETURNED", result["outcome"], result)
            self.assert_restricted(result)
            self.assertEqual("fixture-password", result["content"].strip())
            self.assertEqual(0, result["lengthAtRestriction"], "the old file was removed before the restriction")

        self.for_each_runtime(check)


class DockerScriptSecretTest(SecretFileAclTest):
    """configure-m30-docker-services.ps1: New-ManagedPassword creates the password once and keeps it."""

    def test_a_new_password_is_written_into_a_file_that_is_already_restricted(self):
        def check(executable):
            result = run_case(executable, DOCKER_SCRIPT, DOCKER_FUNCTIONS, "New-ManagedPassword $Target")
            self.assertEqual("RETURNED", result["outcome"], result)
            self.assert_restricted(result)
            self.assertGreaterEqual(len(result["content"].strip()), 40, "a generated password")
            self.assertEqual(0, result["lengthAtRestriction"], "the file was restricted while still empty")

        self.for_each_runtime(check)

    def test_a_restriction_that_fails_leaves_no_new_password_file(self):
        def check(executable):
            result = run_case(executable, DOCKER_SCRIPT, DOCKER_FUNCTIONS, "New-ManagedPassword $Target", mode="fail")
            self.assertEqual("THREW", result["outcome"], result)
            self.assertFalse(result["exists"], "the file is removed when it cannot be restricted")
            self.assertEqual("", result["contentAtRestriction"], "the password was never on disk")

        self.for_each_runtime(check)

    def test_an_existing_file_of_inherited_acl_is_restricted_before_it_is_kept(self):
        def check(executable):
            result = run_case(executable, DOCKER_SCRIPT, DOCKER_FUNCTIONS, "New-ManagedPassword $Target",
                              setup=EXISTING_INHERITED)
            self.assertEqual("RETURNED", result["outcome"], result)
            self.assert_restricted(result)
            self.assertEqual("old-password", result["content"].strip(), "an existing password is kept")

        self.for_each_runtime(check)

    def test_an_existing_file_that_cannot_be_restricted_is_refused_and_left_unchanged(self):
        def check(executable):
            result = run_case(executable, DOCKER_SCRIPT, DOCKER_FUNCTIONS, "New-ManagedPassword $Target",
                              mode="fail", setup=EXISTING_INHERITED)
            self.assertEqual("THREW", result["outcome"], result)
            self.assertIn("may have been exposed", result["message"])
            self.assertTrue(result["exists"])
            self.assertEqual("old-password", result["content"].strip(), "the content is not modified")

        self.for_each_runtime(check)


if __name__ == "__main__":
    if not WINDOWS:
        print(f"SKIPPED outside Windows: {SKIP_REASON}", file=sys.stderr)
    unittest.main()

#!/usr/bin/env python3
"""Tests of what the Docker helper scripts hand to Docker: the runtime ``.env`` and the image build context.

Three scripts write an input that Docker reads, and each must match the file Docker reads it with:

* ``docker/scripts/verify-run-configurations.ps1`` and the development launcher ``docker/scripts/prod-mcp.ps1``
  write a ``.env`` for ``docker/compose-mcp.prod.yaml``: every variable the compose file declares required
  (``${NAME:?...}``) must be defined there, otherwise ``docker compose config`` refuses the whole file;
* the Docker prebuild of ``scripts/release/build-local-windows-candidate.ps1`` assembles a build context for
  ``docker/Dockerfile.mcp.release``: every file a ``COPY`` instruction takes from the context must be in it.

The expectations are read from the real compose file and Dockerfile, and the real definitions are taken from
the scripts: the ``.env`` here-strings as written, the context assembly as a function extracted with the
PowerShell parser and run against a temporary directory. A last case runs the whole verification script
when a Docker engine with Compose answers (Windows only: the script builds Windows paths).

The PowerShell cases run under each PowerShell present: Windows PowerShell 5.1 and ``pwsh`` on Windows,
``pwsh`` elsewhere; without any they declare themselves skipped.
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
COMPOSE = ROOT / "docker" / "compose-mcp.prod.yaml"
DOCKERFILE = ROOT / "docker" / "Dockerfile.mcp.release"
VERIFY_SCRIPT = ROOT / "docker" / "scripts" / "verify-run-configurations.ps1"
DEV_LAUNCHER = ROOT / "docker" / "scripts" / "prod-mcp.ps1"
CANDIDATE_SCRIPT = ROOT / "scripts" / "release" / "build-local-windows-candidate.ps1"
LOCK_DIRECTORY = ROOT / "minos-provider-scip" / "src" / "main" / "resources" / "com" / "minos" / "adapter" / "scip" / "runtime"
CONTEXT_FUNCTION = "Copy-DockerBuildContext"

DECODING = {"encoding": "utf-8", "errors": "replace"}


def runtimes() -> list[tuple[str, str]]:
    """(label, executable) of every PowerShell the scripts must work under on this host."""
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


def required_compose_variables() -> set[str]:
    return set(re.findall(r"\$\{([A-Z][A-Z0-9_]*):\?", COMPOSE.read_text(encoding="utf-8")))


def runtime_env_variables(script: Path) -> set[str]:
    """Variable names of the one here-string of ``script`` that writes the compose ``.env``."""
    text = script.read_text(encoding="utf-8")
    blocks = [block for block in re.findall(r'@"\r?\n(.*?)\r?\n"@', text, re.DOTALL)
              if re.search(r"(?m)^MINOS_COMPOSE_PROJECT=", block)]
    if len(blocks) != 1:
        raise AssertionError(f"{script.name}: expected one .env here-string, found {len(blocks)}")
    return set(re.findall(r"(?m)^([A-Z][A-Z0-9_]*)=", blocks[0]))


def dockerfile_context_sources() -> set[str]:
    """Files the Dockerfile copies from the build context (``COPY`` without ``--from``)."""
    sources = set()
    for line in DOCKERFILE.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^\s*COPY\s+(?!--from)(.+)$", line)
        if match:
            sources.update(match.group(1).split()[:-1])
    return sources


HARNESS = r"""
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile('%SCRIPT%', [ref] $tokens, [ref] $errors)
if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
$found = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq '%NAME%' }, $true))
if ($found.Count -ne 1) { throw 'missing function in the candidate script: %NAME%' }
. ([scriptblock]::Create($found[0].Extent.Text))
%NAME% -Jar '%JAR%' -LockDirectory '%LOCKS%' -Context '%CONTEXT%'
"""


def ps_literal(value: Path | str) -> str:
    return str(value).replace("'", "''")


class RuntimeEnvironmentTest(unittest.TestCase):
    def test_compose_declares_required_variables(self) -> None:
        # Guards the regex: an empty set would make the two cases below vacuous.
        self.assertIn("MINOS_DATA_DIR", required_compose_variables())

    def test_every_script_that_runs_compose_on_the_runtime_passes_the_project_mounts(self) -> None:
        # Without the generated override, recreating minos-mcp drops every exposed project (SEC-0075).
        for relative in ("docker/scripts/mcp-lifecycle.ps1", "docker/scripts/prod-mcp.ps1",
                         "docker/scripts/configure-m30-docker-services.ps1", "scripts/ci/qualify-docker-upgrade.ps1"):
            text = (ROOT / relative).read_text(encoding="utf-8")
            self.assertIn("compose-mcp.projects.yaml", text, relative)
            self.assertNotRegex(text, r"'-f',\s*\$[Cc]omposeFile\s*,\s*'(run|up|ps|--profile)'", relative)

    def test_no_projects_root_is_required_or_written(self) -> None:
        # No projects root is mounted: the compose file requires none, and no launcher writes one in the .env.
        for name in ("MINOS_PROJECTS_DIR", "MINOS_HOST_PROJECTS_ROOT"):
            self.assertNotIn(name, required_compose_variables())
            self.assertNotIn(name, runtime_env_variables(VERIFY_SCRIPT))
            self.assertNotIn(name, runtime_env_variables(DEV_LAUNCHER))

    def test_verification_env_defines_every_required_variable(self) -> None:
        missing = required_compose_variables() - runtime_env_variables(VERIFY_SCRIPT)
        self.assertEqual(set(), missing, "verify-run-configurations.ps1 .env misses required compose variables")

    def test_dev_launcher_env_defines_every_required_variable(self) -> None:
        missing = required_compose_variables() - runtime_env_variables(DEV_LAUNCHER)
        self.assertEqual(set(), missing, "prod-mcp.ps1 .env misses required compose variables")


class CandidateBuildContextTest(unittest.TestCase):
    def setUp(self) -> None:
        self.runtimes = runtimes()
        if not self.runtimes:
            self.skipTest("no PowerShell on this host")

    def run_context(self, executable: str, lock_directory: Path) -> tuple[subprocess.CompletedProcess, Path]:
        work = Path(tempfile.mkdtemp(prefix="minos-context-test-"))
        self.addCleanup(shutil.rmtree, work, True)
        jar = work / "minos.jar"
        jar.write_bytes(b"fake jar")
        context = work / "context"
        context.mkdir()
        script = (HARNESS.replace("%SCRIPT%", ps_literal(CANDIDATE_SCRIPT)).replace("%NAME%", CONTEXT_FUNCTION)
                  .replace("%JAR%", ps_literal(jar)).replace("%LOCKS%", ps_literal(lock_directory))
                  .replace("%CONTEXT%", ps_literal(context)))
        harness = work / "harness.ps1"
        harness.write_text(script, encoding="utf-8-sig")
        result = subprocess.run([executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(harness)],
                                capture_output=True, timeout=120, **DECODING)
        return result, context

    def test_context_holds_every_file_the_dockerfile_copies(self) -> None:
        expected = dockerfile_context_sources()
        self.assertIn("minos.jar", expected)
        self.assertTrue(any(name.endswith("package-lock.json") for name in expected))
        for label, executable in self.runtimes:
            with self.subTest(runtime=label):
                result, context = self.run_context(executable, LOCK_DIRECTORY)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(expected, {path.name for path in context.iterdir()})
                for name in expected - {"minos.jar"}:
                    self.assertEqual((LOCK_DIRECTORY / name).read_bytes(), (context / name).read_bytes(), name)

    def test_missing_lockfile_fails_the_prebuild(self) -> None:
        empty = Path(tempfile.mkdtemp(prefix="minos-context-locks-"))
        self.addCleanup(shutil.rmtree, empty, True)
        for label, executable in self.runtimes:
            with self.subTest(runtime=label):
                result, _ = self.run_context(executable, empty)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Docker prebuild input missing", result.stdout + result.stderr)

    def test_prebuild_builds_from_the_assembled_context(self) -> None:
        text = CANDIDATE_SCRIPT.read_text(encoding="utf-8")
        self.assertRegex(text, r"(?m)^\s*Copy-DockerBuildContext\s+-Jar\s+\$Jar\s+-LockDirectory\s+\S+\s+-Context\s+\$Context\b")


def compose_available() -> bool:
    docker = shutil.which("docker")
    if not docker:
        return False
    try:
        return subprocess.run([docker, "compose", "version"], capture_output=True, timeout=60).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


@unittest.skipUnless(os.name == "nt", "the verification script builds Windows paths: runs on Windows")
class VerificationScriptTest(unittest.TestCase):
    def test_verification_script_succeeds(self) -> None:
        if not compose_available():
            self.skipTest("no Docker engine with Compose on this host")
        for label, executable in runtimes():
            with self.subTest(runtime=label):
                result = subprocess.run(
                    [executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(VERIFY_SCRIPT)],
                    capture_output=True, timeout=300, cwd=ROOT, **DECODING)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("MINOS launch configurations SUCCESS", result.stdout)


if __name__ == "__main__":
    unittest.main()

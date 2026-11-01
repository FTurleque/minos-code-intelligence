#!/usr/bin/env python3
"""Tests of the two-image contract of docker/scripts/mcp-lifecycle.ps1 (AUD-DEP-15).

The MCP query plane has its own image (``MINOS_QUERY_IMAGE``). An installation whose runtime compose file
asks for it while the runtime ``.env`` does not define it must fail BEFORE any call to Docker, with a message
that names the repair command, and must never fall back to the administration image.

Two kinds of cases, none of which re-implements the script:

* the *real* function definitions, extracted with the PowerShell parser (the script cannot be dot-sourced: it
  runs an action at load time), against the real compose files and Dockerfile of the repository;
* the *whole script*, run with a ``docker`` executable that only records its arguments: the refusal is proved
  by an empty record, and a positive control proves that the record is not empty when the script proceeds.

Every case runs under each PowerShell present: Windows PowerShell 5.1 (the installer's runtime) and ``pwsh``
on Windows, ``pwsh`` elsewhere. Without any PowerShell the tests declare themselves skipped.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIFECYCLE = ROOT / "docker" / "scripts" / "mcp-lifecycle.ps1"
DOCKERFILE = ROOT / "docker" / "Dockerfile.mcp.release"
COMPOSE_FILES = ("compose-mcp.prod.yaml", "compose-mcp.connected.yaml")
QUERY_REFERENCE = "${MINOS_QUERY_IMAGE:?MINOS_QUERY_IMAGE is required}"
ADMIN_REFERENCE = "${MINOS_IMAGE:?MINOS_IMAGE is required}"
FUNCTIONS = ("Get-ReleaseImageTargets", "Resolve-QueryImage", "Read-EnvironmentValue", "Test-ComposeUsesQueryImage",
             "Assert-QueryImageConfigured")
EMPTY_TABLE = '{"formatVersion":2,"containerRoot":"/workspace/projects","mounts":[]}'
BASE_ENVIRONMENT = (
    "MINOS_COMPOSE_PROJECT=minos-test\n"
    "MINOS_CONTAINER_NAME=minos-test\n"
    "MINOS_IMAGE=minos-code-intelligence:test\n"
    "MINOS_VERSION=test\n"
    "MINOS_GIT_COMMIT=test\n"
)


# Windows PowerShell 5.1 writes localized warnings in the console code page: the output is only searched for
# ASCII text, so an undecodable byte must never make the capture fail (PYTHONUTF8=1 in the gate runner).
DECODING = {"encoding": "utf-8", "errors": "replace"}


def runtimes() -> list[tuple[str, str]]:
    """(label, executable) of every PowerShell the lifecycle script must work under on this host."""
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


HARNESS = r"""
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Get-ScriptFunctionText([string] $Path, [string[]] $Names) {
    $tokens = $null; $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref] $tokens, [ref] $errors)
    if ($errors.Count -gt 0) { throw "parse failed: $($errors[0].Message)" }
    $found = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Names -contains $node.Name }, $true)
    $missing = @($Names | Where-Object { @($found | ForEach-Object { $_.Name }) -notcontains $_ })
    if ($missing.Count -gt 0) { throw "missing function(s) in the lifecycle script: $($missing -join ', ')" }
    return (($found | ForEach-Object { $_.Extent.Text }) -join [Environment]::NewLine)
}
. ([scriptblock]::Create((Get-ScriptFunctionText '%SCRIPT%' @(%NAMES%))))
$script:DockerCalls = 0
function docker { $script:DockerCalls++ }
$ComposeFile = '%COMPOSE%'
$EnvironmentFile = '%ENVIRONMENT%'
try {
    $value = @(& { %CALL% })
    $result = [ordered]@{ outcome = 'RETURNED'; value = $value; dockerCalls = $script:DockerCalls }
} catch {
    $result = [ordered]@{ outcome = 'THREW'; message = $_.Exception.Message; dockerCalls = $script:DockerCalls }
}
$result | ConvertTo-Json -Compress
"""


def last_json(completed: subprocess.CompletedProcess) -> dict:
    lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
    if not lines:
        raise AssertionError(f"no JSON result (exit {completed.returncode})\n{completed.stdout}\n{completed.stderr}")
    return json.loads(lines[-1])


def run_functions(executable: str, directory: Path, call: str, compose: Path | None = None,
                  environment: Path | None = None) -> dict:
    body = (HARNESS.replace("%SCRIPT%", str(LIFECYCLE))
            .replace("%NAMES%", ",".join(f"'{name}'" for name in FUNCTIONS))
            .replace("%COMPOSE%", str(compose or directory / "absent-compose.yaml"))
            .replace("%ENVIRONMENT%", str(environment or directory / "absent.env"))
            .replace("%CALL%", call))
    script = directory / "case.ps1"
    script.write_text(body, encoding="utf-8-sig")
    completed = subprocess.run([executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)],
                               capture_output=True, timeout=180, check=False, **DECODING)
    return last_json(completed)


def legacy_compose(name: str) -> str:
    """The compose file of a release that had one image: every service on the administration image."""
    return (ROOT / "docker" / name).read_text(encoding="utf-8").replace(QUERY_REFERENCE, ADMIN_REFERENCE)


class PowerShellCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runtimes = runtimes()
        if not cls.runtimes:
            raise unittest.SkipTest("PowerShell is not installed")


class ImageTargetTests(PowerShellCase):
    """Which images a release recipe builds: read from the Dockerfile that is about to be built."""

    def targets(self, executable: str, dockerfile_text: str | None) -> dict:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            path = DOCKERFILE
            if dockerfile_text is not None:
                path = directory / "Dockerfile.mcp.release"
                path.write_text(dockerfile_text, encoding="utf-8")
            return run_functions(executable, directory, f"Get-ReleaseImageTargets '{path}'")

    def test_the_repository_recipe_builds_the_administration_image_then_the_query_image(self):
        for label, executable in self.runtimes:
            result = self.targets(executable, None)
            self.assertEqual("RETURNED", result["outcome"], (label, result))
            self.assertEqual(["admin", "query"], result["value"], label)

    def test_a_recipe_of_a_release_that_had_one_image_builds_one_image(self):
        text = DOCKERFILE.read_text(encoding="utf-8")
        single = "".join(line for line in text.splitlines(keepends=True)
                         if not line.rstrip().endswith(" AS query")).replace(" AS admin", "")
        self.assertNotIn(" AS query", single)
        for label, executable in self.runtimes:
            result = self.targets(executable, single)
            self.assertEqual("RETURNED", result["outcome"], (label, result))
            self.assertEqual([], result["value"], label)

    def test_a_recipe_with_only_one_of_the_two_targets_is_refused(self):
        text = DOCKERFILE.read_text(encoding="utf-8")
        for broken in (text.replace(" AS admin", ""), text.replace(" AS query", " AS lookup")):
            for label, executable in self.runtimes:
                result = self.targets(executable, broken)
                self.assertEqual("THREW", result["outcome"], (label, result))
                self.assertIn("query", result["message"], label)
                self.assertIn("admin", result["message"], label)

    def test_the_query_image_is_named_after_the_administration_image(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                result = run_functions(executable, Path(tmp), "Resolve-QueryImage 'minos-code-intelligence:1.3.0-abc'")
            self.assertEqual(["minos-code-intelligence:1.3.0-abc-query"], result["value"], label)


class RefusalTests(PowerShellCase):
    """The guard itself: real compose files, a runtime .env with or without the query image."""

    def guard(self, executable: str, compose_text: str, environment_text: str | None) -> tuple[dict, str]:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            compose = directory / "compose-mcp.prod.yaml"
            compose.write_text(compose_text, encoding="utf-8")
            environment = directory / ".env"
            if environment_text is not None:
                environment.write_text(environment_text, encoding="ascii")
            return run_functions(executable, directory, "Assert-QueryImageConfigured", compose, environment), tmp

    def assert_actionable_refusal(self, result: dict, directory: str, context):
        self.assertEqual("THREW", result["outcome"], (context, result))
        message = result["message"]
        self.assertIn("MINOS_QUERY_IMAGE", message, context)
        self.assertIn("-Action Install", message, (context, "the message must name the repair command"))
        self.assertIn("never falls back", message, context)
        self.assertNotIn(directory, message, (context, "no absolute path in a public message"))
        self.assertNotIn(os.path.basename(directory), message, context)
        self.assertEqual(0, result["dockerCalls"], (context, "the refusal comes before any call to Docker"))

    def test_a_runtime_without_the_query_image_is_refused_with_the_repair_command(self):
        for name in COMPOSE_FILES:
            compose = (ROOT / "docker" / name).read_text(encoding="utf-8")
            self.assertIn(QUERY_REFERENCE, compose, f"{name} must ask for the query image")
            for label, executable in self.runtimes:
                for case, environment in (("absent", BASE_ENVIRONMENT),
                                          ("empty", BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=\n"),
                                          ("blank", BASE_ENVIRONMENT + 'MINOS_QUERY_IMAGE="  "\n'),
                                          ("commented", BASE_ENVIRONMENT + "# MINOS_QUERY_IMAGE=minos:x-query\n"),
                                          ("no .env", None)):
                    result, directory = self.guard(executable, compose, environment)
                    self.assert_actionable_refusal(result, directory, (name, label, case))

    def test_a_runtime_with_the_query_image_is_accepted(self):
        environment = BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=minos-code-intelligence:test-query\n"
        for name in COMPOSE_FILES:
            for label, executable in self.runtimes:
                result, _ = self.guard(executable, (ROOT / "docker" / name).read_text(encoding="utf-8"), environment)
                self.assertEqual("RETURNED", result["outcome"], (name, label, result))

    def test_the_runtime_of_a_release_that_had_one_image_is_left_alone(self):
        # Its compose file never names the query image: it keeps running as that release installed it, until
        # the next Install migrates it (this is what the Docker A -> B upgrade qualification starts from).
        for name in COMPOSE_FILES:
            for label, executable in self.runtimes:
                result, _ = self.guard(executable, legacy_compose(name), BASE_ENVIRONMENT)
                self.assertEqual("RETURNED", result["outcome"], (name, label, result))

    def test_the_last_definition_of_a_variable_wins_as_for_compose(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp)
                environment = directory / ".env"
                environment.write_text("A=1\nMINOS_QUERY_IMAGE=first\nMINOS_QUERY_IMAGE_OTHER=no\n"
                                       'MINOS_QUERY_IMAGE="second"\n', encoding="ascii")
                result = run_functions(executable, directory,
                                       f"Read-EnvironmentValue -Path '{environment}' -Name 'MINOS_QUERY_IMAGE'")
            self.assertEqual(["second"], result["value"], label)


ANSI_SEQUENCE = re.compile("\x1b" + r"\[[0-9;]*m")


def flatten(output: str) -> str:
    """A PowerShell error as one line: colour sequences and the "| " gutter of wrapped lines removed."""
    lines = (re.sub(r"^[|+]\s?", "", line.strip()) for line in ANSI_SEQUENCE.sub("", output).splitlines())
    return " ".join(" ".join(lines).split())


def fake_docker(directory: Path) -> Path:
    """A ``docker`` that records each invocation in $FAKE_DOCKER_LOG and answers ``image inspect`` and
    ``list-project-mounts`` from files.

    Each recorded line ends with ``[query-image=<value>]``: the MINOS_QUERY_IMAGE the process received.
    """
    directory.mkdir(parents=True, exist_ok=True)
    if os.name == "nt":
        path = directory / "docker.cmd"
        path.write_text('@echo off\r\necho %* [query-image=%MINOS_QUERY_IMAGE%]>>"%FAKE_DOCKER_LOG%"\r\n'
                        'if "%1 %2"=="image inspect" type "%FAKE_DOCKER_INSPECT%"\r\n'
                        'echo %* | findstr /C:"list-project-mounts" >nul && type "%FAKE_DOCKER_TABLE%"\r\n'
                        'exit /b 0\r\n', encoding="ascii")
    else:
        path = directory / "docker"
        path.write_text('#!/bin/sh\nprintf \'%s [query-image=%s]\\n\' "$*" "${MINOS_QUERY_IMAGE:-}" >> "$FAKE_DOCKER_LOG"\n'
                        'if [ "$1 $2" = "image inspect" ]; then cat "$FAKE_DOCKER_INSPECT"; fi\n'
                        'case "$*" in *list-project-mounts*) cat "$FAKE_DOCKER_TABLE";; esac\nexit 0\n', encoding="ascii")
        path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


# Calls the lifecycle script IN-PROCESS, as scripts/ci/qualify-docker-upgrade.ps1 and the installer scripts do, then
# prints the MINOS_QUERY_IMAGE left in the environment of the caller.
WRAPPER = r"""
param([string] $Script, [string] $Action, [string] $Root, [string] $ProjectPath = '', [string] $Name = '')
$ErrorActionPreference = 'Stop'
$Extra = @{}
if ($ProjectPath -ne '') { $Extra['ProjectPath'] = $ProjectPath }
if ($Name -ne '') { $Extra['Name'] = $Name }
& $Script -Action $Action -InstallRoot (Join-Path $Root 'install') -DataRoot (Join-Path $Root 'data') @Extra
Write-Output "CALLER-QUERY-IMAGE=[$env:MINOS_QUERY_IMAGE]"
"""


class WholeScriptTests(PowerShellCase):
    """The real script, action Start, with a recording docker: nothing is started on a refusal."""

    def start(self, executable: str, compose_text: str, environment_text: str, plane: str = "query",
              action: str = "Start", ambient_query_image: str | None = None, table: str = EMPTY_TABLE,
              extra: tuple[str, ...] = (), prepare=None) -> tuple[int, str, list[str]]:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            runtime = directory / "install" / "runtime"
            runtime.mkdir(parents=True)
            (directory / "data").mkdir()
            (directory / "projects").mkdir()
            (runtime / "compose-mcp.prod.yaml").write_text(compose_text, encoding="utf-8")
            (runtime / ".env").write_text(environment_text, encoding="ascii")
            (runtime / "installation.json").write_text('{"formatVersion":5,"image":"minos-code-intelligence:test"}',
                                                       encoding="utf-8")
            (runtime / "provider-inventory.json").write_text("{}", encoding="utf-8")
            (runtime / "provider-binary-sha256.txt").write_text("", encoding="ascii")
            log = directory / "docker-calls.log"
            inspect = directory / "inspect.json"
            inspect.write_text(json.dumps([{"Config": {"Labels": {"io.minos.image.plane": plane}}}]), encoding="ascii")
            fake = fake_docker(directory / "bin")
            table_file = directory / "table.json"
            table_file.write_text(table.replace("%ROOT%", str(directory).replace("\\", "/")), encoding="ascii")
            if prepare is not None:
                prepare(directory)
            environment = dict(os.environ, FAKE_DOCKER_LOG=str(log), FAKE_DOCKER_INSPECT=str(inspect),
                               FAKE_DOCKER_TABLE=str(table_file),
                               PATH=str(fake.parent) + os.pathsep + os.environ.get("PATH", ""))
            environment.pop("MINOS_QUERY_IMAGE", None)
            if ambient_query_image is not None:
                environment["MINOS_QUERY_IMAGE"] = ambient_query_image
            wrapper = directory / "call.ps1"
            wrapper.write_text(WRAPPER, encoding="utf-8-sig")
            completed = subprocess.run(
                [executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(wrapper),
                 "-Script", str(LIFECYCLE), "-Action", action, "-Root", str(directory),
                 *(value.replace("%ROOT%", str(directory)) for value in extra)],
                capture_output=True, timeout=180, check=False, env=environment, stdin=subprocess.DEVNULL, **DECODING)
            calls = log.read_text(encoding="utf-8", errors="replace").splitlines() if log.is_file() else []
            override = runtime / "compose-mcp.projects.yaml"
            self.last_override = override.read_text(encoding="utf-8") if override.is_file() else None
            return completed.returncode, completed.stdout + completed.stderr, calls

    def test_start_without_the_query_image_fails_before_any_docker_call(self):
        compose = (ROOT / "docker" / "compose-mcp.prod.yaml").read_text(encoding="utf-8")
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, compose, BASE_ENVIRONMENT)
            self.assertNotEqual(0, code, (label, output))
            self.assertEqual([], calls, (label, "docker was called although the query image is not configured"))
            flat = flatten(output)
            self.assertIn("MINOS_QUERY_IMAGE", flat, label)
            self.assertIn("-Action Install", flat, label)

    def test_start_with_the_query_image_reaches_docker(self):
        # Positive control of the case above: with the same harness the recording docker IS called.
        compose = (ROOT / "docker" / "compose-mcp.prod.yaml").read_text(encoding="utf-8")
        environment = BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=minos-code-intelligence:test-query\n"
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, compose, environment)
            self.assertEqual(0, code, (label, output))
            self.assertTrue(any("up -d --force-recreate minos-mcp" in call for call in calls), (label, calls))

    def test_start_refuses_a_query_image_that_is_not_a_query_plane_image(self):
        # MINOS_QUERY_IMAGE pointed at the administration image by hand: inspected, then refused; nothing is started.
        compose = (ROOT / "docker" / "compose-mcp.prod.yaml").read_text(encoding="utf-8")
        environment = BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=minos-code-intelligence:test\n"
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, compose, environment, plane="admin")
            self.assertNotEqual(0, code, (label, output))
            self.assertFalse(any(call.startswith("compose") for call in calls), (label, calls))
            self.assertIn("-Action Install", flatten(output), label)

    def test_start_of_a_single_image_runtime_still_works(self):
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, legacy_compose("compose-mcp.prod.yaml"), BASE_ENVIRONMENT)
            self.assertEqual(0, code, (label, output))
            self.assertTrue(any("up -d --force-recreate minos-mcp" in call for call in calls), (label, calls))


    def test_a_runtime_that_is_not_migrated_can_still_be_stopped(self):
        # Stop starts nothing: it is not refused, so that the repair is never blocked by a running container.
        compose = (ROOT / "docker" / "compose-mcp.prod.yaml").read_text(encoding="utf-8")
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, compose, BASE_ENVIRONMENT, action="Stop")
            self.assertEqual(0, code, (label, output))
            stops = [call for call in calls if "stop --timeout 10 minos-mcp" in call]
            self.assertEqual(1, len(stops), (label, calls))
            # Compose interpolates the file even to stop: it receives a placeholder that names no image...
            self.assertIn("[query-image=minos-query-image-not-configured]", stops[0], label)
            # ...which never reaches the caller: the next action of the same process must not inherit it.
            self.assertIn("CALLER-QUERY-IMAGE=[]", output, (label, "the placeholder leaked into the caller"))

    def test_an_ambient_query_image_never_replaces_the_one_of_the_runtime(self):
        # For Compose the process environment wins over --env-file: the variable is hidden during the call.
        compose = (ROOT / "docker" / "compose-mcp.prod.yaml").read_text(encoding="utf-8")
        environment = BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=minos-code-intelligence:test-query\n"
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, compose, environment,
                                             ambient_query_image="minos-code-intelligence:test")
            self.assertEqual(0, code, (label, output))
            ups = [call for call in calls if "up -d --force-recreate minos-mcp" in call]
            self.assertEqual(1, len(ups), (label, calls))
            self.assertIn("[query-image=]", ups[0], (label, "Compose received the ambient MINOS_QUERY_IMAGE"))
            self.assertIn("CALLER-QUERY-IMAGE=[minos-code-intelligence:test]", output,
                          (label, "the environment of the caller must be restored"))

    def test_validate_without_the_query_image_fails_before_any_docker_call(self):
        compose = (ROOT / "docker" / "compose-mcp.connected.yaml").read_text(encoding="utf-8")
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, compose, BASE_ENVIRONMENT, action="Validate")
            self.assertNotEqual(0, code, (label, output))
            self.assertEqual([], calls, label)
            self.assertIn("-Action Install", flatten(output), label)


# ---------------------------------------------------------------------------------------------------------------
# Project mounts: no projects root is mounted; one generated read-only mount per exposed project.
# ---------------------------------------------------------------------------------------------------------------

MOUNT_FUNCTIONS = ("ConvertTo-YamlString", "Format-ProjectMountsOverride")
SPECIAL_SOURCE = "D:/src/it's #1: app\\x\"y"


def run_mount_functions(executable: str, directory: Path, call: str) -> dict:
    body = (HARNESS.replace("%SCRIPT%", str(LIFECYCLE))
            .replace("%NAMES%", ",".join(f"'{name}'" for name in MOUNT_FUNCTIONS))
            .replace("%COMPOSE%", str(directory / "absent-compose.yaml"))
            .replace("%ENVIRONMENT%", str(directory / "absent.env"))
            .replace("%CALL%", "$ContainerProjectsRoot = '/workspace/projects'; " + call))
    script = directory / "case.ps1"
    script.write_text(body, encoding="utf-8-sig")
    completed = subprocess.run([executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)],
                               capture_output=True, timeout=180, check=False, **DECODING)
    return last_json(completed)


def override_mounts(text: str) -> list[dict]:
    """The mounts of a generated override, by service: each mount line is a JSON object."""
    mounts, service = [], None
    for line in text.splitlines():
        if line.startswith("  ") and not line.startswith("   ") and line.strip().endswith(":"):
            service = line.strip()[:-1]
        elif line.startswith("      - "):
            mounts.append(dict(json.loads(line[len("      - "):]), service=service))
    return mounts


def compose_available() -> bool:
    if shutil.which("docker") is None:
        return False
    return subprocess.run(["docker", "compose", "version"], capture_output=True, check=False).returncode == 0


class ProjectMountsOverrideTests(PowerShellCase):
    """The override text, from the real functions: every scalar is an escaped string."""

    def generate(self, executable: str, mounts: str) -> str:
        with tempfile.TemporaryDirectory() as tmp:
            result = run_mount_functions(executable, Path(tmp), f"Format-ProjectMountsOverride -Mounts @({mounts})")
        self.assertEqual("RETURNED", result["outcome"], result)
        return "".join(result["value"])

    def test_a_dollar_sign_is_doubled_so_that_compose_never_interpolates_it(self):
        for label, executable in self.runtimes:
            text = self.generate(executable, "[pscustomobject]@{ Name = 'app'; HostRoot = 'D:/src/p/${A:-..}' }")
            sources = {mount["source"] for mount in override_mounts(text)}
            self.assertEqual({"D:/src/p/$${A:-..}"}, sources, (label, text))

    def test_an_empty_table_mounts_nothing(self):
        for label, executable in self.runtimes:
            text = self.generate(executable, "")
            self.assertIn("services: {}", text, label)
            self.assertEqual([], override_mounts(text), label)

    def test_each_mount_is_read_only_and_the_same_in_both_planes(self):
        source = SPECIAL_SOURCE.replace("'", "''")
        for label, executable in self.runtimes:
            text = self.generate(executable, f"[pscustomobject]@{{ Name = 'app'; HostRoot = '{source}' }}, "
                                             "[pscustomobject]@{ Name = 'team/b'; HostRoot = 'N:/w/team/b' }")
            mounts = override_mounts(text)
            self.assertEqual({"minos-mcp", "minos-admin"}, {mount["service"] for mount in mounts}, label)
            self.assertEqual(4, len(mounts), (label, text))
            for mount in mounts:
                self.assertEqual("bind", mount["type"], label)
                self.assertTrue(mount["read_only"], label)
                self.assertFalse(mount["bind"]["create_host_path"], label)
            app = [mount for mount in mounts if mount["target"] == "/workspace/projects/app"]
            self.assertEqual([SPECIAL_SOURCE] * 2, [mount["source"] for mount in app], label)
            self.assertTrue(all(ord(character) < 128 for character in text), (label, "the override is ASCII"))

    @unittest.skipUnless(compose_available(), "docker compose is not installed")
    def test_docker_compose_accepts_the_override_with_special_characters(self):
        executable = self.runtimes[0][1]
        source = SPECIAL_SOURCE.replace("'", "''")
        text = self.generate(executable, f"[pscustomobject]@{{ Name = 'app'; HostRoot = '{source}' }}")
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "data").mkdir()
            override = directory / "compose-mcp.projects.yaml"
            override.write_text(text, encoding="ascii")
            environment = directory / ".env"
            environment.write_text(BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=minos-code-intelligence:test-query\n"
                                   + f"MINOS_DATA_DIR={(directory / 'data').as_posix()}\n", encoding="ascii")
            completed = subprocess.run(
                ["docker", "compose", "--project-directory", str(directory), "--env-file", str(environment),
                 "-f", str(ROOT / "docker" / "compose-mcp.prod.yaml"), "-f", str(override), "config", "--format", "json"],
                capture_output=True, check=False, **DECODING)
            self.assertEqual(0, completed.returncode, completed.stderr)
            config = json.loads(completed.stdout)
            for service in ("minos-mcp", "minos-admin"):
                mounts = [volume for volume in config["services"][service]["volumes"]
                          if volume["target"] == "/workspace/projects/app"]
                self.assertEqual(1, len(mounts), (service, config["services"][service]["volumes"]))
                self.assertTrue(mounts[0]["read_only"], service)
                self.assertTrue(mounts[0]["source"].replace("\\", "/").endswith("it's #1: app\\x\"y".replace("\\", "/"))
                                or mounts[0]["source"] == SPECIAL_SOURCE, mounts[0])


QUERY_ENVIRONMENT = BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=minos-code-intelligence:test-query\n"


def table_of(*mounts: tuple[str, str]) -> str:
    return json.dumps({"formatVersion": 2, "containerRoot": "/workspace/projects",
                       "mounts": [{"name": name, "path": name, "hostRoot": host} for name, host in mounts]})


class ProjectMountLifecycleTests(PowerShellCase):
    """The real script with a recording docker: override regenerated, folder checked before the bootstrap."""

    start = WholeScriptTests.start

    compose = (ROOT / "docker" / "compose-mcp.prod.yaml").read_text(encoding="utf-8")

    def test_every_compose_call_passes_the_regenerated_override(self):
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, self.compose, QUERY_ENVIRONMENT)
            self.assertEqual(0, code, (label, output))
            composes = [call for call in calls if call.startswith("compose")]
            self.assertTrue(composes, (label, calls))
            self.assertTrue(all("compose-mcp.projects.yaml" in call for call in composes), (label, composes))
            listing = next(index for index, call in enumerate(composes) if "list-project-mounts" in call)
            up = next(index for index, call in enumerate(composes) if "up -d --force-recreate minos-mcp" in call)
            self.assertLess(listing, up, (label, composes))

    def test_a_missing_folder_is_omitted_with_a_warning_and_the_start_goes_on(self):
        for label, executable in self.runtimes:
            table = table_of(("app", "%ROOT%/projects/app"), ("gone", "%ROOT%/projects/gone"))
            code, output, calls = self.start(executable, self.compose, QUERY_ENVIRONMENT, table=table,
                                             prepare=lambda root: (root / "projects" / "app").mkdir())
            self.assertEqual(0, code, (label, output))
            self.assertTrue(any("up -d --force-recreate minos-mcp" in call for call in calls), (label, calls))
            targets = {mount["target"] for mount in override_mounts(self.last_override)}
            self.assertEqual({"/workspace/projects/app"}, targets, (label, self.last_override))
            self.assertIn("'gone' is not mounted", flatten(output), label)

    def test_an_override_edited_by_hand_is_rewritten(self):
        def edit(root: Path) -> None:
            (root / "install" / "runtime" / "compose-mcp.projects.yaml").write_text(
                'services:\n  minos-mcp:\n    volumes:\n      - {"type": "bind", "source": "/", '
                '"target": "/workspace/projects/everything", "read_only": true}\n', encoding="ascii")
        for label, executable in self.runtimes:
            code, output, _ = self.start(executable, self.compose, QUERY_ENVIRONMENT, prepare=edit)
            self.assertEqual(0, code, (label, output))
            self.assertNotIn("everything", self.last_override, label)
            self.assertIn("services: {}", self.last_override, label)

    def add(self, executable: str, folder: str, prepare=None, name: str = "") -> tuple[int, str, list[str]]:
        extra = ("-ProjectPath", folder) + (("-Name", name) if name else ())
        return self.start(executable, self.compose, QUERY_ENVIRONMENT, action="AddProject", extra=extra,
                          prepare=prepare)

    def test_the_profile_is_refused_before_the_bootstrap(self):
        for label, executable in self.runtimes:
            code, output, calls = self.add(executable, str(Path.home()))
            self.assertNotEqual(0, code, (label, output))
            self.assertFalse(any("add-project-mount" in call or "up -d" in call for call in calls), (label, calls))
            flat = flatten(output)
            self.assertIn("user profile", flat, label)
            self.assertNotIn(str(Path.home()), flat.replace(" ", ""), (label, "the refusal names no path"))

    def test_a_folder_with_a_dollar_sign_is_refused_before_the_bootstrap(self):
        for label, executable in self.runtimes:
            code, output, calls = self.add(executable, "%ROOT%/projects/p$x",
                                           prepare=lambda root: (root / "projects" / "p$x").mkdir())
            self.assertNotEqual(0, code, (label, output))
            self.assertFalse(any("add-project-mount" in call for call in calls), (label, calls))
            self.assertIn("dollar sign", flatten(output), label)

    def test_a_mounted_folder_that_became_a_link_is_omitted_at_start(self):
        def link(root: Path) -> None:
            target = root / "projects" / "real"
            target.mkdir()
            if os.name == "nt":
                subprocess.run(["cmd", "/c", "mklink", "/J", str(root / "projects" / "app"), str(target)],
                               capture_output=True, check=True)
            else:
                os.symlink(target, root / "projects" / "app")
        for label, executable in self.runtimes:
            code, output, _ = self.start(executable, self.compose, QUERY_ENVIRONMENT, prepare=link,
                                         table=table_of(("app", "%ROOT%/projects/app")))
            self.assertEqual(0, code, (label, output))
            self.assertEqual([], override_mounts(self.last_override), (label, self.last_override))
            self.assertIn("junction or a symbolic link", flatten(output), label)

    def test_a_missing_folder_is_refused_before_the_bootstrap(self):
        for label, executable in self.runtimes:
            code, output, calls = self.add(executable, str(Path(tempfile.gettempdir()) / "minos-absent-project-folder"))
            self.assertNotEqual(0, code, (label, output))
            self.assertFalse(any("add-project-mount" in call for call in calls), (label, calls))

    def test_a_link_to_a_project_folder_is_refused_before_the_bootstrap(self):
        def link(root: Path) -> None:
            target = root / "projects" / "real"
            target.mkdir()
            if os.name == "nt":
                subprocess.run(["cmd", "/c", "mklink", "/J", str(root / "projects" / "link"), str(target)],
                               capture_output=True, check=True)
            else:
                os.symlink(target, root / "projects" / "link")
        for label, executable in self.runtimes:
            # The link is created by prepare under the case root: %ROOT% is replaced by that root.
            code, output, calls = self.start(
                executable, self.compose, QUERY_ENVIRONMENT, action="AddProject", prepare=link,
                extra=("-ProjectPath", "%ROOT%/projects/link"))
            self.assertNotEqual(0, code, (label, output))
            self.assertFalse(any("add-project-mount" in call for call in calls), (label, calls))
            self.assertIn("junction or a symbolic link", flatten(output), label)

    def test_a_dedicated_folder_is_mounted_then_registered(self):
        for label, executable in self.runtimes:
            code, output, calls = self.add(executable, "%ROOT%/projects/app", name="app",
                                           prepare=lambda root: (root / "projects" / "app").mkdir())
            self.assertEqual(0, code, (label, output))
            order = [next(index for index, call in enumerate(calls) if marker in call)
                     for marker in ("add-project-mount app", "up -d --force-recreate minos-mcp",
                                    "project add /workspace/projects/app")]
            self.assertEqual(sorted(order), order, (label, calls))

    def test_an_invalid_name_is_refused_before_the_bootstrap(self):
        for label, executable in self.runtimes:
            code, output, calls = self.add(executable, "%ROOT%/projects/app", name="App",
                                           prepare=lambda root: (root / "projects" / "app").mkdir())
            self.assertNotEqual(0, code, (label, output))
            self.assertFalse(any("add-project-mount" in call for call in calls), (label, calls))

    def test_remove_project_needs_a_name_and_recreates_the_query_plane(self):
        for label, executable in self.runtimes:
            code, output, calls = self.start(executable, self.compose, QUERY_ENVIRONMENT, action="RemoveProject")
            self.assertNotEqual(0, code, (label, output))
            self.assertFalse(any(call.startswith("compose") for call in calls), (label, calls))
            code, output, calls = self.start(executable, self.compose, QUERY_ENVIRONMENT, action="RemoveProject",
                                             extra=("-Name", "app"))
            self.assertEqual(0, code, (label, output))
            removal = next(index for index, call in enumerate(calls) if "remove-project-mount app" in call)
            up = next(index for index, call in enumerate(calls) if "up -d --force-recreate minos-mcp" in call)
            self.assertLess(removal, up, (label, calls))


if __name__ == "__main__":
    sys.exit(unittest.main(verbosity=2))

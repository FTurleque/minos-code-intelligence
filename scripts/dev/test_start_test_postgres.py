#!/usr/bin/env python3
"""Tests of scripts/dev/start-test-postgres.sh and scripts/dev/start-test-postgres.ps1 (AUD-DEP-14, R-27).

The PostgreSQL integration tests of minos-storage connect to a server started outside the JVM. Both scripts
start it the same way, and the CI runs the shell variant. What is proved here, without a real Docker:

* the image is read from its single source, the default of ``MINOS_POSTGRES_IMAGE`` in
  ``docker/compose-mcp.connected.yaml``; an unpinned default is refused before any call to Docker, with a
  message that names the variable;
* every start generates a new password, handed to ``docker`` through its environment only: it never appears in
  the arguments of ``docker``;
* the port is published on ``127.0.0.1`` only, and a container whose healthcheck is not healthy is removed;
* ``--github-env`` masks the password (``::add-mask::``) and writes the three connection variables to
  ``$GITHUB_ENV`` instead of printing the password.

Each case copies the script and a compose file into a temporary tree and puts first on ``PATH`` a ``docker``
executable that only records its arguments and the ``POSTGRES_PASSWORD`` it inherits. The shell cases need a
POSIX ``bash`` (Git Bash on Windows; never the WSL launcher); the PowerShell cases run under each PowerShell
present (Windows PowerShell 5.1 and ``pwsh`` on Windows, ``pwsh`` elsewhere). A missing runtime skips its cases.
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
SHELL_SCRIPT = ROOT / "scripts" / "dev" / "start-test-postgres.sh"
POWERSHELL_SCRIPT = ROOT / "scripts" / "dev" / "start-test-postgres.ps1"
CONTAINER = "minos-test-postgres"
# A fixture image: the gate check-image-pins.py refuses a managed-service image written outside the compose files.
PINNED_IMAGE = "fixture.invalid/pgvector-test:17@sha256:" + "ab" * 32
UNPINNED_IMAGE = "fixture.invalid/pgvector-test:17"
DECODING = {"encoding": "utf-8", "errors": "replace"}

FAKE_DOCKER = r'''
import json, os, sys
arguments = sys.argv[1:]
with open(os.environ["FAKE_DOCKER_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps({"args": arguments, "password": os.environ.get("POSTGRES_PASSWORD")}) + "\n")
if arguments[:1] == ["inspect"]:
    print(os.environ.get("FAKE_DOCKER_HEALTH", "healthy"))
elif arguments[:1] == ["run"]:
    print("0123456789ab")
elif arguments[:1] == ["port"]:
    print("5432/tcp -> 127.0.0.1:55432")
'''


def compose(image: str) -> str:
    return (
        "services:\n"
        "  postgres:\n"
        f'    image: "${{MINOS_POSTGRES_IMAGE:-{image}}}"\n'
        "    environment:\n"
        '      POSTGRES_USER: "${MINOS_POSTGRES_USER:-minos}"\n'
    )


def posix_bash() -> str | None:
    """A POSIX bash: Git Bash next to git.exe on Windows (System32\\bash.exe starts WSL), bash elsewhere."""
    if os.name != "nt":
        return shutil.which("bash")
    git = shutil.which("git")
    if not git:
        return None
    # git.exe lives in <Git>\cmd, <Git>\bin or <Git>\mingw64\bin depending on the installation options.
    for ancestor in list(Path(git).resolve().parents)[:3]:
        for candidate in (ancestor / "bin" / "bash.exe", ancestor / "usr" / "bin" / "bash.exe"):
            if candidate.is_file():
                return str(candidate)
    return None


def powershell_runtimes() -> list[tuple[str, str]]:
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


class Tree:
    """A temporary repository tree with the script under test, a compose file and a recording docker."""

    def __init__(self, script: Path, image: str) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="minos-start-pg-")
        self.root = Path(self.directory.name)
        (self.root / "scripts" / "dev").mkdir(parents=True)
        (self.root / "docker").mkdir()
        self.script = self.root / "scripts" / "dev" / script.name
        shutil.copyfile(script, self.script)
        (self.root / "docker" / "compose-mcp.connected.yaml").write_text(compose(image), encoding="utf-8")
        self.bin = self.root / "fake-bin"
        self.bin.mkdir()
        fake = self.bin / "fake_docker.py"
        fake.write_text(FAKE_DOCKER, encoding="utf-8")
        python = Path(sys.executable).as_posix()
        posix = self.bin / "docker"
        posix.write_text(f'#!/bin/sh\nexec "{python}" "{fake.as_posix()}" "$@"\n', encoding="utf-8", newline="\n")
        posix.chmod(posix.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        (self.bin / "docker.cmd").write_text(f'@"{sys.executable}" "{fake}" %*\r\n', encoding="utf-8", newline="")
        self.log = self.root / "docker.log"
        self.log.write_text("", encoding="utf-8")
        self.github_env = self.root / "github.env"

    def close(self) -> None:
        self.directory.cleanup()

    def environment(self, health: str = "healthy", github_env: bool = False) -> dict[str, str]:
        environment = {key: value for key, value in os.environ.items()
                       if key not in ("POSTGRES_PASSWORD", "GITHUB_ENV", "MINOS_TEST_POSTGRES_PASSWORD")}
        environment["PATH"] = str(self.bin) + os.pathsep + environment.get("PATH", "")
        environment["FAKE_DOCKER_LOG"] = str(self.log)
        environment["FAKE_DOCKER_HEALTH"] = health
        if github_env:
            environment["GITHUB_ENV"] = str(self.github_env)
        return environment

    def calls(self) -> list[dict]:
        return [json.loads(line) for line in self.log.read_text(encoding="utf-8").splitlines() if line.strip()]

    def reset(self) -> None:
        self.log.write_text("", encoding="utf-8")


class Contract:
    """The cases shared by both variants; a subclass says how to run the script."""

    script: Path

    def run_script(self, tree: Tree, options: list[str], **environment) -> subprocess.CompletedProcess:
        raise NotImplementedError

    def option(self, name: str) -> str:
        raise NotImplementedError

    def new_tree(self, image: str = PINNED_IMAGE) -> Tree:
        tree = Tree(self.script, image)
        self.addCleanup(tree.close)
        return tree

    def runs(self, tree: Tree) -> list[dict]:
        return [call for call in tree.calls() if call["args"][:1] == ["run"]]

    def test_print_image_reads_the_compose_default(self) -> None:
        tree = self.new_tree()
        result = self.run_script(tree, [self.option("print-image")])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), PINNED_IMAGE)
        self.assertEqual(tree.calls(), [], "printing the image must not call docker")

    def test_unpinned_default_is_refused_before_docker(self) -> None:
        tree = self.new_tree(UNPINNED_IMAGE)
        result = self.run_script(tree, [])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MINOS_POSTGRES_IMAGE", result.stderr)
        self.assertEqual(tree.calls(), [], "an unpinned image must be refused before any docker call")

    def test_two_starts_two_passwords_never_in_the_arguments(self) -> None:
        tree = self.new_tree()
        passwords = []
        for _ in range(2):
            tree.reset()
            result = self.run_script(tree, [])
            self.assertEqual(result.returncode, 0, result.stderr)
            runs = self.runs(tree)
            self.assertEqual(len(runs), 1, tree.calls())
            password = runs[0]["password"]
            self.assertTrue(password and len(password) >= 32, "the password must be generated and long")
            for call in tree.calls():
                self.assertFalse(any(password in argument for argument in call["args"]),
                                 f"the password reaches the arguments of docker: {call['args']}")
            arguments = runs[0]["args"]
            self.assertIn("127.0.0.1:55432:5432", arguments)
            self.assertFalse(any(argument.endswith(":5432") and not argument.startswith("127.0.0.1:")
                                 for argument in arguments), arguments)
            self.assertIn("POSTGRES_PASSWORD", arguments, "the password is passed by name, from the environment")
            self.assertIn(PINNED_IMAGE, arguments)
            self.assertIn("-Dminos.postgresql.test.url=jdbc:postgresql://127.0.0.1:55432/postgres", result.stdout)
            passwords.append(password)
        self.assertNotEqual(passwords[0], passwords[1])

    def test_custom_port_is_bound_to_loopback(self) -> None:
        tree = self.new_tree()
        result = self.run_script(tree, [self.option("port"), "55999"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("127.0.0.1:55999:5432", self.runs(tree)[0]["args"])

    def test_unhealthy_container_is_removed_and_fails(self) -> None:
        tree = self.new_tree()
        result = self.run_script(tree, [], health="unhealthy")
        self.assertNotEqual(result.returncode, 0)
        calls = [call["args"] for call in tree.calls()]
        run_index = next(index for index, call in enumerate(calls) if call[:1] == ["run"])
        self.assertIn(["rm", "-f", CONTAINER], calls[run_index + 1:], calls)

    def test_stop_removes_the_container(self) -> None:
        tree = self.new_tree()
        result = self.run_script(tree, [self.option("stop")])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["args"] for call in tree.calls()], [["rm", "-f", CONTAINER]])


class ShellScriptTest(Contract, unittest.TestCase):
    script = SHELL_SCRIPT

    @classmethod
    def setUpClass(cls) -> None:
        cls.bash = posix_bash()
        if cls.bash is None:
            raise unittest.SkipTest("no POSIX bash on this host")

    def option(self, name: str) -> str:
        return "--" + name

    def run_script(self, tree: Tree, options: list[str], health: str = "healthy",
                   github_env: bool = False) -> subprocess.CompletedProcess:
        return subprocess.run([self.bash, str(tree.script), *options], capture_output=True, timeout=120,
                              env=tree.environment(health, github_env), **DECODING)

    def test_github_env_masks_and_exports_without_printing_the_password(self) -> None:
        tree = self.new_tree()
        result = self.run_script(tree, ["--github-env"], github_env=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        password = self.runs(tree)[0]["password"]
        self.assertIn("--pull=never", self.runs(tree)[0]["args"], "the CI pulls before logging out of Docker Hub")
        lines = result.stdout.splitlines()
        self.assertEqual(lines[0], f"::add-mask::{password}", "the mask comes before anything else")
        self.assertNotIn(password, "\n".join(lines[1:]) + result.stderr)
        exported = tree.github_env.read_text(encoding="utf-8").splitlines()
        self.assertIn("MINOS_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55432/postgres", exported)
        self.assertIn("MINOS_TEST_POSTGRES_USER=minos", exported)
        self.assertIn(f"MINOS_TEST_POSTGRES_PASSWORD={password}", exported)

    def test_github_env_without_the_variable_fails_before_docker(self) -> None:
        tree = self.new_tree()
        result = self.run_script(tree, ["--github-env"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("GITHUB_ENV", result.stderr)
        self.assertEqual(tree.calls(), [])

    def test_unknown_option_is_a_usage_error(self) -> None:
        tree = self.new_tree()
        result = self.run_script(tree, ["--bogus"])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(tree.calls(), [])


class PowerShellScriptTest(Contract, unittest.TestCase):
    script = POWERSHELL_SCRIPT

    @classmethod
    def setUpClass(cls) -> None:
        cls.runtimes = powershell_runtimes()
        if not cls.runtimes:
            raise unittest.SkipTest("no PowerShell on this host")

    def option(self, name: str) -> str:
        return {"print-image": "-PrintImage", "port": "-Port", "stop": "-Stop"}[name]

    def run_script(self, tree: Tree, options: list[str], health: str = "healthy") -> subprocess.CompletedProcess:
        # Every PowerShell present must give the same answer: the first divergent result is returned.
        results = []
        for label, executable in self.runtimes:
            tree.reset()
            environment = tree.environment(health)
            environment.pop("NoDefaultCurrentDirectoryInExePath", None)
            result = subprocess.run([executable, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                                     "-File", str(tree.script), *options],
                                    capture_output=True, timeout=180, env=environment, **DECODING)
            results.append((label, result))
        first = results[0][1]
        for label, result in results[1:]:
            self.assertEqual((result.returncode == 0), (first.returncode == 0),
                             f"{label} and {results[0][0]} disagree: {result.stderr} / {first.stderr}")
        return results[-1][1]


if __name__ == "__main__":
    unittest.main()

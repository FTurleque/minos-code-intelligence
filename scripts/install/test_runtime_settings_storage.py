#!/usr/bin/env python3
"""Tests of the storage selection written by configure-runtime-settings.ps1 into the native MINOS home.

With PostgreSQL managed by Docker, the connection settings exist only in the Docker data folder and name a host
that resolves only on the managed Docker network: the native MINOS home must select local storage, otherwise
every native command fails at bootstrap. Each case runs the *real* script in a temporary folder, under Windows
PowerShell 5.1 (the installer's runtime) and under ``pwsh``. Windows only: the script refuses other hosts, and
elsewhere the tests declare themselves skipped with their reason.
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
SCRIPT = ROOT / "scripts" / "install" / "configure-runtime-settings.ps1"
WINDOWS = os.name == "nt"
SKIP_REASON = "the installation script targets Windows hosts: runs on Verify (windows-2022)"


def runtimes() -> list[tuple[str, str]]:
    """(label, executable) of every PowerShell the script must work under."""
    found = []
    legacy = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    if legacy.is_file():
        found.append(("Windows PowerShell 5.1", str(legacy)))
    core = shutil.which("pwsh")
    if core:
        found.append(("pwsh", core))
    return found


def run_script(executable: str, work: Path, arguments: list[str]) -> tuple[dict[str, str], dict]:
    """Runs the script on an empty data folder; returns the written properties and the installer state."""
    native_home = work / "data"
    state = work / "installer-state.json"
    completed = subprocess.run(
        [executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT),
         "-DataRoot", str(native_home), "-DockerDataRoot", str(work / "docker-data"),
         "-InstallerStatePath", str(state), *arguments],
        capture_output=True, text=True, timeout=180, check=False)
    if completed.returncode != 0:
        raise AssertionError(f"exit {completed.returncode}\n{completed.stdout}\n{completed.stderr}")
    properties = {}
    for line in (native_home / "config" / "minos.properties").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            properties[key] = value
    return properties, json.loads(state.read_text(encoding="utf-8-sig"))


@unittest.skipUnless(WINDOWS, SKIP_REASON)
class RuntimeSettingsStorageTest(unittest.TestCase):

    def for_each_runtime(self, check) -> None:
        available = runtimes()
        self.assertEqual(["Windows PowerShell 5.1", "pwsh"], [label for label, _ in available],
                         "both PowerShell runtimes are required: the installer runs under 5.1, development under pwsh")
        for label, executable in available:
            with self.subTest(runtime=label), tempfile.TemporaryDirectory(prefix="minos-runtime-settings-") as work:
                check(executable, Path(work))

    def test_postgresql_managed_by_docker_leaves_the_native_home_on_local_storage(self):
        def check(executable, work):
            properties, state = run_script(executable, work, [
                "-StorageBackend", "postgresql", "-McpBackend", "docker", "-ManagedDockerPostgres"])
            self.assertEqual("local", properties.get("minos.storage.backend"), properties)
            self.assertEqual([], [key for key in properties if key.startswith("minos.postgres.")], properties)
            self.assertEqual("postgresql", state["storageBackend"], "the installer keeps the user's choice")
            self.assertTrue(state["managedDockerPostgres"])

        self.for_each_runtime(check)

    def test_an_external_postgresql_is_still_selected_in_the_native_home(self):
        def check(executable, work):
            password = work / "password.txt"
            password.write_text("fixture-value\n", encoding="utf-8")
            properties, state = run_script(executable, work, [
                "-StorageBackend", "postgresql", "-McpBackend", "native",
                "-PostgresUrl", "jdbc:postgresql://127.0.0.1:5432/minos", "-PostgresUser", "minos",
                "-PostgresPasswordSourcePath", str(password)])
            self.assertEqual("postgresql", properties.get("minos.storage.backend"), properties)
            self.assertEqual("false", properties.get("minos.postgres.managed"), properties)
            self.assertEqual("jdbc:postgresql://127.0.0.1:5432/minos", properties.get("minos.postgres.url"))
            self.assertNotIn("fixture-value", json.dumps(properties))
            self.assertFalse(state["managedDockerPostgres"])

        self.for_each_runtime(check)


if __name__ == "__main__":
    unittest.main()

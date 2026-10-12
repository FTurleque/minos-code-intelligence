#!/usr/bin/env python3
"""Tests of the development launcher ``minos.cmd`` at the repository root (AUD-SEC-13).

The IntelliJ plugin runs its MINOS command with the opened project as working directory. ``cmd.exe`` looks
for a bare ``java`` in the current directory before the ``PATH``: a ``java.bat`` planted in a project would
run instead of Java. The launcher under test is the real file, copied into a temporary tree next to a fake
shaded JAR; each fake ``java`` only writes a marker naming itself.

``NoDefaultCurrentDirectoryInExePath`` is removed from the environment given to the launcher: a developer
machine may already define it, which hides the defect. Windows only: elsewhere the tests declare themselves
skipped with their reason.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "minos.cmd"
WINDOWS = os.name == "nt"
SKIP_REASON = "cmd.exe resolution of a bare command: runs on Verify (windows-2022)"
GUARD = "NoDefaultCurrentDirectoryInExePath"

# A console program that writes the marker named by its own location: the only way to have a real java.exe.
MARKER_PROGRAM = r"""
using System;
using System.IO;
public static class Marker {
    public static int Main(string[] arguments) {
        string directory = Environment.GetEnvironmentVariable("MINOS_TEST_MARKERS");
        File.WriteAllText(Path.Combine(directory, "java-home"), string.Join(" ", arguments));
        string code = Environment.GetEnvironmentVariable("MINOS_TEST_EXIT");
        return string.IsNullOrEmpty(code) ? 0 : int.Parse(code);
    }
}
"""


def marker_batch(name: str) -> str:
    return f'@echo off\r\necho %*> "%MINOS_TEST_MARKERS%\\{name}"\r\nexit /b %MINOS_TEST_EXIT%\r\n'


@unittest.skipUnless(WINDOWS, SKIP_REASON)
class DevLauncherTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="minos-dev-launcher-")
        self.addCleanup(directory.cleanup)
        self.work = Path(directory.name)
        self.system = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
        self.repository = self.work / "repository"
        (self.repository / "target").mkdir(parents=True)
        shutil.copyfile(LAUNCHER, self.repository / "minos.cmd")
        (self.repository / "target" / "minos-code-intelligence-9-all.jar").write_bytes(b"")
        self.project = self.work / "opened-project"
        self.project.mkdir()
        (self.project / "java.bat").write_text(marker_batch("trap"), encoding="ascii", newline="")
        self.tools = self.work / "tools"
        self.tools.mkdir()
        (self.tools / "java.bat").write_text(marker_batch("java-path"), encoding="ascii", newline="")
        self.markers = self.work / "markers"
        self.markers.mkdir()

    def environment(self, **extra: str) -> dict[str, str]:
        environment = {
            "SystemRoot": os.environ.get("SystemRoot", r"C:\Windows"),
            "ComSpec": str(self.system / "cmd.exe"),
            "PATHEXT": ".COM;.EXE;.BAT;.CMD",
            "PATH": f"{self.tools};{self.system}",
            "MINOS_TEST_MARKERS": str(self.markers),
            "MINOS_TEST_EXIT": "0",
        }
        environment.update(extra)
        self.assertNotIn(GUARD, environment, "the guard variable must come from the launcher, not from the test")
        return environment

    def launch(self, *arguments: str, **extra: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [str(self.system / "cmd.exe"), "/d", "/c", str(self.repository / "minos.cmd"), *arguments],
            cwd=self.project, env=self.environment(**extra), capture_output=True, text=True, timeout=60, check=False)

    def written(self) -> list[str]:
        return sorted(path.name for path in self.markers.iterdir())

    def java_home(self) -> Path:
        """A JDK-shaped folder whose bin\\java.exe writes the `java-home` marker."""
        home = self.work / "jdk"
        (home / "bin").mkdir(parents=True)
        source = self.work / "Marker.cs"
        source.write_text(MARKER_PROGRAM, encoding="utf-8")
        build = (f"Add-Type -TypeDefinition (Get-Content -Raw -LiteralPath '{source}') "
                 f"-OutputAssembly '{home / 'bin' / 'java.exe'}' -OutputType ConsoleApplication")
        completed = subprocess.run(
            [str(self.system / "WindowsPowerShell" / "v1.0" / "powershell.exe"), "-NoProfile", "-Command", build],
            capture_output=True, text=True, timeout=120, check=False)
        self.assertEqual(0, completed.returncode, completed.stderr)
        return home

    def test_a_java_bat_in_the_current_directory_is_not_run(self):
        completed = self.launch("index", "demo")
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(["java-path"], self.written(), "the java of the PATH runs, the planted one does not")
        arguments = (self.markers / "java-path").read_text(encoding="ascii").strip()
        self.assertTrue(arguments.startswith("-jar "), arguments)
        self.assertTrue(arguments.endswith("index demo"), arguments)

    def test_java_home_wins_over_the_current_directory_and_the_path(self):
        completed = self.launch("status", JAVA_HOME=str(self.java_home()))
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(["java-home"], self.written())
        self.assertTrue((self.markers / "java-home").read_text(encoding="utf-8").endswith("status"))

    def test_a_java_home_without_java_falls_back_to_the_path_not_to_the_current_directory(self):
        empty = self.work / "not-a-jdk"
        empty.mkdir()
        completed = self.launch("status", JAVA_HOME=str(empty))
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(["java-path"], self.written())

    def test_the_exit_code_of_java_is_returned(self):
        self.assertEqual(7, self.launch("status", MINOS_TEST_EXIT="7").returncode)
        self.assertEqual(5, self.launch("status", MINOS_TEST_EXIT="5", JAVA_HOME=str(self.java_home())).returncode)

    def test_the_guard_variable_does_not_leak_to_the_caller(self):
        caller = self.work / "caller.cmd"
        caller.write_text(
            f'@echo off\r\ncall "{self.repository / "minos.cmd"}" status\r\n'
            f'if defined {GUARD} (echo CALLER-SEES-GUARD) else (echo CALLER-CLEAN)\r\n', encoding="ascii", newline="")
        completed = subprocess.run(
            [str(self.system / "cmd.exe"), "/d", "/c", str(caller)], cwd=self.project, env=self.environment(),
            capture_output=True, text=True, timeout=60, check=False)
        self.assertIn("CALLER-CLEAN", completed.stdout, completed.stdout + completed.stderr)
        self.assertEqual(["java-path"], self.written())

    def test_a_missing_shaded_jar_is_still_reported(self):
        (self.repository / "target" / "minos-code-intelligence-9-all.jar").unlink()
        completed = self.launch("status")
        self.assertEqual(1, completed.returncode)
        self.assertIn("MINOS shaded JAR is missing", completed.stderr)
        self.assertEqual([], self.written())


if __name__ == "__main__":
    if not WINDOWS:
        print(f"SKIPPED outside Windows: {SKIP_REASON}", file=sys.stderr)
    unittest.main()

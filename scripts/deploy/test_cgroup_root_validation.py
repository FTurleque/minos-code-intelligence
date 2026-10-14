#!/usr/bin/env python3
"""Tests of the delegated cgroup root accepted by the Linux sandbox provisioning scripts.

``scripts/deploy/provision-linux-sandbox-cgroup.sh`` (and ``scripts/ci/delegate-linux-cgroup.sh``, which
delegates to it) runs ``sudo chown -R`` on ``MINOS_SANDBOX_CGROUP_ROOT`` and interpolates it into
``sudo sh -c``. The root must therefore be a canonical path under ``/sys/fs/cgroup`` whose last component
is dedicated to MINOS (``minos*``): neither ``..`` nor an existing foreign cgroup (``user.slice``) nor a
shell metacharacter may reach a privileged command.

The real scripts run with a fake ``sudo`` first on the ``PATH``: it only records its arguments, so nothing
privileged is ever executed. A refused root, ``--attach-pid`` or ``--env-file`` must exit with code 2
before any ``sudo`` call. Acceptance is
checked on the validation function extracted from the real deployment script, since provisioning itself
needs a cgroup v2 hierarchy. ``GITHUB_ACTIONS`` and ``GITHUB_ENV`` are not passed on: the CI package
check is out of scope and the runner environment file must stay untouched.
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
PROVISION = ROOT / "scripts" / "deploy" / "provision-linux-sandbox-cgroup.sh"
CI_DELEGATION = ROOT / "scripts" / "ci" / "delegate-linux-cgroup.sh"
VALIDATION_FUNCTION = "require_minos_cgroup_root"

FAKE_SUDO = '#!/bin/sh\nprintf \'%s\\n\' "$*" >> "$MINOS_TEST_SUDO_LOG"\nexit 0\n'

REFUSED_ROOTS = (
    "/sys/fs/cgroup/../../home",
    "/sys/fs/cgroup/minos.slice/../../../home/minos",
    "/sys/fs/cgroup/minos.slice/..",
    "/sys/fs/cgroup/./minos.slice",
    "/sys/fs/cgroup//minos.slice",
    "/sys/fs/cgroup/user.slice",
    "/sys/fs/cgroup/system.slice/sshd.service",
    "/sys/fs/cgroup/MINOS.slice",
    "/sys/fs/cgroup/minos'; touch pwned; '",
    "/sys/fs/cgroup/minos slice",
    "/sys/fs/cgroup",
    "/sys/fs/cgroupx/minos.slice",
    "sys/fs/cgroup/minos.slice",
)
# An empty MINOS_SANDBOX_CGROUP_ROOT means "unset" to the scripts (default root); the function refuses it.
REFUSED_BY_VALIDATION = REFUSED_ROOTS + ("",)

ACCEPTED_ROOTS = {
    "/sys/fs/cgroup/minos.slice": "/sys/fs/cgroup/minos.slice",
    "/sys/fs/cgroup/minos.slice/": "/sys/fs/cgroup/minos.slice",
    "/sys/fs/cgroup/minos-lot3": "/sys/fs/cgroup/minos-lot3",
    "/sys/fs/cgroup/minos": "/sys/fs/cgroup/minos",
    "/sys/fs/cgroup/system.slice/minos@worker.service": "/sys/fs/cgroup/system.slice/minos@worker.service",
}


def resolve_bash() -> str | None:
    """A POSIX bash: Git for Windows' one on Windows, never the WSL launcher of System32."""
    if os.name != "nt":
        return shutil.which("bash")
    git = shutil.which("git")
    if git is not None:
        candidate = Path(git).resolve().parents[1] / "usr" / "bin" / "bash.exe"
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("bash")
    if found is not None and "system32" not in found.lower():
        return found
    return None


BASH = resolve_bash()
SKIP_REASON = "no POSIX bash found (Git for Windows on a workstation, the system bash on Linux)"


def extract_function(script: Path, name: str) -> str:
    match = re.search(rf"(?ms)^{re.escape(name)}\(\) \{{\n.*?^\}}\n", script.read_text(encoding="utf-8"))
    if match is None:
        raise AssertionError(f"{script.relative_to(ROOT)}: no top-level function {name}()")
    return match.group(0)


@unittest.skipIf(BASH is None, SKIP_REASON)
class CgroupRootValidationTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="minos-cgroup-root-")
        self.addCleanup(directory.cleanup)
        self.work = Path(directory.name)
        fake_bin = self.work / "bin"
        fake_bin.mkdir()
        sudo = fake_bin / "sudo"
        sudo.write_text(FAKE_SUDO, encoding="ascii", newline="\n")
        sudo.chmod(0o755)
        self.sudo_log = self.work / "sudo.log"
        self.path = os.pathsep.join([str(fake_bin), str(Path(BASH).parent), os.environ.get("PATH", "")])

    def environment(self, **extra: str) -> dict[str, str]:
        environment = {"PATH": self.path, "MINOS_TEST_SUDO_LOG": str(self.sudo_log)}
        if os.name == "nt":
            environment["SYSTEMROOT"] = os.environ.get("SYSTEMROOT", r"C:\Windows")
        environment.update(extra)
        return environment

    def run_script(self, script: Path, root: str, *arguments: str) -> subprocess.CompletedProcess:
        self.sudo_log.unlink(missing_ok=True)
        return subprocess.run(
            [BASH, str(script), *arguments],
            env=self.environment(MINOS_SANDBOX_CGROUP_ROOT=root),
            cwd=self.work, capture_output=True, text=True, timeout=60, check=False,
        )

    def sudo_calls(self) -> list[str]:
        if not self.sudo_log.exists():
            return []
        return self.sudo_log.read_text(encoding="utf-8").splitlines()

    def assert_refused_before_sudo(self, script: Path, root: str, *arguments: str, mentions: str) -> None:
        result = self.run_script(script, root, *arguments)
        self.assertEqual(sudo_calls := self.sudo_calls(), [],
                         f"{script.name} ran sudo with root {root!r}: {sudo_calls}")
        self.assertEqual(result.returncode, 2,
                         f"{script.name} root {root!r}: stdout={result.stdout!r} stderr={result.stderr!r}")
        self.assertIn(mentions, result.stderr)
        self.assertFalse((self.work / "pwned").exists(), "a metacharacter of the root reached a shell")

    def test_scripts_refuse_a_non_minos_or_non_canonical_root_before_any_sudo(self):
        for script in (PROVISION, CI_DELEGATION):
            for root in REFUSED_ROOTS:
                with self.subTest(script=script.name, root=root):
                    self.assert_refused_before_sudo(script, root, mentions="MINOS_SANDBOX_CGROUP_ROOT")

    def test_scripts_refuse_a_non_numeric_attach_pid_before_any_sudo(self):
        for script in (PROVISION, CI_DELEGATION):
            for pid in ("abc", "12;id", "-1"):
                with self.subTest(script=script.name, pid=pid):
                    self.assert_refused_before_sudo(script, "/sys/fs/cgroup/minos.slice", "--attach-pid", pid,
                                                    mentions="--attach-pid")

    def test_scripts_refuse_an_env_file_option_without_a_path_before_any_sudo(self):
        for script in (PROVISION, CI_DELEGATION):
            with self.subTest(script=script.name):
                self.assert_refused_before_sudo(script, "/sys/fs/cgroup/minos.slice", "--env-file",
                                                mentions="--env-file")

    def run_validation(self, root: str) -> subprocess.CompletedProcess:
        program = extract_function(PROVISION, VALIDATION_FUNCTION) + f'{VALIDATION_FUNCTION} "$1"\n'
        return subprocess.run(
            [BASH, "-c", program, "validation", root],
            env=self.environment(), cwd=self.work, capture_output=True, text=True, timeout=60, check=False,
        )

    def test_validation_accepts_a_minos_root_and_prints_its_canonical_form(self):
        for root, canonical in ACCEPTED_ROOTS.items():
            with self.subTest(root=root):
                result = self.run_validation(root)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, canonical + "\n")

    def test_validation_refuses_what_the_scripts_refuse(self):
        for root in REFUSED_BY_VALIDATION:
            with self.subTest(root=root):
                result = self.run_validation(root)
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertEqual(result.stdout, "")
                self.assertIn("MINOS_SANDBOX_CGROUP_ROOT", result.stderr)

    def test_ci_script_delegates_to_the_deployment_script(self):
        text = CI_DELEGATION.read_text(encoding="utf-8")
        self.assertIn("scripts/deploy/provision-linux-sandbox-cgroup.sh", text)
        self.assertIsNone(re.search(r"(?m)^\s*sudo\b", text),
                          "the CI script must not keep its own privileged delegation")


if __name__ == "__main__":
    unittest.main()

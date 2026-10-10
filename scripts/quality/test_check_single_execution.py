#!/usr/bin/env python3
"""Self-test of check-single-execution.py: one witness per way a heavy gate can run twice or not at all."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-single-execution.py"
_SPEC = importlib.util.spec_from_file_location("check_single_execution", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

PR_CI = """name: PR Validation
on:
  pull_request:
    branches: [main, develop]
  push:
    branches: [main, develop]
  workflow_dispatch:
jobs:
  invariants:
    runs-on: ubuntu-24.04
    steps:
      - name: Product facts
        run: python scripts/docs/product-facts.py --check
  verify:
    runs-on: ${{ matrix.os }}
    steps:
      - name: Install and authorize Linux worker sandbox runtime
        run: bash scripts/ci/install-linux-sandbox-toolchain.sh
      - name: Maven clean verify (Unix)
        run: |
          set -euo pipefail
          ./mvnw -B -ntp -Dminos.postgresql.tests.required=true clean verify
      - name: Maven clean verify (Windows)
        run: .\\mvnw.cmd -B -ntp clean verify
      - name: Targeted JaCoCo gate (full)
        run: python scripts/quality/check-jacoco.py
      - name: Targeted JaCoCo gate (Windows)
        run: python scripts/quality/check-jacoco.py --skip-scope m30-postgresql-pgvector
"""

OTHER_PR_WORKFLOW = """name: Other
on:
  pull_request:
    branches: [main]
    paths:
      - 'scripts/quality/check-jacoco.py'
      - "scripts/docs/product-facts.py"
jobs:
  other:
    runs-on: ubuntu-24.04
    steps:
      # ./mvnw clean verify and python scripts/docs/product-facts.py --check are named here only in a comment
      - run: echo done
"""

MANUAL_ONLY = """name: Replay
on:
  workflow_dispatch:
jobs:
  replay:
    runs-on: ubuntu-24.04
    steps:
      - run: |
          bash scripts/ci/install-linux-sandbox-toolchain.sh
          ./mvnw -B -ntp clean verify
          python scripts/docs/product-facts.py --check
          python scripts/quality/check-jacoco.py
"""


def duplicate(command: str, event: str = "pull_request") -> str:
    return f"""name: Duplicate
on:
  {event}:
jobs:
  copy:
    runs-on: ubuntu-24.04
    steps:
      - run: {command}
"""


class CheckSingleExecutionTest(unittest.TestCase):

    def run_gate(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, content in files.items():
                path = root / ".github" / "workflows" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            failures, _, _ = gate.check(root)
        return failures

    def test_a_single_executor_with_quoted_filters_comments_and_a_manual_replay_passes(self):
        files = {"pr-ci.yml": PR_CI, "other.yml": OTHER_PR_WORKFLOW, "replay.yml": MANUAL_ONLY}
        self.assertEqual([], self.run_gate(files))

    def test_each_heavy_control_in_a_second_pull_request_workflow_is_refused(self):
        witnesses = {
            "maven-verify-unix": "./mvnw -B -ntp clean verify",
            "maven-verify-windows": ".\\mvnw.cmd -B -ntp clean verify",
            "product-facts": "python scripts/docs/product-facts.py --check",
            "jacoco-linux": "python scripts/quality/check-jacoco.py",
            "jacoco-windows": "python scripts/quality/check-jacoco.py --skip-scope m30-postgresql-pgvector",
            "sandbox-toolchain": "bash scripts/ci/install-linux-sandbox-toolchain.sh",
        }
        for name, command in witnesses.items():
            with self.subTest(control=name):
                failures = self.run_gate({"pr-ci.yml": PR_CI, "m99.yml": duplicate(command)})
                self.assertEqual(1, len(failures), failures)
                self.assertIn("m99.yml", failures[0])
                self.assertIn(name, failures[0])

    def test_a_push_triggered_copy_counts_as_a_pull_request_gate(self):
        failures = self.run_gate({"pr-ci.yml": PR_CI, "m99.yml": duplicate("./mvnw clean verify", "push")})
        self.assertEqual(1, len(failures), failures)

    def test_a_copy_started_by_hand_only_is_not_a_pull_request_gate(self):
        failures = self.run_gate({"pr-ci.yml": PR_CI,
                                  "m99.yml": duplicate("./mvnw clean verify", "workflow_dispatch")})
        self.assertEqual([], failures)

    def test_a_second_copy_inside_pr_ci_itself_is_refused(self):
        twice = PR_CI + "      - name: Again\n        run: python scripts/docs/product-facts.py --check\n"
        failures = self.run_gate({"pr-ci.yml": twice})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("product-facts", failures[0])
        self.assertIn("found 2", failures[0])

    def test_a_control_that_lost_its_only_executor_is_refused(self):
        for needle, name in (("python scripts/docs/product-facts.py --check", "product-facts"),
                             ("bash scripts/ci/install-linux-sandbox-toolchain.sh", "sandbox-toolchain"),
                             ("./mvnw -B -ntp -Dminos.postgresql.tests.required=true clean verify",
                              "maven-verify-unix")):
            with self.subTest(control=name):
                failures = self.run_gate({"pr-ci.yml": PR_CI.replace(needle, "echo removed")})
                self.assertEqual(1, len(failures), failures)
                self.assertIn(name, failures[0])
                self.assertIn("found 0", failures[0])

    def test_a_self_test_line_is_not_a_second_execution(self):
        step = "      - name: JaCoCo gate self-test\n        run: python scripts/quality/check-jacoco.py --self-test\n"
        self.assertEqual([], self.run_gate({"pr-ci.yml": PR_CI + step}))

    def test_a_real_second_execution_is_still_refused_next_to_a_self_test(self):
        step = ("      - name: JaCoCo gate self-test\n        run: python scripts/quality/check-jacoco.py --self-test\n"
                "      - name: Again\n        run: python scripts/quality/check-jacoco.py\n")
        failures = self.run_gate({"pr-ci.yml": PR_CI + step})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("jacoco-linux", failures[0])
        self.assertIn("found 2", failures[0])

    def test_a_self_test_does_not_replace_the_only_executor(self):
        only_self_test = PR_CI.replace("run: python scripts/quality/check-jacoco.py\n",
                                       "run: python scripts/quality/check-jacoco.py --self-test\n", 1)
        failures = self.run_gate({"pr-ci.yml": only_self_test})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("jacoco-linux", failures[0])
        self.assertIn("found 0", failures[0])

    def test_a_missing_or_manual_pr_ci_is_refused(self):
        self.assertEqual(1, len(self.run_gate({"other.yml": OTHER_PR_WORKFLOW})))
        manual = PR_CI.replace("  pull_request:\n    branches: [main, develop]\n  push:\n    branches: [main, develop]\n", "")
        failures = self.run_gate({"pr-ci.yml": manual})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("single executor", failures[0])

    def test_no_workflow_directory_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            failures, _, _ = gate.check(Path(directory))
        self.assertEqual(1, len(failures), failures)


if __name__ == "__main__":
    unittest.main()

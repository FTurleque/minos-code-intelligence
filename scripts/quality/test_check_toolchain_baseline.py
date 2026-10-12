#!/usr/bin/env python3
"""Self-test of check-toolchain-baseline.py: one witness per place that can drift away from the wrapper."""
from __future__ import annotations

import importlib.util
import shutil
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-toolchain-baseline.py"
_SPEC = importlib.util.spec_from_file_location("check_toolchain_baseline", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

REPOSITORY = Path(__file__).resolve().parents[2]


def wrapper(version: str) -> str:
    return ("distributionBase=GRADLE_USER_HOME\n"
            "distributionSha256Sum=00\n"
            f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip\n")


class ToolchainBaselineTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write(gate.WRAPPER, wrapper("9.8.1"))
        self.write(gate.POLICY, "### IntelliJ plugin\n\n- Java: **21**.\n- Gradle: **9.8.1**, through the wrapper.\n")
        self.write(gate.AGENTS, "| Plugin | Java **21**, Gradle **9.8.1** (`minos-intellij/gradlew`) |\n")
        self.write(f"{gate.WORKFLOWS}/release.yml",
                   "jobs:\n  build:\n    steps:\n      - uses: gradle/actions/setup-gradle@abc # v6\n"
                   "        with:\n          gradle-version: '9.8.1'\n")
        self.write(f"{gate.WORKFLOWS}/plugin.yml",
                   "jobs:\n  build:\n    steps:\n      - run: ./gradlew --no-daemon test\n")

    def write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def failures(self) -> list[str]:
        return gate.check(self.root)[0]

    def test_a_tree_in_agreement_with_its_wrapper_is_accepted(self):
        failures, version, compared = gate.check(self.root)
        self.assertEqual([], failures)
        self.assertEqual("9.8.1", version)
        self.assertEqual(3, compared, "the policy, AGENTS.md and the one workflow that installs Gradle")

    def test_a_raised_wrapper_is_refused_until_the_policy_follows(self):
        self.write(gate.WRAPPER, wrapper("9.9.0"))
        failures = self.failures()
        self.assertIn(f"{gate.POLICY} states Gradle 9.8.1, the wrapper is 9.9.0", failures)
        self.assertIn(f"{gate.AGENTS} states Gradle 9.8.1, the wrapper is 9.9.0", failures)
        self.assertIn(f"{gate.WORKFLOWS}/release.yml:6 installs Gradle 9.8.1, the wrapper is 9.9.0", failures)

    def test_a_release_workflow_left_on_an_older_gradle_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/release.yml", "        with:\n          gradle-version: \"9.6.1\"  # pinned\n")
        self.assertEqual([f"{gate.WORKFLOWS}/release.yml:2 installs Gradle 9.6.1, the wrapper is 9.8.1"], self.failures())

    def test_a_policy_that_no_longer_states_the_baseline_is_refused(self):
        self.write(gate.POLICY, "### IntelliJ plugin\n\n- Gradle through the wrapper.\n")
        self.assertEqual([f"{gate.POLICY} does not state the Gradle baseline (expected `Gradle: **X**`)"], self.failures())

    def test_an_agents_file_with_another_version_is_refused(self):
        self.write(gate.AGENTS, "| Plugin | Java **21**, Gradle **9.6.1** |\n")
        self.assertEqual([f"{gate.AGENTS} states Gradle 9.6.1, the wrapper is 9.8.1"], self.failures())

    def test_a_missing_policy_or_agents_file_is_refused(self):
        (self.root / gate.POLICY).unlink()
        (self.root / gate.AGENTS).unlink()
        self.assertEqual([f"{gate.POLICY} is missing", f"{gate.AGENTS} is missing"], self.failures())

    def test_a_missing_wrapper_is_refused(self):
        (self.root / gate.WRAPPER).unlink()
        self.assertEqual([f"{gate.WRAPPER} is missing: the Gradle baseline has no reference"], self.failures())

    def test_a_wrapper_without_a_readable_distribution_is_refused(self):
        self.write(gate.WRAPPER, "distributionBase=GRADLE_USER_HOME\n")
        self.assertEqual([f"{gate.WRAPPER} must name exactly one Gradle distribution, found 0"], self.failures())

    def test_a_workflow_that_builds_with_the_wrapper_needs_no_version(self):
        (self.root / gate.WORKFLOWS / "release.yml").unlink()
        failures, _version, compared = gate.check(self.root)
        self.assertEqual([], failures)
        self.assertEqual(2, compared)

    def test_the_repository_is_in_agreement_with_its_wrapper(self):
        failures, version, compared = gate.check(REPOSITORY)
        self.assertEqual([], failures)
        self.assertTrue(version)
        self.assertGreaterEqual(compared, 2)

    def test_a_copy_of_the_repository_with_a_raised_wrapper_is_refused(self):
        for relative in (gate.WRAPPER, gate.POLICY, gate.AGENTS):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / relative, target)
        shutil.rmtree(self.root / gate.WORKFLOWS)
        shutil.copytree(REPOSITORY / gate.WORKFLOWS, self.root / gate.WORKFLOWS)
        self.assertEqual([], self.failures())
        properties = (self.root / gate.WRAPPER).read_text(encoding="utf-8")
        self.write(gate.WRAPPER, gate.DISTRIBUTION.sub(
            lambda match: match.group(0).replace(match.group("version"), "99.0"), properties))
        self.assertTrue(any("the wrapper is 99.0" in failure for failure in self.failures()))


if __name__ == "__main__":
    unittest.main()

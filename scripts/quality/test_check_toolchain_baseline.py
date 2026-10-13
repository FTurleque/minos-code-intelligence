#!/usr/bin/env python3
"""Self-test of check-toolchain-baseline.py: one witness per place that can drift away from the Gradle wrapper
or from the Java release of the root POM."""
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
        return gate.check_gradle(self.root)[0]

    def test_a_tree_in_agreement_with_its_wrapper_is_accepted(self):
        failures, version, compared = gate.check_gradle(self.root)
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
        failures, _version, compared = gate.check_gradle(self.root)
        self.assertEqual([], failures)
        self.assertEqual(2, compared)

    def test_the_repository_is_in_agreement_with_its_wrapper(self):
        failures, version, compared = gate.check_gradle(REPOSITORY)
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


def pom(release: str, enforced: str) -> str:
    return ("<project>\n  <properties>\n"
            f"    <maven.compiler.release>{release}</maven.compiler.release>\n"
            "  </properties>\n  <build><plugins><plugin><configuration><rules>\n"
            f"    <requireJavaVersion><version>{enforced}</version></requireJavaVersion>\n"
            "    <requireMavenVersion><version>[3.9,4.0)</version></requireMavenVersion>\n"
            "  </rules></configuration></plugin></plugins></build>\n</project>\n")


def workflow(*jobs: tuple[str, str]) -> str:
    """One job per (job id, java version), each with one setup-java step."""
    return "jobs:\n" + "".join(
        f"  {job}:\n    steps:\n      - uses: actions/setup-java@abc # v5\n        with:\n"
        f"          distribution: temurin\n          java-version: '{version}'\n" for job, version in jobs)


POLICY_JAVA = ("### Core\n\n- Java: **25**, enforced as `[25,26)` by Maven Enforcer.\n"
               "- Java support: Temurin **25** receives updates until at least **2031-09**.\n\n"
               "### IntelliJ plugin\n\n- Java: **21**.\n")


class JavaBaselineTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write(gate.POM, pom("25", "[25,26)"))
        self.write(gate.POLICY, POLICY_JAVA)
        self.write(gate.AGENTS, "| Noyau, CLI, MCP, stockage, providers | Java **25**, Maven **3.9+** |\n"
                                "| Plugin `minos-intellij` | Java **21**, Gradle **9.8.1** |\n")
        self.write(gate.OPENSPEC_CONFIG, "context: |\n  - Noyau, CLI, MCP, stockage, providers : Java 25 et Maven 3.9.x\n"
                                         "  - Plugin minos-intellij : Java 21\n")
        self.write(f"{gate.WORKFLOWS}/pr-ci.yml", workflow(("verify", "25")))
        self.write(f"{gate.WORKFLOWS}/intellij-plugin.yml", workflow(("plugin", "21"), ("windows-ownership", "21")))
        self.write("docker/Dockerfile.mcp", "# syntax=docker/dockerfile:1\nFROM eclipse-temurin:25.0.4.1_1-jre@sha256:" + "a" * 64 + "\n")

    def write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def failures(self) -> list[str]:
        return gate.check_java(self.root)[0]

    def test_a_tree_in_agreement_with_its_pom_is_accepted(self):
        failures, version, compared = gate.check_java(self.root)
        self.assertEqual([], failures)
        self.assertEqual("25", version)
        self.assertEqual(9, compared, "enforcer, three workflow steps, one image, policy (version and support), AGENTS.md, openspec config")

    def test_a_workflow_left_on_the_previous_java_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/pr-ci.yml", workflow(("verify", "24")))
        self.assertEqual([f"{gate.WORKFLOWS}/pr-ci.yml:7 installs Java 24, the POM compiles for 25"], self.failures())

    def test_an_enforcer_left_on_the_previous_java_is_refused(self):
        self.write(gate.POM, pom("25", "[24,25)"))
        self.assertEqual([f"{gate.POM} enforces Java [24,25), the POM compiles for 25 (expected [25,26))"], self.failures())

    def test_a_widened_enforcer_range_is_refused(self):
        self.write(gate.POM, pom("25", "[25,27)"))
        self.assertEqual([f"{gate.POM} enforces Java [25,27), the POM compiles for 25 (expected [25,26))"], self.failures())

    def test_an_image_left_on_the_previous_java_is_refused(self):
        self.write("docker/Dockerfile.mcp", "FROM eclipse-temurin:24.0.2_12-jre@sha256:" + "b" * 64 + "\n")
        self.assertEqual(["docker/Dockerfile.mcp:1 is built on eclipse-temurin 24, the POM compiles for 25"], self.failures())

    def test_a_policy_without_an_end_of_support_date_is_refused(self):
        self.write(gate.POLICY, "### Core\n\n- Java: **25**, enforced as `[25,26)` by Maven Enforcer.\n")
        self.assertEqual([f"{gate.POLICY} does not date the end of support of the Java baseline "
                          "(expected `Java support: ... **25** ... until [at least] **YYYY-MM**`)"], self.failures())

    def test_a_support_line_about_another_java_is_refused(self):
        self.write(gate.POLICY, POLICY_JAVA.replace("Temurin **25**", "Temurin **24**"))
        self.assertEqual([f"{gate.POLICY} dates the support of Java 24, the POM compiles for 25"], self.failures())

    def test_a_policy_that_states_another_java_is_refused(self):
        self.write(gate.POLICY, POLICY_JAVA.replace("- Java: **25**, enforced as `[25,26)`", "- Java: **24**, enforced as `[24,25)`"))
        self.assertEqual([f"{gate.POLICY} states Java 24 enforced as [24,25), the POM compiles for 25"], self.failures())

    def test_an_agents_file_or_an_openspec_config_with_another_java_is_refused(self):
        self.write(gate.AGENTS, "| Noyau, CLI, MCP, stockage, providers | Java **24**, Maven **3.9+** |\n")
        self.write(gate.OPENSPEC_CONFIG, "  - Noyau, CLI, MCP, stockage, providers : Java 24 et Maven 3.9.x\n")
        self.assertEqual([f"{gate.AGENTS} states Java 24 for the product, the POM compiles for 25",
                          f"{gate.OPENSPEC_CONFIG} states Java 24 for the product, the POM compiles for 25"], self.failures())

    def test_a_file_that_no_longer_states_the_java_baseline_is_refused(self):
        self.write(gate.AGENTS, "| Plugin `minos-intellij` | Java **21** |\n")
        self.assertEqual([f"{gate.AGENTS} does not state the Java baseline of the product"], self.failures())

    def test_the_plugin_steps_in_java_21_are_accepted_by_a_closed_list(self):
        self.assertIn(("intellij-plugin.yml", "plugin"), gate.PLUGIN_JAVA_STEPS)
        self.assertEqual([], self.failures())

    def test_java_21_outside_the_closed_list_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/pr-ci.yml", workflow(("verify", "21")))
        self.assertEqual([f"{gate.WORKFLOWS}/pr-ci.yml:7 installs Java 21, the POM compiles for 25"], self.failures())

    def test_a_second_java_21_step_in_a_listed_job_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/intellij-plugin.yml",
                   workflow(("plugin", "21")) + "      - uses: actions/setup-java@abc # v5\n        with:\n          java-version: '21'\n")
        self.assertEqual([f"{gate.WORKFLOWS}/intellij-plugin.yml job `plugin` installs Java 21 in 2 steps, "
                          "the closed list of plugin steps allows 1"], self.failures())

    def test_a_plugin_job_raised_above_21_without_the_list_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/intellij-plugin.yml", workflow(("plugin", "22")))
        self.assertEqual([f"{gate.WORKFLOWS}/intellij-plugin.yml:7 installs Java 22, the POM compiles for 25"], self.failures())

    def test_an_upgrade_qualification_may_install_the_previous_baseline_once(self):
        self.assertIn(("docker-upgrade-qualification.yml", "real-docker-upgrade"), gate.PREVIOUS_JAVA_STEPS)
        both = workflow(("real-docker-upgrade", gate.PREVIOUS_JAVA)) + (
            "      - uses: actions/setup-java@abc # v5\n        with:\n          java-version: '25'\n")
        self.write(f"{gate.WORKFLOWS}/docker-upgrade-qualification.yml", both)
        self.assertEqual([], self.failures())

    def test_the_previous_baseline_twice_in_an_upgrade_qualification_is_refused(self):
        twice = workflow(("real-docker-upgrade", gate.PREVIOUS_JAVA)) + (
            f"      - uses: actions/setup-java@abc # v5\n        with:\n          java-version: '{gate.PREVIOUS_JAVA}'\n")
        self.write(f"{gate.WORKFLOWS}/docker-upgrade-qualification.yml", twice)
        self.assertEqual([f"{gate.WORKFLOWS}/docker-upgrade-qualification.yml job `real-docker-upgrade` installs Java "
                          f"{gate.PREVIOUS_JAVA} in 2 steps, the closed list of upgrade qualifications allows 1"], self.failures())

    def test_the_previous_baseline_outside_an_upgrade_qualification_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/docker-upgrade-qualification.yml", workflow(("another-job", gate.PREVIOUS_JAVA)))
        self.assertEqual([f"{gate.WORKFLOWS}/docker-upgrade-qualification.yml:7 installs Java {gate.PREVIOUS_JAVA}, "
                          "the POM compiles for 25"], self.failures())

    def test_a_pom_without_a_single_release_is_refused(self):
        self.write(gate.POM, "<project/>\n")
        self.assertEqual([f"{gate.POM} must state exactly one maven.compiler.release, found 0"], self.failures())

    def test_a_missing_pom_is_refused(self):
        (self.root / gate.POM).unlink()
        self.assertEqual([f"{gate.POM} is missing: the Java baseline has no reference"], self.failures())

    def test_the_repository_is_in_agreement_with_its_pom(self):
        failures, version, compared = gate.check_java(REPOSITORY)
        self.assertEqual([], failures)
        self.assertTrue(version)
        self.assertGreaterEqual(compared, 20, "enforcer, product and plugin workflow steps, two images, three documents")

    def test_a_copy_of_the_repository_with_a_raised_release_names_every_place_left_behind(self):
        for relative in (gate.POM, gate.POLICY, gate.AGENTS, gate.OPENSPEC_CONFIG):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / relative, target)
        for directory in (gate.WORKFLOWS, "docker"):
            shutil.rmtree(self.root / directory)
            shutil.copytree(REPOSITORY / directory, self.root / directory)
        self.assertEqual([], self.failures())
        text = (self.root / gate.POM).read_text(encoding="utf-8")
        self.write(gate.POM, gate.RELEASE.sub("<maven.compiler.release>99</maven.compiler.release>", text))
        failures = self.failures()
        for place in (gate.POM, gate.POLICY, gate.AGENTS, gate.OPENSPEC_CONFIG, "pr-ci.yml", "docker/Dockerfile.mcp:",
                      "docker/Dockerfile.mcp.release:"):
            self.assertTrue(any(place in failure for failure in failures), place)
        self.assertFalse(any("intellij-plugin.yml" in failure for failure in failures), "the plugin stays in Java 21")

    def test_the_whole_gate_reports_both_baselines(self):
        failures, versions, compared = gate.check(REPOSITORY)
        self.assertEqual([], failures)
        self.assertRegex(versions, r"^gradle=\S+, java=\d+$")
        self.assertGreaterEqual(compared, 22)


if __name__ == "__main__":
    unittest.main()

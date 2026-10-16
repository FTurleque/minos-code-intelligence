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


# A release job that builds the plugin as the pull-request CI does: setup-gradle for its cache only, then the wrapper.
WRAPPER_RELEASE = ("jobs:\n  build:\n    steps:\n      - uses: gradle/actions/setup-gradle@abc # v6\n"
                   "        with:\n          cache-provider: basic\n"
                   "      - working-directory: minos-intellij\n        run: ./gradlew --no-daemon test buildPlugin\n"
                   "      - working-directory: minos-intellij\n        shell: pwsh\n"
                   "        run: ./gradlew.bat --no-daemon verifyPlugin\n")


def installs_gradle(value: str) -> str:
    """The release job of the pull request #427: setup-gradle installs a Gradle of its own (line 6)."""
    return ("jobs:\n  build:\n    steps:\n      - uses: gradle/actions/setup-gradle@abc # v6\n"
            f"        with:\n          gradle-version: {value}\n"
            "      - working-directory: minos-intellij\n        run: gradle --no-daemon test buildPlugin\n")


def refused(line: int, stated: str) -> str:
    return (f"{gate.WORKFLOWS}/release.yml:{line} installs Gradle {stated} through `gradle-version:`: build the plugin "
            "with the wrapper (`./gradlew` or `gradlew.bat` in minos-intellij) and remove `gradle-version:`")


class ToolchainBaselineTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write(gate.WRAPPER, wrapper("9.8.1"))
        self.write(gate.POLICY, "### IntelliJ plugin\n\n- Java: **21**.\n- Gradle: **9.8.1**, through the wrapper.\n")
        self.write(gate.AGENTS, "| Plugin | Java **21**, Gradle **9.8.1** (`minos-intellij/gradlew`) |\n")
        self.write(f"{gate.WORKFLOWS}/release.yml", WRAPPER_RELEASE)
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
        self.assertEqual(2, compared, "the policy and AGENTS.md; a workflow that builds with the wrapper states nothing")

    def test_a_raised_wrapper_is_refused_until_the_policy_follows(self):
        self.write(gate.WRAPPER, wrapper("9.9.0"))
        self.assertEqual([f"{gate.POLICY} states Gradle 9.8.1, the wrapper is 9.9.0",
                          f"{gate.AGENTS} states Gradle 9.8.1, the wrapper is 9.9.0"], self.failures(),
                         "the workflows build with the wrapper: they follow it by construction")

    def test_a_workflow_that_installs_the_wrapper_version_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/release.yml", installs_gradle("'9.8.1'"))
        failures, _version, compared = gate.check_gradle(self.root)
        self.assertEqual([refused(6, "9.8.1")], failures)
        self.assertEqual(2, compared, "a refused gradle-version: is no place in agreement")

    def test_a_release_workflow_left_on_an_older_gradle_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/release.yml", "        with:\n          gradle-version: \"9.6.1\"  # pinned\n")
        self.assertEqual([refused(2, "9.6.1")], self.failures())

    def test_a_gradle_version_written_as_an_expression_or_left_empty_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/release.yml", installs_gradle("${{ env.GRADLE }}"))
        self.assertEqual([refused(6, "${{ env.GRADLE }}")], self.failures())
        self.write(f"{gate.WORKFLOWS}/release.yml", installs_gradle(""))
        self.assertEqual([refused(6, "(empty)")], self.failures())

    def test_a_workflow_with_the_wrapper_alone_is_accepted(self):
        self.write(f"{gate.WORKFLOWS}/release.yml", WRAPPER_RELEASE
                   + "      # gradle-version: is not needed, the wrapper names the distribution\n")
        self.assertEqual([], self.failures())

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

    def test_a_copy_of_the_release_workflow_that_installs_gradle_again_is_refused(self):
        shutil.rmtree(self.root / gate.WORKFLOWS)
        shutil.copytree(REPOSITORY / gate.WORKFLOWS, self.root / gate.WORKFLOWS)
        self.assertEqual([], self.failures())
        release = self.root / gate.WORKFLOWS / "intellij-plugin-release.yml"
        text = release.read_text(encoding="utf-8")
        self.assertIn("          cache-provider: basic\n", text)
        release.write_text(text.replace("          cache-provider: basic\n",
                                        "          gradle-version: '9.8.1'\n          cache-provider: basic\n", 1),
                           encoding="utf-8")
        failures = self.failures()
        self.assertEqual(1, len(failures))
        self.assertRegex(failures[0], r"^\.github/workflows/intellij-plugin-release\.yml:\d+ installs Gradle 9\.8\.1 "
                                      r"through `gradle-version:`")


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


PLUGIN_BUILD = """buildscript {
    dependencyLocking {
        lockMode = LockMode.STRICT
    }
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

// Not lockAllConfigurations(): IDE distributions differ by system.
dependencyLocking {
    lockMode = LockMode.STRICT
    ignoredDependencies.add("idea:*")
}

configurations {
    named("runtimeClasspath") { resolutionStrategy.activateDependencyLocking() }
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    intellijPlatform {
        intellijIdea("2026.1")
    }
}
"""

PLUGIN_LOCK = """# This is a Gradle generated file for dependency locking.
com.google.code.gson:gson:2.14.0=compileClasspath,runtimeClasspath
org.junit.jupiter:junit-jupiter:6.1.3=testCompileClasspath,testRuntimeClasspath
empty=
"""


VERIFICATION = """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <verify-signatures>false</verify-signatures>
      <trusted-artifacts>
         <trust group="com.jetbrains.intellij.java" reason="IDE build"/>
         <trust group="com.jetbrains.intellij.platform" name="util-jdom" reason="IDE build"/>
         <trust group="^(bundledModule|bundledPlugin|localIde|idea)$" regex="true" reason="IDE distribution"/>
      </trusted-artifacts>
   </configuration>
   <components>
      <component group="com.google.code.gson" name="gson" version="2.14.0">
         <artifact name="gson-2.14.0.jar">
            <sha256 value="%s" origin="Generated by Gradle"/>
         </artifact>
      </component>
   </components>
</verification-metadata>
""" % ("ab" * 32)


class PluginLockTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write(gate.PLUGIN_BUILD, PLUGIN_BUILD)
        self.write(gate.PLUGIN_LOCK, PLUGIN_LOCK)
        self.write(gate.PLUGIN_BUILDSCRIPT_LOCK, "org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:2.19.0=classpath\n")
        self.write(gate.PLUGIN_VERIFICATION, VERIFICATION)

    def write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def failures(self) -> list[str]:
        return gate.check_plugin_locks(self.root)[0]

    def test_a_strict_lock_that_agrees_with_the_declarations_is_accepted(self):
        self.assertEqual(([], 2), gate.check_plugin_locks(self.root))

    def test_a_missing_lock_file_is_refused(self):
        for relative in (gate.PLUGIN_LOCK, gate.PLUGIN_BUILDSCRIPT_LOCK):
            (self.root / relative).unlink()
        failures = self.failures()
        self.assertEqual(2, len(failures))
        self.assertTrue(failures[0].startswith(f"{gate.PLUGIN_LOCK} is missing"))
        self.assertTrue(failures[1].startswith(f"{gate.PLUGIN_BUILDSCRIPT_LOCK} is missing"))

    def test_a_lock_that_is_not_strict_or_not_activated_is_refused(self):
        for mutated in (PLUGIN_BUILD.replace("LockMode.STRICT", "LockMode.LENIENT", 1),
                        PLUGIN_BUILD.replace("    configurations.classpath {\n        resolutionStrategy."
                                             "activateDependencyLocking()\n    }\n", "")):
            self.write(gate.PLUGIN_BUILD, mutated)
            failures = self.failures()
            self.assertEqual(1, len(failures))
            self.assertIn("does not lock both the build script classpath and the project configurations", failures[0])

    def test_locking_every_configuration_is_refused_but_a_comment_naming_it_is_not(self):
        self.write(gate.PLUGIN_BUILD, PLUGIN_BUILD.replace("    ignoredDependencies.add(\"idea:*\")\n",
                                                           "    ignoredDependencies.add(\"idea:*\")\n    lockAllConfigurations()\n"))
        failures = self.failures()
        self.assertEqual(1, len(failures))
        self.assertIn("locks every configuration (lockAllConfigurations())", failures[0])

    def test_an_ide_distribution_in_the_lock_is_refused(self):
        self.write(gate.PLUGIN_LOCK, PLUGIN_LOCK + "idea:idea:2026.1=compileClasspath\n"
                                                  "com.jetbrains.intellij.idea:ideaIC:2026.1=intellijPlatformDependency\n")
        failures = self.failures()
        self.assertEqual(2, len(failures))
        self.assertIn("locks the IDE distribution com.jetbrains.intellij.idea:ideaIC:2026.1", failures[0])
        self.assertIn("locks the IDE distribution idea:idea:2026.1", failures[1])

    def test_a_declaration_raised_without_its_lock_is_refused(self):
        self.write(gate.PLUGIN_BUILD, PLUGIN_BUILD.replace("gson:2.14.0", "gson:2.15.0"))
        self.assertEqual([f"{gate.PLUGIN_BUILD} declares com.google.code.gson:gson:2.15.0 but {gate.PLUGIN_LOCK} locks "
                          "2.14.0: the lock would ship 2.14.0, run --write-locks"], self.failures())

    def test_a_declaration_missing_from_the_lock_is_refused(self):
        self.write(gate.PLUGIN_BUILD, PLUGIN_BUILD.replace(
            "    implementation(\"com.google.code.gson:gson:2.14.0\")\n",
            "    implementation(\"com.google.code.gson:gson:2.14.0\")\n"
            "    implementation(\"org.apache.commons:commons-lang3:3.18.0\")\n"))
        failures = self.failures()
        self.assertEqual(1, len(failures))
        self.assertIn("declares org.apache.commons:commons-lang3:3.18.0, which", failures[0])

    def test_missing_verification_metadata_is_refused(self):
        (self.root / gate.PLUGIN_VERIFICATION).unlink()
        failures = self.failures()
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith(f"{gate.PLUGIN_VERIFICATION} is missing"))

    def test_metadata_that_does_not_verify_or_records_no_sum_is_refused(self):
        self.write(gate.PLUGIN_VERIFICATION, VERIFICATION.replace("<verify-metadata>true", "<verify-metadata>false"))
        self.assertIn("does not verify metadata", self.failures()[0])
        self.write(gate.PLUGIN_VERIFICATION, VERIFICATION.replace("<sha256 value=", "<sha1 value="))
        self.assertEqual([f"{gate.PLUGIN_VERIFICATION} records no SHA-256 sum"], self.failures())

    def test_a_trust_outside_the_ide_components_is_refused(self):
        for trust in ('<trust group="com.google.code.gson"/>', '<trust group=".*" regex="true"/>',
                      '<trust group="com.jetbrains.intellij.java" version="261.1"/>'):
            self.write(gate.PLUGIN_VERIFICATION, VERIFICATION.replace(
                "</trusted-artifacts>", f"   {trust}\n      </trusted-artifacts>"))
            failures = self.failures()
            self.assertEqual(1, len(failures), trust)
            self.assertIn(f"trusts {trust}, outside the IDE components", failures[0])

    def test_the_repository_locks_its_plugin_dependencies(self):
        failures, agreeing = gate.check_plugin_locks(REPOSITORY)
        self.assertEqual([], failures)
        self.assertGreaterEqual(agreeing, 3)


if __name__ == "__main__":
    unittest.main()

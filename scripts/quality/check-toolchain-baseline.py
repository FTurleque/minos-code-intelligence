#!/usr/bin/env python3
"""Fail when a toolchain baseline (Gradle of the plugin, Java of the product) is written with two versions.

docs/TOOLCHAIN_POLICY.md says a baseline change is deliberate. The Gradle wrapper of `minos-intellij` is
what the pull-request CI builds the plugin with, and Dependabot raises it; the policy, AGENTS.md and the
release workflows (which install Gradle explicitly) each wrote the version again. Two raises of the
wrapper (9.6.1 to 9.8.0, then 9.8.1) left the policy and the release workflows on 9.6.1: a release was
built with a version the pull-request CI no longer qualified.

The wrapper is the reference. Rules:

1. `minos-intellij/gradle/wrapper/gradle-wrapper.properties` names one Gradle distribution version.
2. `docs/TOOLCHAIN_POLICY.md` states that version (`Gradle: **X**`), and so does `AGENTS.md`
   (`Gradle **X**`).
3. Every `gradle-version:` of a workflow is that version; a workflow that builds with `./gradlew` needs
   none and stays in agreement by construction.

A raise of the wrapper therefore fails this gate until the policy and the release workflows follow: the
change of baseline is made in one pull request, in the open. What this gate does not prove: that the
version is qualified on Linux and Windows (the plugin jobs do), nor that prose elsewhere repeats it.

The Java baseline of the product follows the same model. `maven.compiler.release` of the root POM is the
reference `N`. Rules:

4. Maven Enforcer requires exactly `[N,N+1)`: a wider range would let a deliverable be built on another JDK.
5. Every `java-version:` of a workflow is `N`. The steps of the IntelliJ plugin stay in Java 21 (its baseline
   follows IntelliJ Platform, not the product): they are a closed list of (workflow, job) with the number of
   Java 21 steps each may hold. Java 21 anywhere else, or any third version, is refused. One more closed list
   names the jobs that qualify an upgrade from a candidate built for the previous baseline: they install that
   JDK too, once, and it is the only other version accepted there.
6. Every `FROM eclipse-temurin:` of a Dockerfile of the repository has the major `N`.
7. `docs/TOOLCHAIN_POLICY.md` states `Java: **N**, enforced as `[N,N+1)`` and dates the end of support of
   that JDK (`Java support: ... **N** ... until [at least] **YYYY-MM**`); `AGENTS.md` and
   `openspec/config.yaml` state `N` for the product.

What these rules do not prove: that the date written in the policy is the vendor's, that scripts with their
own version guard follow (they fail by themselves on the first run), nor that Java `N` is qualified (the
`Verify` jobs, the image and the installer workflows do).

Self-test: scripts/quality/test_check_toolchain_baseline.py.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - après l'ajout du dossier du script au chemin

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
WRAPPER = "minos-intellij/gradle/wrapper/gradle-wrapper.properties"
POLICY = "docs/TOOLCHAIN_POLICY.md"
AGENTS = "AGENTS.md"
WORKFLOWS = ".github/workflows"

DISTRIBUTION = re.compile(r"^distributionUrl=.*/gradle-(?P<version>\d+(?:\.\d+)*)-(?:bin|all)\.zip\s*$", re.MULTILINE)
POLICY_VERSION = re.compile(r"Gradle: \*\*(?P<version>[^*]+)\*\*")
AGENTS_VERSION = re.compile(r"Gradle \*\*(?P<version>[^*]+)\*\*")
WORKFLOW_VERSION = re.compile(r"^\s*gradle-version:\s*['\"]?(?P<version>[^'\"\s#]+)['\"]?\s*(?:#.*)?$")

POM = "pom.xml"
OPENSPEC_CONFIG = "openspec/config.yaml"
RELEASE = re.compile(r"<maven\.compiler\.release>(?P<version>[^<]*)</maven\.compiler\.release>")
ENFORCED = re.compile(r"<requireJavaVersion>\s*<version>(?P<range>[^<]*)</version>")
JOB = re.compile(r"^  (?P<job>[A-Za-z0-9_-]+):\s*(?:#.*)?$")
JAVA_STEP = re.compile(r"^\s*java-version:\s*['\"]?(?P<version>[^'\"\s#]+)['\"]?\s*(?:#.*)?$")
TEMURIN = re.compile(r"^\s*FROM\s+(?:--platform=\S+\s+)?eclipse-temurin:(?P<major>\d+)")
POLICY_JAVA = re.compile(r"- Java: \*\*(?P<version>\d+)\*\*, enforced as `(?P<range>\[[^`]*\))`")
POLICY_SUPPORT = re.compile(r"Java support: [^\n]*?\*\*(?P<version>\d+)\*\*[^\n]*?until (?:at least )?"
                            r"\*\*(?P<date>\d{4}-\d{2}(?:-\d{2})?)\*\*")
AGENTS_JAVA = re.compile(r"providers \| Java \*\*(?P<version>\d+)\*\*")
CONFIG_JAVA = re.compile(r"providers : Java (?P<version>\d+)\b")

# The IntelliJ plugin is an external client built for IntelliJ Platform: Java 21, whatever the product compiles
# for. (workflow, job) -> number of `java-version: '21'` steps that job holds. Adding a plugin job is a line here.
PLUGIN_JAVA = "21"
PLUGIN_JAVA_STEPS = {
    ("code-audit.yml", "intellij-plugin"): 1,
    ("intellij-plugin-release.yml", "build"): 1,
    ("intellij-plugin.yml", "plugin"): 1,
    ("intellij-plugin.yml", "windows-ownership"): 1,
    ("release-windows.yml", "build"): 1,
}
# An A -> B upgrade qualification builds candidate A with the JDK of A. While a supported candidate A is still
# built for the previous baseline, these jobs install it once, next to the current one. Empty this table (and
# remove the steps) when `main` is built for the current baseline.
PREVIOUS_JAVA = "24"
PREVIOUS_JAVA_STEPS = {
    ("docker-upgrade-qualification.yml", "real-docker-upgrade"): 1,
    ("release-promotion-gate.yml", "docker-upgrade-qualification"): 1,
}
SKIPPED_DIRECTORIES = frozenset({".git", "target", "build", "node_modules", ".gradle", ".idea"})


def read(root: Path, relative: str) -> str | None:
    path = root / relative
    return path.read_text(encoding="utf-8") if path.is_file() else None


def stated_versions(text: str, pattern: re.Pattern[str]) -> list[str]:
    return [match.group("version").strip() for match in pattern.finditer(text)]


def check_gradle(root: Path) -> tuple[list[str], str, int]:
    """(failures, wrapper version, number of places compared with it)."""
    failures: list[str] = []
    wrapper = read(root, WRAPPER)
    if wrapper is None:
        return [f"{WRAPPER} is missing: the Gradle baseline has no reference"], "", 0
    distributions = stated_versions(wrapper, DISTRIBUTION)
    if len(distributions) != 1:
        return [f"{WRAPPER} must name exactly one Gradle distribution, found {len(distributions)}"], "", 0
    version = distributions[0]
    compared = 0
    for relative, pattern, form in ((POLICY, POLICY_VERSION, "Gradle: **X**"), (AGENTS, AGENTS_VERSION, "Gradle **X**")):
        text = read(root, relative)
        if text is None:
            failures.append(f"{relative} is missing")
            continue
        stated = stated_versions(text, pattern)
        if not stated:
            failures.append(f"{relative} does not state the Gradle baseline (expected `{form}`)")
        for other in stated:
            compared += 1
            if other != version:
                failures.append(f"{relative} states Gradle {other}, the wrapper is {version}")
    workflows = root / WORKFLOWS
    for workflow in sorted(workflows.glob("*.y*ml")) if workflows.is_dir() else []:
        for number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), start=1):
            match = WORKFLOW_VERSION.match(line)
            if not match:
                continue
            compared += 1
            if match.group("version") != version:
                failures.append(f"{WORKFLOWS}/{workflow.name}:{number} installs Gradle {match.group('version')}, "
                                f"the wrapper is {version}")
    return failures, version, compared


def java_steps_failures(root: Path, version: str) -> tuple[list[str], int]:
    """Rule 5: every `java-version:` is the product baseline, except the closed list of plugin steps."""
    failures: list[str] = []
    compared = 0
    workflows = root / WORKFLOWS
    for workflow in sorted(workflows.glob("*.y*ml")) if workflows.is_dir() else []:
        job = ""
        plugin_steps: dict[str, int] = {}
        previous_steps: dict[str, int] = {}
        for number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), start=1):
            declared = JOB.match(line)
            if declared:
                job = declared.group("job")
                continue
            step = JAVA_STEP.match(line)
            if not step:
                continue
            compared += 1
            stated = step.group("version")
            if stated == PLUGIN_JAVA != version and (workflow.name, job) in PLUGIN_JAVA_STEPS:
                plugin_steps[job] = plugin_steps.get(job, 0) + 1
            elif stated == PREVIOUS_JAVA != version and (workflow.name, job) in PREVIOUS_JAVA_STEPS:
                previous_steps[job] = previous_steps.get(job, 0) + 1
            elif stated != version:
                failures.append(f"{WORKFLOWS}/{workflow.name}:{number} installs Java {stated}, the POM compiles for {version}")
        for job, count in plugin_steps.items():
            allowed = PLUGIN_JAVA_STEPS[(workflow.name, job)]
            if count > allowed:
                failures.append(f"{WORKFLOWS}/{workflow.name} job `{job}` installs Java {PLUGIN_JAVA} in {count} steps, "
                                f"the closed list of plugin steps allows {allowed}")
        for job, count in previous_steps.items():
            allowed = PREVIOUS_JAVA_STEPS[(workflow.name, job)]
            if count > allowed:
                failures.append(f"{WORKFLOWS}/{workflow.name} job `{job}` installs Java {PREVIOUS_JAVA} in {count} steps, "
                                f"the closed list of upgrade qualifications allows {allowed}")
    return failures, compared


def java_images_failures(root: Path, version: str) -> tuple[list[str], int]:
    """Rule 6: every eclipse-temurin base image has the major of the product baseline."""
    failures: list[str] = []
    compared = 0
    for dockerfile in gate_cli.repository_files(root, SKIPPED_DIRECTORIES):
        if not dockerfile.name.startswith("Dockerfile"):
            continue
        relative = dockerfile.relative_to(root).as_posix()
        for number, line in enumerate(dockerfile.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            image = TEMURIN.match(line)
            if not image:
                continue
            compared += 1
            if image.group("major") != version:
                failures.append(f"{relative}:{number} is built on eclipse-temurin {image.group('major')}, "
                                f"the POM compiles for {version}")
    return failures, compared


def java_documents_failures(root: Path, version: str, expected_range: str) -> tuple[list[str], int]:
    """Rule 7: the policy states and dates the baseline; AGENTS.md and the OpenSpec configuration state it."""
    failures: list[str] = []
    compared = 0
    policy = read(root, POLICY)
    if policy is None:
        failures.append(f"{POLICY} is missing")
    else:
        stated = list(POLICY_JAVA.finditer(policy))
        if not stated:
            failures.append(f"{POLICY} does not state the Java baseline of the product")
        for match in stated:
            compared += 1
            if match.group("version") != version or match.group("range") != expected_range:
                failures.append(f"{POLICY} states Java {match.group('version')} enforced as {match.group('range')}, "
                                f"the POM compiles for {version}")
        supported = list(POLICY_SUPPORT.finditer(policy))
        if not supported:
            failures.append(f"{POLICY} does not date the end of support of the Java baseline "
                            f"(expected `Java support: ... **{version}** ... until [at least] **YYYY-MM**`)")
        for match in supported:
            compared += 1
            if match.group("version") != version:
                failures.append(f"{POLICY} dates the support of Java {match.group('version')}, the POM compiles for {version}")
    for relative, pattern in ((AGENTS, AGENTS_JAVA), (OPENSPEC_CONFIG, CONFIG_JAVA)):
        text = read(root, relative)
        if text is None:
            failures.append(f"{relative} is missing")
            continue
        stated = stated_versions(text, pattern)
        if not stated:
            failures.append(f"{relative} does not state the Java baseline of the product")
        for other in stated:
            compared += 1
            if other != version:
                failures.append(f"{relative} states Java {other} for the product, the POM compiles for {version}")
    return failures, compared


def check_java(root: Path) -> tuple[list[str], str, int]:
    """(failures, Java release of the root POM, number of places compared with it)."""
    pom = read(root, POM)
    if pom is None:
        return [f"{POM} is missing: the Java baseline has no reference"], "", 0
    releases = stated_versions(pom, RELEASE)
    if len(releases) != 1 or not releases[0].isdigit():
        return [f"{POM} must state exactly one maven.compiler.release, found {len(releases)}"], "", 0
    version = releases[0]
    expected_range = f"[{version},{int(version) + 1})"
    failures: list[str] = []
    enforced = [match.group("range").strip() for match in ENFORCED.finditer(pom)]
    if not enforced:
        failures.append(f"{POM} does not enforce a Java version (expected {expected_range})")
    compared = len(enforced)
    for stated in enforced:
        if stated != expected_range:
            failures.append(f"{POM} enforces Java {stated}, the POM compiles for {version} (expected {expected_range})")
    for part_failures, part_compared in (java_steps_failures(root, version), java_images_failures(root, version),
                                         java_documents_failures(root, version, expected_range)):
        failures.extend(part_failures)
        compared += part_compared
    return failures, version, compared


def check(root: Path) -> tuple[list[str], str, int]:
    """(failures, both baselines, number of places compared with their reference)."""
    gradle_failures, gradle, gradle_compared = check_gradle(root)
    java_failures, java, java_compared = check_java(root)
    return gradle_failures + java_failures, f"gradle={gradle}, java={java}", gradle_compared + java_compared


def main() -> int:
    return gate_cli.run(
        __doc__, DEFAULT_ROOT, check, "TOOLCHAIN BASELINE GATE",
        lambda versions, compared: f"TOOLCHAIN BASELINE GATE SUCCESS ({versions}, places in agreement with their reference={compared})")


if __name__ == "__main__":
    raise SystemExit(main())

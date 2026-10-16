#!/usr/bin/env python3
"""Fail when a toolchain baseline (Gradle of the plugin, Java of the product) is written with two versions.

docs/TOOLCHAIN_POLICY.md says a baseline change is deliberate. The Gradle wrapper of `minos-intellij` is
what the pull-request CI builds the plugin with, and Dependabot raises it; the policy and AGENTS.md write
the version again. The release workflows used to install Gradle explicitly (`gradle-version:`): two raises
of the wrapper (9.6.1 to 9.8.0, then 9.8.1) left the policy and those workflows on 9.6.1, and a release
was built with a version the pull-request CI no longer qualified.

The wrapper is the reference. Rules:

1. `minos-intellij/gradle/wrapper/gradle-wrapper.properties` names one Gradle distribution version.
2. `docs/TOOLCHAIN_POLICY.md` states that version (`Gradle: **X**`), and so does `AGENTS.md`
   (`Gradle **X**`).
3. No workflow holds a `gradle-version:`, whatever its value: the release builds the plugin with the
   wrapper (`./gradlew` or `gradlew.bat` in `minos-intellij`), like the pull-request CI, so it has no
   version of its own to drift. `gradle/actions/setup-gradle` may stay for its cache, without it.

A raise of the wrapper therefore fails this gate until the policy and AGENTS.md follow: the change of
baseline is made in one pull request, in the open. What this gate does not prove: that the version is
qualified on Linux and Windows (the plugin jobs do), that a workflow which builds the plugin calls the
wrapper rather than a `gradle` found on the runner, nor that prose elsewhere repeats the version.

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

The dependencies of the IntelliJ plugin are locked (AUD-DEP-04). Rules:

8. `minos-intellij/gradle.lockfile` (what the plugin ships, compiles against and tests with) and
   `minos-intellij/buildscript-gradle.lockfile` (the Gradle plugins of its build) exist.
9. `minos-intellij/build.gradle.kts` activates locking in strict mode for the build script and for the project, and
   never through `lockAllConfigurations()` (it would lock IDE distributions that differ by system and release).
10. `gradle.lockfile` names no IDE distribution (`idea:*`, `com.jetbrains.intellij.*`): MINOS does not ship it.
11. Every dependency declared with a version in `build.gradle.kts` (`implementation("g:a:v")`, ...) is locked at that
    version: a lock overrides a declaration silently, so a raise that forgot `--write-locks` would ship the old one.
12. `minos-intellij/gradle/verification-metadata.xml` exists, verifies metadata, records SHA-256 sums, and trusts
    nothing but the closed list TRUSTED_IDE_GROUPS: the components of an IntelliJ distribution, versioned by the IDE
    build selected at run time (option C of the change). Any other `<trust>` is refused.

What these rules do not prove: that the lock matches what Gradle resolves (the strict build of the plugin jobs fails
on a dependency outside the lock), that the sums cover what each system resolves (the plugin jobs fail on a missing
or wrong sum), nor that a locked version is free of known vulnerabilities (osv-scan reads it).

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
# Any `gradle-version:` key of a workflow (quoted key, empty value or expression included); a comment is not one.
WORKFLOW_VERSION = re.compile(r"^\s*(?:-\s+)?['\"]?gradle-version['\"]?\s*:(?P<value>.*)$")

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
    failures.extend(workflow_gradle_versions(root))
    return failures, version, compared


def workflow_gradle_versions(root: Path) -> list[str]:
    """Rule 3: a `gradle-version:` is refused whatever its value; it is no place in agreement with the wrapper."""
    failures: list[str] = []
    workflows = root / WORKFLOWS
    for workflow in sorted(workflows.glob("*.y*ml")) if workflows.is_dir() else []:
        for number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), start=1):
            match = WORKFLOW_VERSION.match(line)
            if not match:
                continue
            stated = re.split(r"\s#", match.group("value"), maxsplit=1)[0].strip().strip("'\"") or "(empty)"
            failures.append(f"{WORKFLOWS}/{workflow.name}:{number} installs Gradle {stated} through `gradle-version:`: "
                            f"build the plugin with the wrapper (`./gradlew` or `gradlew.bat` in minos-intellij) "
                            f"and remove `gradle-version:`")
    return failures


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


PLUGIN_BUILD = "minos-intellij/build.gradle.kts"
PLUGIN_LOCK = "minos-intellij/gradle.lockfile"
PLUGIN_BUILDSCRIPT_LOCK = "minos-intellij/buildscript-gradle.lockfile"
STRICT_LOCK_MODE = re.compile(r"^\s*lockMode\s*=\s*LockMode\.STRICT\s*$", re.MULTILINE)
ACTIVATED_LOCKING = re.compile(r"activateDependencyLocking\(\)")
LOCK_ALL = re.compile(r"lockAllConfigurations\(\)")
LOCK_ENTRY = re.compile(r"^(?P<module>[^#\s=]+:[^#\s=]+):(?P<version>[^#\s=]+)=", re.MULTILINE)
DECLARED_DEPENDENCY = re.compile(
    r"^\s*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly)"
    r"\(\s*\"(?P<module>[^\":\s]+:[^\":\s]+):(?P<version>[^\":\s]+)\"\s*\)", re.MULTILINE)
IDE_DISTRIBUTION = re.compile(r"^(?:idea|com\.jetbrains\.intellij\.[^:]+):")
PLUGIN_VERIFICATION = "minos-intellij/gradle/verification-metadata.xml"
VERIFY_METADATA = re.compile(r"<verify-metadata>\s*true\s*</verify-metadata>")
SHA256_ENTRY = re.compile(r"<sha256\s+value=\"[0-9a-f]{64}\"")
TRUST = re.compile(r"<trust\b(?P<attributes>[^>]*)/?>")
TRUST_ATTRIBUTE = re.compile(r"(?P<name>[\w-]+)=\"(?P<value>[^\"]*)\"")
# (group, name, regex) of every component Gradle may resolve without a sum: an IntelliJ distribution and the IDE-build
# libraries the IntelliJ Platform Gradle Plugin takes from it. Nothing that MINOS ships.
TRUSTED_IDE_GROUPS = frozenset({
    ("^(bundledModule|bundledPlugin|localIde|idea)$", None, "true"),
    ("com.jetbrains.intellij.java", None, None),
    ("com.jetbrains.intellij.platform", "util-jdom", None),
})


def verification_failures(root: Path) -> list[str]:
    """Rule 12: the sums of what the plugin build resolves are recorded, and only the IDE is trusted."""
    text = read(root, PLUGIN_VERIFICATION)
    if text is None:
        return [f"{PLUGIN_VERIFICATION} is missing: run `./gradlew --write-verification-metadata sha256` with the tasks "
                "of the plugin jobs, under Linux then Windows"]
    failures: list[str] = []
    if not VERIFY_METADATA.search(text):
        failures.append(f"{PLUGIN_VERIFICATION} does not verify metadata (<verify-metadata>true</verify-metadata>)")
    if not SHA256_ENTRY.search(text):
        failures.append(f"{PLUGIN_VERIFICATION} records no SHA-256 sum")
    for match in TRUST.finditer(text):
        attributes = {item.group("name"): item.group("value") for item in TRUST_ATTRIBUTE.finditer(match.group("attributes"))}
        key = (attributes.get("group"), attributes.get("name"), attributes.get("regex"))
        if key not in TRUSTED_IDE_GROUPS or set(attributes) - {"group", "name", "regex", "reason"}:
            failures.append(f"{PLUGIN_VERIFICATION} trusts {match.group(0).strip()}, outside the IDE components of "
                            "TRUSTED_IDE_GROUPS: record its SHA-256 instead")
    return failures


def code_of(text: str) -> str:
    """A Kotlin build script without its `//` comments, which may name what the code must not do."""
    return "\n".join(line.split("//", 1)[0] for line in text.splitlines())


def check_plugin_locks(root: Path) -> tuple[list[str], int]:
    """(failures, number of declared dependencies found at their locked version): rules 8 to 11."""
    failures: list[str] = []
    build = read(root, PLUGIN_BUILD)
    lock = read(root, PLUGIN_LOCK)
    for relative, text in ((PLUGIN_LOCK, lock), (PLUGIN_BUILDSCRIPT_LOCK, read(root, PLUGIN_BUILDSCRIPT_LOCK))):
        if text is None:
            failures.append(f"{relative} is missing: run `./gradlew dependencies buildEnvironment --write-locks` "
                            "in minos-intellij and commit the lock files")
    if build is None:
        return failures + [f"{PLUGIN_BUILD} is missing"], 0
    code = code_of(build)
    if LOCK_ALL.search(code):
        failures.append(f"{PLUGIN_BUILD} locks every configuration (lockAllConfigurations()), IDE distributions "
                        "included: lock the named configurations of the plugin only")
    if len(STRICT_LOCK_MODE.findall(code)) < 2 or len(ACTIVATED_LOCKING.findall(code)) < 2:
        failures.append(f"{PLUGIN_BUILD} does not lock both the build script classpath and the project "
                        "configurations in strict mode (lockMode = LockMode.STRICT, activateDependencyLocking())")
    failures.extend(verification_failures(root))
    if lock is None:
        return failures, 0
    locked = {match.group("module"): match.group("version") for match in LOCK_ENTRY.finditer(lock)}
    for module in sorted(locked):
        if IDE_DISTRIBUTION.match(module):
            failures.append(f"{PLUGIN_LOCK} locks the IDE distribution {module}:{locked[module]}, which MINOS does "
                            "not ship: ignore it (dependencyLocking.ignoredDependencies)")
    agreeing = 0
    for match in DECLARED_DEPENDENCY.finditer(code):
        module, version = match.group("module"), match.group("version")
        if module not in locked:
            failures.append(f"{PLUGIN_BUILD} declares {module}:{version}, which {PLUGIN_LOCK} does not lock: "
                            "run --write-locks")
        elif locked[module] != version:
            failures.append(f"{PLUGIN_BUILD} declares {module}:{version} but {PLUGIN_LOCK} locks "
                            f"{locked[module]}: the lock would ship {locked[module]}, run --write-locks")
        else:
            agreeing += 1
    return failures, agreeing


def check(root: Path) -> tuple[list[str], str, int]:
    """(failures, both baselines, number of places compared with their reference)."""
    gradle_failures, gradle, gradle_compared = check_gradle(root)
    java_failures, java, java_compared = check_java(root)
    lock_failures, locked = check_plugin_locks(root)
    return (gradle_failures + java_failures + lock_failures, f"gradle={gradle}, java={java}",
            gradle_compared + java_compared + locked)


def main() -> int:
    return gate_cli.run(
        __doc__, DEFAULT_ROOT, check, "TOOLCHAIN BASELINE GATE",
        lambda versions, compared: f"TOOLCHAIN BASELINE GATE SUCCESS ({versions}, places in agreement with their reference={compared})")


if __name__ == "__main__":
    raise SystemExit(main())

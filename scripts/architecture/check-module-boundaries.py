#!/usr/bin/env python3
"""Enforce MINOS source ownership, Maven dependency directions, and generated architecture facts.

The governed module list is checked against the root reactor (A7), and every Java package belongs to
exactly one module (A3 / ADR 0044). Self-test: scripts/architecture/test_check_module_boundaries.py.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
GENERATED_DEPENDENCY_DOC = ROOT / "docs" / "architecture" / "diagrams" / "module-dependencies.md"
MODULES = (
    "minos-domain",
    "minos-engine",
    "minos-runtime-local",
    "minos-storage-local",
    "minos-storage-postgresql",
    "minos-provider-scip",
    "minos-integration-git",
    "minos-application",
    "minos-bootstrap",
    "minos-nexus",
    "minos-cli",
    "minos-api",
    "minos-mcp",
    "minos-app",
)

# A2 / ADR 0042 — module roles.
# Adapter modules implement engine/domain ports against a concrete technology (process runtime,
# file system, PostgreSQL, SCIP, Git). The application layer only knows ports; no adapter reaches
# back into it. minos-bootstrap is the composition root: the ONLY non-adapter module that knows
# concrete adapter classes, apart from minos-app, the final distributable assembly. Surfaces open the
# application through MinosApplication.open and reach the composition root at runtime only.
ADAPTER_MODULES = frozenset({
    "minos-runtime-local",
    "minos-storage-local",
    "minos-storage-postgresql",
    "minos-provider-scip",
    "minos-integration-git",
})
APPLICATION_MODULE = "minos-application"
BOOTSTRAP_MODULE = "minos-bootstrap"
ASSEMBLY_MODULE = "minos-app"
SURFACE_MODULES = frozenset({"minos-cli", "minos-api", "minos-mcp", "minos-nexus"})
# Surfaces may see the composition root at runtime or in tests, never at compile time.
SURFACE_TO_BOOTSTRAP_SCOPES = frozenset({"runtime", "test"})
# Optional backends stay optional in production: the composition root may reach them in tests only
# (production discovers them by ServiceLoader, see DefaultMinosApplicationComposer).
BOOTSTRAP_TEST_ONLY_ADAPTERS = frozenset({"minos-storage-postgresql"})

# Explicitly allowed direct MINOS dependencies. This is a maximum set, not a requirement:
# removing a dependency is allowed, adding a dependency outside this policy is blocked.
ALLOWED_DEPENDENCIES: dict[str, frozenset[str]] = {
    "minos-domain": frozenset(),
    "minos-engine": frozenset({"minos-domain"}),
    "minos-runtime-local": frozenset({"minos-engine"}),
    "minos-storage-local": frozenset({"minos-engine"}),
    "minos-storage-postgresql": frozenset({"minos-domain", "minos-engine", "minos-storage-local"}),
    "minos-provider-scip": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local"
    }),
    "minos-integration-git": frozenset({"minos-engine"}),
    "minos-application": frozenset({"minos-domain", "minos-engine"}),
    "minos-bootstrap": frozenset({
        "minos-domain", "minos-engine", "minos-application", "minos-runtime-local", "minos-storage-local",
        "minos-provider-scip", "minos-integration-git", "minos-storage-postgresql"
    }),
    "minos-nexus": frozenset({"minos-domain", "minos-application", "minos-bootstrap"}),
    "minos-cli": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-nexus", "minos-bootstrap"}),
    "minos-api": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-bootstrap"}),
    "minos-mcp": frozenset({"minos-application", "minos-bootstrap"}),
    "minos-app": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local",
        "minos-storage-postgresql", "minos-provider-scip", "minos-integration-git",
        "minos-application", "minos-bootstrap", "minos-nexus", "minos-cli", "minos-api", "minos-mcp"
    }),
}

# A3 / ADR 0044 — one package, one module. A Java package belongs to exactly one module: no package is
# declared by the production sources of two modules, and no test is declared in a package whose production
# sources live in another module. Either would let package-private visibility cross a jar boundary.
#
# Ratchet while the A3 lot is in progress: the splits below are still tolerated. Each entry must match the
# current state exactly (same module set, same test file); an entry that no longer matches is stale and
# fails, so every commit that folds a package removes its entries. Both lists end empty, then disappear.
TOLERATED_SPLIT_PACKAGES: dict[str, frozenset[str]] = {
    "com.minos.cli": frozenset({"minos-app", "minos-cli"}),
    "com.minos.dynamic": frozenset({"minos-application", "minos-engine"}),
    "com.minos.integration.nexus": frozenset({"minos-app", "minos-nexus"}),
    "com.minos.semantic": frozenset({"minos-application", "minos-domain"}),
    "com.minos.storage": frozenset({"minos-application", "minos-engine"}),
}
TOLERATED_FOREIGN_TESTS: frozenset[str] = frozenset({
    "minos-bootstrap/src/test/java/com/minos/application/MinosApplicationTest.java",
    "minos-bootstrap/src/test/java/com/minos/application/ProgramGraphPerformanceQualificationTest.java",
    "minos-bootstrap/src/test/java/com/minos/application/ProjectIndexStateReconcilerTest.java",
    "minos-bootstrap/src/test/java/com/minos/application/ProjectInspectionSnapshotConsistencyTest.java",
    "minos-bootstrap/src/test/java/com/minos/application/ProjectResolverTest.java",
    "minos-bootstrap/src/test/java/com/minos/application/ProviderPlatformDiagnosticRedactionTest.java",
    "minos-bootstrap/src/test/java/com/minos/architecture/ArchitectureJavaFixtureMeasurementTest.java",
    "minos-bootstrap/src/test/java/com/minos/architecture/LocalProjectArchitectureQueryTest.java",
    "minos-bootstrap/src/test/java/com/minos/dynamic/RuntimeIntelligenceServiceTest.java",
    "minos-bootstrap/src/test/java/com/minos/impact/LocalProjectImpactQueryTest.java",
    "minos-bootstrap/src/test/java/com/minos/incremental/IncrementalIndexingCoordinatorTest.java",
    "minos-bootstrap/src/test/java/com/minos/incremental/IncrementalIndexingDiagnosticRedactionTest.java",
    "minos-bootstrap/src/test/java/com/minos/incremental/ProjectFingerprintSnapshotAlignmentServiceTest.java",
    "minos-bootstrap/src/test/java/com/minos/incremental/ProjectFingerprintSnapshotRealFixtureTest.java",
    "minos-bootstrap/src/test/java/com/minos/orchestration/FileAuthoritativeSnapshotRecoveryTest.java",
    "minos-bootstrap/src/test/java/com/minos/orchestration/ResumeAfterHardKillIntegrationTest.java",
    "minos-bootstrap/src/test/java/com/minos/orchestration/ResumeCrashFixtureMain.java",
    "minos-bootstrap/src/test/java/com/minos/program/analysis/FileProgramGraphProviderTest.java",
    "minos-bootstrap/src/test/java/com/minos/program/analysis/ProgramGraphAnalysisTest.java",
    "minos-bootstrap/src/test/java/com/minos/program/analysis/ProgramGraphServiceConcurrencyTest.java",
    "minos-bootstrap/src/test/java/com/minos/semantic/M23SemanticProviderConfigurationTest.java",
    "minos-bootstrap/src/test/java/com/minos/semantic/SemanticHybridIntelligenceTest.java",
    "minos-bootstrap/src/test/java/com/minos/semantic/SemanticSyncConsistencyTest.java",
    "minos-bootstrap/src/test/java/com/minos/workspace/WorkspaceIntelligenceServiceTest.java",
    "minos-app/src/test/java/com/minos/adapter/scip/M17ProviderPlatformTest.java",
    "minos-app/src/test/java/com/minos/adapter/scip/M24PolyglotProviderTest.java",
    "minos-app/src/test/java/com/minos/adapter/scip/ScipIndexerCatalogTest.java",
    "minos-app/src/test/java/com/minos/adapter/scip/ScipPersistentSnapshotExperiment.java",
    "minos-app/src/test/java/com/minos/adapter/scip/ScipRelatedTestSnapshotIntegrationTest.java",
    "minos-app/src/test/java/com/minos/adapter/scip/ScipSymbolSnapshotImporterTest.java",
    "minos-app/src/test/java/com/minos/application/M17ProviderSurfaceIntegrationTest.java",
    "minos-app/src/test/java/com/minos/application/ProviderCatalogPortTest.java",
    "minos-app/src/test/java/com/minos/application/SharedMinosApplicationIntegrationTest.java",
    "minos-app/src/test/java/com/minos/architecture/ArchitectureRealFixtureMeasurementTest.java",
    "minos-app/src/test/java/com/minos/context/CodeSearchBenchmark.java",
    "minos-app/src/test/java/com/minos/impact/ImpactAnalysisRealFixtureTest.java",
    "minos-app/src/test/java/com/minos/incremental/IncrementalIndexingRealFixtureTest.java",
    "minos-app/src/test/java/com/minos/mcp/MinosMcpServerIntegrationTest.java",
    "minos-app/src/test/java/com/minos/query/DependencyDerivationServiceTest.java",
    "minos-app/src/test/java/com/minos/query/RelatedTestDerivationServiceTest.java",
    "minos-app/src/test/java/com/minos/query/RelationshipQueryServiceTest.java",
    "minos-app/src/test/java/com/minos/query/SymbolQueryServiceTest.java",
})

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", re.MULTILINE)
ARTIFACT_TO_MODULE = {
    "minos-domain": "minos-domain",
    "minos-engine": "minos-engine",
    "minos-runtime-local": "minos-runtime-local",
    "minos-storage-local": "minos-storage-local",
    "minos-storage-postgresql": "minos-storage-postgresql",
    "minos-provider-scip": "minos-provider-scip",
    "minos-integration-git": "minos-integration-git",
    "minos-application": "minos-application",
    "minos-bootstrap": "minos-bootstrap",
    "minos-nexus": "minos-nexus",
    "minos-cli": "minos-cli",
    "minos-api": "minos-api",
    "minos-mcp": "minos-mcp",
    "minos-code-intelligence": "minos-app",
}


def fail(message: str) -> None:
    raise RuntimeError(message)


def parse_pom(module: str) -> ET.Element:
    pom = ROOT / module / "pom.xml"
    if not pom.is_file():
        fail(f"{module}: missing pom.xml")
    return ET.parse(pom).getroot()


def check_pom_layout(module: str, root: ET.Element) -> None:
    build = root.find("m:build", NS)
    if build is None:
        return

    if build.find("m:sourceDirectory", NS) is not None:
        fail(f"{module}: custom sourceDirectory is forbidden")
    if build.find("m:testSourceDirectory", NS) is not None:
        fail(f"{module}: custom testSourceDirectory is forbidden")

    for plugin in build.findall("m:plugins/m:plugin", NS):
        artifact = plugin.findtext("m:artifactId", default="", namespaces=NS)
        if artifact != "maven-compiler-plugin":
            continue
        configuration = plugin.find("m:configuration", NS)
        if configuration is None:
            continue
        if configuration.find("m:includes", NS) is not None:
            fail(f"{module}: maven-compiler-plugin <includes> is forbidden")
        if configuration.find("m:excludes", NS) is not None:
            fail(f"{module}: maven-compiler-plugin <excludes> is forbidden")


def minos_dependencies(module: str, root: ET.Element) -> dict[str, str]:
    """Direct internal dependencies of one POM, with their Maven scope (default: compile)."""
    dependencies: dict[str, str] = {}
    for dependency in root.findall("m:dependencies/m:dependency", NS):
        if dependency.findtext("m:groupId", default="", namespaces=NS) != "com.minos":
            continue
        artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
        target = ARTIFACT_TO_MODULE.get(artifact)
        if target is None:
            fail(f"{module}: unknown internal artifact dependency com.minos:{artifact}")
        if target == module:
            fail(f"{module}: self-dependency is forbidden")
        scope = (dependency.findtext("m:scope", default="", namespaces=NS) or "compile").strip()
        previous = dependencies.get(target)
        # A module declared twice (e.g. main jar and test-jar) keeps its strongest scope.
        if previous is None or SCOPE_STRENGTH[scope] > SCOPE_STRENGTH[previous]:
            dependencies[target] = scope
    return dependencies


SCOPE_STRENGTH = {"test": 0, "runtime": 1, "provided": 2, "system": 2, "compile": 3, "import": 3}


def check_no_hidden_internal_dependencies(module: str, root: ET.Element) -> None:
    """A2: an internal dependency must be a plain, reviewable edge of <dependencies>.

    Maven also resolves dependencies declared inside <profiles> (activated by OS, property or
    file) and pins versions through <dependencyManagement>; a MINOS module declared there would
    escape the policy above. Only the reactor parent may manage internal versions.
    """
    hidden = (
        ("m:profiles/m:profile/m:dependencies/m:dependency", "a <profile>"),
        ("m:profiles/m:profile/m:dependencyManagement/m:dependencies/m:dependency", "a <profile> <dependencyManagement>"),
        ("m:dependencyManagement/m:dependencies/m:dependency", "<dependencyManagement>"),
    )
    for path, location in hidden:
        for dependency in root.findall(path, NS):
            if dependency.findtext("m:groupId", default="", namespaces=NS) != "com.minos":
                continue
            artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
            fail(
                f"{module}: internal dependency com.minos:{artifact} is declared in {location}; "
                "MINOS module dependencies must be declared directly in <dependencies>"
            )


def check_hexagonal_boundaries(scoped: dict[str, dict[str, str]]) -> None:
    """A2 / ADR 0042 — explicit, scope-aware hexagonal rules (all scopes unless stated)."""
    roles = ADAPTER_MODULES | SURFACE_MODULES | {APPLICATION_MODULE, BOOTSTRAP_MODULE, ASSEMBLY_MODULE}
    unknown = roles - set(MODULES)
    if unknown:
        fail(f"hexagonal policy references unknown modules: {', '.join(sorted(unknown))}")
    violations: list[str] = []
    for module in MODULES:
        dependencies = scoped[module]
        adapters = sorted(set(dependencies) & ADAPTER_MODULES)
        if module == APPLICATION_MODULE:
            violations += [
                f"{module} -> {adapter} (the application layer must depend only on ports; "
                "wire the adapter from the composition root minos-bootstrap)" for adapter in adapters]
        elif module in SURFACE_MODULES:
            violations += [
                f"{module} -> {adapter} [{dependencies[adapter]}] (a surface must reach adapters only "
                "through application ports; the composition root minos-bootstrap wires them)"
                for adapter in adapters]
            bootstrap_scope = dependencies.get(BOOTSTRAP_MODULE)
            if bootstrap_scope is not None and bootstrap_scope not in SURFACE_TO_BOOTSTRAP_SCOPES:
                violations.append(
                    f"{module} -> {BOOTSTRAP_MODULE} [{bootstrap_scope}] (a surface may see the composition "
                    "root only in runtime or test scope, never at compile time)")
        elif module in ADAPTER_MODULES:
            for forbidden in sorted({APPLICATION_MODULE, BOOTSTRAP_MODULE, ASSEMBLY_MODULE} | SURFACE_MODULES):
                if forbidden in dependencies:
                    violations.append(
                        f"{module} -> {forbidden} (an adapter must implement engine/domain ports and never "
                        "depend on the application, the composition root or a surface)")
        elif module == BOOTSTRAP_MODULE:
            for adapter in sorted(set(dependencies) & BOOTSTRAP_TEST_ONLY_ADAPTERS):
                if dependencies[adapter] != "test":
                    violations.append(
                        f"{module} -> {adapter} [{dependencies[adapter]}] (optional backends are discovered "
                        "by ServiceLoader in production; the composition root may reach them in tests only)")
            for surface in sorted(set(dependencies) & (SURFACE_MODULES | {ASSEMBLY_MODULE})):
                violations.append(f"{module} -> {surface} (the composition root sits below the surfaces)")
        elif module != ASSEMBLY_MODULE:
            # minos-domain, minos-engine: no adapter either. minos-bootstrap (and the final assembly
            # minos-app) are the only non-adapter modules allowed to know concrete adapter classes.
            violations += [
                f"{module} -> {adapter} (only minos-bootstrap and the minos-app assembly may depend on an adapter)"
                for adapter in adapters]
    if violations:
        fail("A2 hexagonal boundary violated: " + "; ".join(violations))


def check_dependency_policy(scoped: dict[str, dict[str, str]]) -> None:
    graph = {module: frozenset(dependencies) for module, dependencies in scoped.items()}
    if set(ALLOWED_DEPENDENCIES) != set(MODULES):
        fail("dependency policy must cover every reactor module exactly once")

    check_hexagonal_boundaries(scoped)

    for module, dependencies in graph.items():
        forbidden = dependencies - ALLOWED_DEPENDENCIES[module]
        if forbidden:
            fail(f"{module}: forbidden MINOS dependencies: {', '.join(sorted(forbidden))}")

    if graph["minos-domain"]:
        fail("minos-domain must remain dependency-free inside MINOS")
    if graph["minos-engine"] - {"minos-domain"}:
        fail("minos-engine may depend only on minos-domain")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(module: str, path: tuple[str, ...]) -> None:
        if module in visiting:
            cycle = " -> ".join((*path, module))
            fail(f"internal Maven dependency cycle: {cycle}")
        if module in visited:
            return
        visiting.add(module)
        for dependency in sorted(graph[module]):
            visit(dependency, (*path, module))
        visiting.remove(module)
        visited.add(module)

    for module in MODULES:
        visit(module, tuple())


SOURCE_IMPORT = re.compile(r"^\s*import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;", re.MULTILINE)
SOURCE_QUALIFIED = re.compile(r"\bcom\.minos(?:\.[a-z_]\w*)+\.[A-Z]\w*")
SOURCE_WORD = re.compile(r"\b[A-Z]\w*\b")
COMMENTS_AND_LITERALS = re.compile(
    r"//[^\n]*|/\*.*?\*/|\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'", re.DOTALL)


def adapter_classes() -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    """Top-level production classes of every adapter module: FQN -> module, package -> name -> module."""
    by_fqn: dict[str, str] = {}
    by_package: dict[str, dict[str, str]] = {}
    for module in sorted(ADAPTER_MODULES):
        source_root = ROOT / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            relative = source.relative_to(source_root).with_suffix("")
            package = ".".join(relative.parts[:-1])
            by_fqn[".".join(relative.parts)] = module
            by_package.setdefault(package, {})[relative.parts[-1]] = module
    return by_fqn, by_package


def resolve_adapter_class(name: str, by_fqn: dict[str, str]) -> str | None:
    parts = name.split(".")
    for size in range(len(parts), 0, -1):
        candidate = ".".join(parts[:size])
        if candidate in by_fqn:
            return candidate
    return None


def check_source_boundaries() -> None:
    """A2 / ADR 0042 — the POM rules do not see sources.

    No production class outside the adapters and the composition root (minos-bootstrap) may name a
    concrete adapter class: not by import (single, static, or wildcard of a package holding adapter
    classes), not by fully qualified name, not by simple name from a package it shares with an adapter.
    minos-app keeps its POM dependencies (it assembles the shaded JAR) but, like the application and the
    surfaces, reaches adapters only through ports wired by minos-bootstrap. Comments and string literals
    are ignored.
    """
    by_fqn, by_package = adapter_classes()
    guarded = [module for module in MODULES if module not in ADAPTER_MODULES and module != BOOTSTRAP_MODULE]
    violations: list[str] = []
    for module in guarded:
        source_root = ROOT / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            text = source.read_text(encoding="utf-8")
            code = COMMENTS_AND_LITERALS.sub(" ", text)
            package_match = PACKAGE.search(text)
            package = package_match.group(1) if package_match else ""
            found: set[str] = set()
            for static, name, wildcard in SOURCE_IMPORT.findall(code):
                if wildcard and not static:
                    if name in by_package:
                        found.add(name + ".*")
                    continue
                resolved = resolve_adapter_class(name, by_fqn)
                if resolved:
                    found.add(resolved)
            body = SOURCE_IMPORT.sub(" ", code)
            for qualified in SOURCE_QUALIFIED.findall(body):
                resolved = resolve_adapter_class(qualified, by_fqn)
                if resolved:
                    found.add(resolved)
            words = set(SOURCE_WORD.findall(body))
            for simple in by_package.get(package, {}):
                if simple in words:
                    found.add(package + "." + simple)
            relative = source.relative_to(ROOT).as_posix()
            for adapter_class in sorted(found):
                owner = by_fqn.get(adapter_class) or sorted(set(by_package[adapter_class[:-2]].values()))[0]
                violations.append(f"{relative} -> {adapter_class} [{owner}]")
    if violations:
        fail("A2 source boundary violated (a non-adapter module other than minos-bootstrap names a concrete "
             "adapter class; reach it through a port wired by minos-bootstrap): " + "; ".join(violations))


def check_java_layout() -> tuple[int, dict[str, int]]:
    owners: dict[str, str] = {}
    counts: dict[str, int] = {}
    total = 0

    for module in MODULES:
        source_root = ROOT / module / "src" / "main" / "java"
        module_count = 0
        if source_root.is_dir():
            for source in sorted(source_root.rglob("*.java")):
                relative = source.relative_to(source_root).as_posix()
                previous = owners.get(relative)
                if previous is not None:
                    fail(f"duplicate production source {relative}: {previous} and {module}")
                owners[relative] = module

                text = source.read_text(encoding="utf-8")
                match = PACKAGE.search(text)
                if match is None:
                    fail(f"{module}: production Java source must declare a package: {relative}")
                expected_parent = Path(*match.group(1).split("."))
                if source.relative_to(source_root).parent != expected_parent:
                    fail(
                        f"{module}: package/path mismatch for {relative}: "
                        f"package={match.group(1)}"
                    )
                module_count += 1
                total += 1
        counts[module] = module_count

    return total, counts


def reactor_modules(root: Path = ROOT) -> list[str]:
    """Modules of the root reactor: <modules> of pom.xml, including those a <profile> would add."""
    pom = root / "pom.xml"
    if not pom.is_file():
        fail("root pom.xml is missing: the module list cannot be checked against the reactor")
    project = ET.parse(pom).getroot()
    declared = project.findall("m:modules/m:module", NS) + project.findall("m:profiles/m:profile/m:modules/m:module", NS)
    modules = [(element.text or "").strip().rstrip("/") for element in declared]
    if not modules or any(not module for module in modules):
        fail("root pom.xml declares no usable <modules>: the module list cannot be checked against the reactor")
    return modules


def check_reactor_modules(root: Path = ROOT, modules: tuple[str, ...] = MODULES) -> None:
    """A7 — the governed module list must be exactly the reactor.

    A module added to the reactor and forgotten here would escape every rule of this script, POM and
    sources alike; a module listed here but gone from the reactor would be checked against stale files.
    """
    reactor = reactor_modules(root)
    problems: list[str] = []
    duplicated = sorted({module for module in modules if modules.count(module) > 1})
    if duplicated:
        problems.append(f"MODULES lists {', '.join(duplicated)} more than once")
    missing = sorted(set(reactor) - set(modules))
    if missing:
        problems.append(
            f"reactor module(s) {', '.join(missing)} declared in the root pom.xml <modules> are absent from MODULES "
            "(an ungoverned module escapes every boundary rule)")
    extra = sorted(set(modules) - set(reactor))
    if extra:
        problems.append(f"MODULES lists {', '.join(extra)}, absent from the root pom.xml <modules>")
    if problems:
        fail("A7 module list and Maven reactor diverge: " + "; ".join(problems))


def declared_package(source: Path) -> str:
    match = PACKAGE.search(COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8")))
    return match.group(1) if match else ""


def check_package_ownership(
        root: Path = ROOT,
        modules: tuple[str, ...] = MODULES,
        tolerated_split: dict[str, frozenset[str]] = TOLERATED_SPLIT_PACKAGES,
        tolerated_tests: frozenset[str] = TOLERATED_FOREIGN_TESTS,
) -> tuple[int, int, int]:
    """A3 / ADR 0044 — one package, one module (see TOLERATED_SPLIT_PACKAGES for the ratchet).

    Returns (packages, tolerated split packages, tolerated foreign tests) for the success line.
    """
    owners: dict[str, set[str]] = {}
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        if source_root.is_dir():
            for source in sorted(source_root.rglob("*.java")):
                owners.setdefault(declared_package(source), set()).add(module)

    violations: list[str] = []
    split = {package: frozenset(owned) for package, owned in owners.items() if len(owned) > 1}
    for package, owned in sorted(split.items()):
        tolerated = tolerated_split.get(package)
        if tolerated == owned:
            continue
        violation = (f"package {package} is declared by the production sources of {len(owned)} modules: "
                     f"{', '.join(sorted(owned))}")
        if tolerated is not None:
            violation += f" (the ratchet tolerates only {', '.join(sorted(tolerated))}; update the ratchet)"
        violations.append(violation)
    for package in sorted(set(tolerated_split) - set(split)):
        violations.append(f"stale ratchet entry: package {package} is no longer split, "
                          "remove it from TOLERATED_SPLIT_PACKAGES")

    foreign: set[str] = set()
    for module in modules:
        source_root = root / module / "src" / "test" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            package = declared_package(source)
            relative = source.relative_to(root).as_posix()
            # A tolerated test is named by its path: the path must state its package, as for production.
            if source.relative_to(source_root).parent != Path(*package.split(".")):
                violations.append(f"test {relative} of {module}: package/path mismatch (package={package or '<default>'})")
                continue
            package_owners = owners.get(package, set())
            if not package_owners or module in package_owners:
                continue
            foreign.add(relative)
            if relative not in tolerated_tests:
                violations.append(
                    f"test {relative} of {module} is declared in package {package}, whose production sources "
                    f"belong to {', '.join(sorted(package_owners))}")
    for relative in sorted(set(tolerated_tests) - foreign):
        violations.append(f"stale ratchet entry: test {relative} is no longer declared in a foreign package, "
                          "remove it from TOLERATED_FOREIGN_TESTS")

    if violations:
        fail("A3 one-package-one-module violated (package-private visibility would cross a jar boundary): "
             + "; ".join(violations))
    return len(owners), len(split), len(foreign)


def mermaid_id(module: str) -> str:
    return module.replace("-", "_")


def render_dependency_document(graph: dict[str, frozenset[str]]) -> str:
    lines = [
        "# Diagramme — Dépendances Maven entre modules MINOS",
        "",
        "> **Fichier généré.** Ne pas modifier ce diagramme manuellement.",
        "> La vérité exécutable provient des POMs du reactor et de",
        "> `scripts/architecture/check-module-boundaries.py`.",
        "> Régénération : `python scripts/architecture/check-module-boundaries.py --write-doc`.",
        "",
        "```mermaid",
        "flowchart LR",
    ]
    for module in MODULES:
        lines.append(f'    {mermaid_id(module)}["{module}"]')
    for module in MODULES:
        for dependency in sorted(graph[module]):
            lines.append(f"    {mermaid_id(module)} --> {mermaid_id(dependency)}")
    lines.extend([
        "```",
        "",
        "## Dépendances MINOS directes",
        "",
        "| Module | Dépendances directes |",
        "|---|---|",
    ])
    for module in MODULES:
        dependencies = ", ".join(f"`{dependency}`" for dependency in sorted(graph[module])) or "—"
        lines.append(f"| `{module}` | {dependencies} |")
    lines.extend([
        "",
        "Le sens d'une flèche est **module → dépendance directe**. Les dépendances transitives ne sont pas répétées.",
        "Le mode normal du checker échoue si ce fichier n'est plus exactement aligné avec les POMs courants.",
        "",
    ])
    return "\n".join(lines)


def check_or_write_dependency_document(graph: dict[str, frozenset[str]], write_doc: bool) -> None:
    expected = render_dependency_document(graph)
    if write_doc:
        GENERATED_DEPENDENCY_DOC.parent.mkdir(parents=True, exist_ok=True)
        GENERATED_DEPENDENCY_DOC.write_text(expected, encoding="utf-8", newline="\n")
        print(f"M21 generated dependency documentation: {GENERATED_DEPENDENCY_DOC.relative_to(ROOT)}")
        return

    if not GENERATED_DEPENDENCY_DOC.is_file():
        fail(
            "generated module dependency documentation is missing; run: "
            "python scripts/architecture/check-module-boundaries.py --write-doc"
        )
    actual = GENERATED_DEPENDENCY_DOC.read_text(encoding="utf-8")
    if actual != expected:
        fail(
            "generated module dependency documentation is stale; run: "
            "python scripts/architecture/check-module-boundaries.py --write-doc"
        )


def main() -> int:
    try:
        arguments = sys.argv[1:]
        unknown = [argument for argument in arguments if argument != "--write-doc"]
        if unknown:
            fail(f"unknown arguments: {', '.join(unknown)}")
        write_doc = "--write-doc" in arguments

        check_reactor_modules()
        roots = {module: parse_pom(module) for module in MODULES}
        for module, root in roots.items():
            check_pom_layout(module, root)
            check_no_hidden_internal_dependencies(module, root)
        scoped = {module: minos_dependencies(module, root) for module, root in roots.items()}
        graph = {module: frozenset(dependencies) for module, dependencies in scoped.items()}
        check_dependency_policy(scoped)
        total, counts = check_java_layout()
        check_source_boundaries()
        packages, tolerated_split, tolerated_tests = check_package_ownership()
        check_or_write_dependency_document(graph, write_doc)
        for module in MODULES:
            dependencies = ",".join(sorted(graph[module])) or "-"
            print(f"M21 module-boundary {module}: sources={counts[module]} dependencies={dependencies}")
        print(
            f"A3 package ownership: packages={packages}, tolerated split packages={tolerated_split}, "
            f"tolerated foreign-package tests={tolerated_tests}"
        )
        print(
            f"M21 MODULE BOUNDARY CONSISTENCY SUCCESS "
            f"(modules={len(MODULES)}, sources={total}, dependencyPolicy=explicit-v1, hexagonalPolicy=A2-ADR-0042, "
            f"packagePolicy=A3-ADR-0044, reactor=root-pom-modules)"
        )
        return 0
    except Exception as exception:
        print(f"M21 MODULE BOUNDARY CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

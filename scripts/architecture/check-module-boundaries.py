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
    # ADR 0058: every module a class uses is declared, including the domain reached through minos-engine.
    "minos-runtime-local": frozenset({"minos-domain", "minos-engine"}),
    "minos-storage-local": frozenset({"minos-domain", "minos-engine"}),
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
    "minos-nexus": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-bootstrap"}),
    "minos-cli": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-nexus", "minos-bootstrap"}),
    "minos-api": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-bootstrap"}),
    "minos-mcp": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-bootstrap"}),
    "minos-app": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local",
        "minos-storage-postgresql", "minos-provider-scip", "minos-integration-git",
        "minos-application", "minos-bootstrap", "minos-nexus", "minos-cli", "minos-api", "minos-mcp"
    }),
}

# A3 / ADR 0044 — one package, one module. A Java package belongs to exactly one module: no package is
# declared by the production sources of two modules, and no test is declared in a package whose production
# sources live in another module. Either would let package-private visibility cross a jar boundary. No split
# is tolerated (see check_package_ownership).

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


def parse_pom(module: str, root: Path = ROOT) -> ET.Element:
    pom = root / module / "pom.xml"
    if not pom.is_file():
        fail(f"{module}: missing pom.xml")
    return ET.parse(pom).getroot()


def repository_group_id(root: Path = ROOT) -> str:
    """The groupId of the reactor, read from its root POM: the only group an internal dependency may use."""
    pom = root / "pom.xml"
    if not pom.is_file():
        fail("root pom.xml is missing: the internal groupId cannot be read")
    group = ET.parse(pom).getroot().findtext("m:groupId", default="", namespaces=NS).strip()
    if not group or "${" in group:
        fail("root pom.xml declares no literal <groupId>: the internal groupId cannot be read")
    return group


def check_pom_layout(module: str, root: ET.Element) -> None:
    """No custom source layout, at the root of the POM or hidden in a <profile> (plugins in a profile are fine)."""
    builds = [("", root.find("m:build", NS))]
    builds += [(f" in profile '{profile.findtext('m:id', default='?', namespaces=NS)}'", profile.find("m:build", NS))
               for profile in root.findall("m:profiles/m:profile", NS)]
    for where, build in builds:
        if build is None:
            continue
        for tag in ("sourceDirectory", "testSourceDirectory"):
            if build.find(f"m:{tag}", NS) is not None:
                fail(f"{module}: custom {tag}{where} is forbidden")
        plugins = build.findall("m:plugins/m:plugin", NS) + build.findall("m:pluginManagement/m:plugins/m:plugin", NS)
        for plugin in plugins:
            artifact = plugin.findtext("m:artifactId", default="", namespaces=NS)
            if artifact != "maven-compiler-plugin":
                continue
            configuration = plugin.find("m:configuration", NS)
            if configuration is None:
                continue
            for tag in ("includes", "excludes"):
                if configuration.find(f"m:{tag}", NS) is not None:
                    fail(f"{module}: maven-compiler-plugin <{tag}>{where} is forbidden")


def is_internal_dependency(module: str, dependency: ET.Element, group_id: str) -> str | None:
    """The artifactId when `dependency` designates a MINOS module, else None. Refuses a module under another group.

    A dependency is internal because of its artifactId, not because of a group name written in this script (E02): a
    module declared with an unresolved or look-alike groupId would otherwise be taken for a third-party library and
    escape every rule.
    """
    artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
    group = dependency.findtext("m:groupId", default="", namespaces=NS).strip()
    if artifact in ARTIFACT_TO_MODULE:
        if group != group_id:
            fail(f"{module}: internal artifact {artifact} is declared with groupId '{group}', expected '{group_id}' "
                 "(an unresolved or look-alike groupId hides an internal dependency)")
        return artifact
    if group == group_id:
        fail(f"{module}: unknown internal artifact dependency {group_id}:{artifact}")
    return None


def minos_dependencies(module: str, root: ET.Element, group_id: str | None = None) -> dict[str, str]:
    """Direct internal dependencies of one POM, with their Maven scope (default: compile)."""
    group_id = group_id or repository_group_id()
    dependencies: dict[str, str] = {}
    for dependency in root.findall("m:dependencies/m:dependency", NS):
        artifact = is_internal_dependency(module, dependency, group_id)
        if artifact is None:
            continue
        target = ARTIFACT_TO_MODULE[artifact]
        if target == module:
            fail(f"{module}: self-dependency is forbidden")
        scope = (dependency.findtext("m:scope", default="", namespaces=NS) or "compile").strip()
        previous = dependencies.get(target)
        # A module declared twice (e.g. main jar and test-jar) keeps its strongest scope.
        if previous is None or SCOPE_STRENGTH[scope] > SCOPE_STRENGTH[previous]:
            dependencies[target] = scope
    return dependencies


SCOPE_STRENGTH = {"test": 0, "runtime": 1, "provided": 2, "system": 2, "compile": 3, "import": 3}


def check_no_hidden_internal_dependencies(module: str, root: ET.Element, group_id: str | None = None) -> None:
    """A2: an internal dependency must be a plain, reviewable edge of <dependencies>.

    Maven also resolves dependencies declared inside <profiles> (activated by OS, property or
    file) and pins versions through <dependencyManagement>; a MINOS module declared there would
    escape the policy above. Only the reactor parent may manage internal versions.
    """
    group_id = group_id or repository_group_id()
    hidden = (
        ("m:profiles/m:profile/m:dependencies/m:dependency", "a <profile>"),
        ("m:profiles/m:profile/m:dependencyManagement/m:dependencies/m:dependency", "a <profile> <dependencyManagement>"),
        ("m:dependencyManagement/m:dependencies/m:dependency", "<dependencyManagement>"),
    )
    for path, location in hidden:
        for dependency in root.findall(path, NS):
            artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
            if artifact not in ARTIFACT_TO_MODULE and dependency.findtext("m:groupId", default="", namespaces=NS) != group_id:
                continue
            fail(
                f"{module}: internal dependency {group_id}:{artifact} is declared in {location}; "
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


def qualified_name_pattern(group_id: str) -> re.Pattern[str]:
    """A fully qualified class name under the reactor groupId (read from the root POM, never written here)."""
    return re.compile(r"\b" + re.escape(group_id) + r"(?:\.[a-z_]\w*)+\.[A-Z]\w*")


SOURCE_WORD = re.compile(r"\b[A-Z]\w*\b")
COMMENTS_AND_LITERALS = re.compile(
    r"//[^\n]*|/\*.*?\*/|\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'", re.DOTALL)


def adapter_classes(root: Path = ROOT) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    """Top-level production classes of every adapter module: FQN -> module, package -> name -> module."""
    by_fqn: dict[str, str] = {}
    by_package: dict[str, dict[str, str]] = {}
    for module in sorted(ADAPTER_MODULES):
        source_root = root / module / "src" / "main" / "java"
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


def check_source_boundaries(root: Path = ROOT, group_id: str | None = None) -> None:
    """A2 / ADR 0042 — the POM rules do not see sources.

    No production class outside the adapters and the composition root (minos-bootstrap) may name a
    concrete adapter class: not by import (single, static, or wildcard of a package holding adapter
    classes), not by fully qualified name, not by simple name from a package it shares with an adapter.
    minos-app keeps its POM dependencies (it assembles the shaded JAR) but, like the application and the
    surfaces, reaches adapters only through ports wired by minos-bootstrap. Comments and string literals
    are ignored.
    """
    by_fqn, by_package = adapter_classes(root)
    qualified_name = qualified_name_pattern(group_id or repository_group_id(root))
    guarded = [module for module in MODULES if module not in ADAPTER_MODULES and module != BOOTSTRAP_MODULE]
    violations: list[str] = []
    for module in guarded:
        source_root = root / module / "src" / "main" / "java"
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
            for qualified in qualified_name.findall(body):
                resolved = resolve_adapter_class(qualified, by_fqn)
                if resolved:
                    found.add(resolved)
            words = set(SOURCE_WORD.findall(body))
            for simple in by_package.get(package, {}):
                if simple in words:
                    found.add(package + "." + simple)
            relative = source.relative_to(root).as_posix()
            for adapter_class in sorted(found):
                owner = by_fqn.get(adapter_class) or sorted(set(by_package[adapter_class[:-2]].values()))[0]
                violations.append(f"{relative} -> {adapter_class} [{owner}]")
    if violations:
        fail("A2 source boundary violated (a non-adapter module other than minos-bootstrap names a concrete "
             "adapter class; reach it through a port wired by minos-bootstrap): " + "; ".join(violations))


def check_java_layout(root: Path = ROOT) -> tuple[int, dict[str, int]]:
    owners: dict[str, str] = {}
    counts: dict[str, int] = {}
    total = 0

    for module in MODULES:
        source_root = root / module / "src" / "main" / "java"
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


def check_package_ownership(root: Path = ROOT, modules: tuple[str, ...] = MODULES) -> int:
    """A3 / ADR 0044 — one package, one module. Returns the number of production packages."""
    owners: dict[str, set[str]] = {}
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        if source_root.is_dir():
            for source in sorted(source_root.rglob("*.java")):
                owners.setdefault(declared_package(source), set()).add(module)

    violations: list[str] = []
    for package, owned in sorted(owners.items()):
        if len(owned) > 1:
            violations.append(f"package {package} is declared by the production sources of {len(owned)} modules: "
                              f"{', '.join(sorted(owned))}")

    for module in modules:
        source_root = root / module / "src" / "test" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            package = declared_package(source)
            relative = source.relative_to(root).as_posix()
            # As for production sources, the directory of a test must state its package.
            if source.relative_to(source_root).parent != Path(*package.split(".")):
                violations.append(f"test {relative} of {module}: package/path mismatch (package={package or '<default>'})")
                continue
            package_owners = owners.get(package, set())
            if package_owners and module not in package_owners:
                violations.append(
                    f"test {relative} of {module} is declared in package {package}, whose production sources "
                    f"belong to {', '.join(sorted(package_owners))}")

    if violations:
        fail("A3 one-package-one-module violated (package-private visibility would cross a jar boundary): "
             + "; ".join(violations))
    return len(owners)


# A8 / AUD-ARC-06 — package cycles, as a ratchet. The reactor modules and the IntelliJ plugin (outside the reactor,
# no com.minos:* artifact) are read as one graph package -> package, from imports and fully qualified names of the
# production sources (comments and string literals ignored). A strongly connected component of more than one package
# is a cycle. A cycle may exist only if it is listed below; a listed cycle must still be exactly a component, so the
# table can only shrink, by a visible edit, when a cycle is broken. Lifting them is the change
# `casser-les-cycles-de-packages`. What this does not see: references through reflection, ServiceLoader, or a type
# only named through a generic parameter without import (no bytecode is read). An edge added inside a listed cycle is
# not seen either: the packages of the component do not change.
EXTERNAL_SOURCE_ROOTS = ("minos-intellij",)
KNOWN_PACKAGE_CYCLES: dict[str, tuple[frozenset[str], str]] = {
    "engine-discovery": (
        frozenset({"com.minos.discovery", "com.minos.discovery.spi"}),
        "minos-engine: the SPI detectors import ProjectDiscovery and ProjectIgnorePolicy, which use the SPI back",
    ),
    "engine-incremental-orchestration": (
        frozenset({"com.minos.incremental", "com.minos.orchestration"}),
        "minos-engine: IncrementalIndexingPlan and ProjectFingerprintService are used by the lifecycle, "
        "which the planner and the coordinator use back",
    ),
    "application-resolution-and-output": (
        frozenset({
            "com.minos.application", "com.minos.application.dynamic", "com.minos.application.semantic",
            "com.minos.architecture", "com.minos.impact", "com.minos.output", "com.minos.program.analysis",
            "com.minos.workspace",
        }),
        "minos-application: ProjectResolver is imported by six packages that MinosApplication imports back, "
        "and application.semantic imports output.DeterministicJson while output imports the query packages",
    ),
    "intellij-plugin": (
        frozenset({"com.minos.intellij.protocol", "com.minos.intellij.service", "com.minos.intellij.ui"}),
        "minos-intellij (outside the reactor): MinosCliClient reaches ui.MinosRegistryNotice",
    ),
}


def package_graph(root: Path, owners: tuple[str, ...], group_id: str) -> tuple[
        dict[str, set[str]], dict[tuple[str, str], str], set[str]]:
    """Package -> packages it uses, one witness file per edge, and every declared package."""
    classes: dict[str, str] = {}
    sources: list[tuple[str, str, str]] = []
    for owner in owners:
        source_root = root / owner / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            text = source.read_text(encoding="utf-8")
            code = COMMENTS_AND_LITERALS.sub(" ", text)
            match = PACKAGE.search(code)
            package = match.group(1) if match else ""
            classes[".".join((*source.relative_to(source_root).with_suffix("").parts,))] = package
            sources.append((source.relative_to(root).as_posix(), package, code))
    packages = set(classes.values())
    qualified_name = qualified_name_pattern(group_id)
    edges: dict[str, set[str]] = {package: set() for package in packages}
    witness: dict[tuple[str, str], str] = {}

    def package_of(name: str, wildcard: bool) -> str | None:
        if wildcard and name in packages:
            return name
        parts = name.split(".")
        for size in range(len(parts), 0, -1):
            candidate = ".".join(parts[:size])
            if candidate in classes:
                return classes[candidate]
        return None

    for relative, package, code in sources:
        used: list[str | None] = []
        for _, name, wildcard in SOURCE_IMPORT.findall(code):
            used.append(package_of(name, bool(wildcard)))
        body = SOURCE_IMPORT.sub(" ", code)
        used += [package_of(name, False) for name in qualified_name.findall(body)]
        for target in used:
            if target is not None and target != package:
                edges[package].add(target)
                witness.setdefault((package, target), relative)
    return edges, witness, packages


def strongly_connected_components(edges: dict[str, set[str]]) -> list[frozenset[str]]:
    """Components of more than one node (iterative Tarjan: no recursion limit, deterministic order)."""
    index: dict[str, int] = {}
    low: dict[str, int] = {}
    on_stack: set[str] = set()
    stack: list[str] = []
    components: list[frozenset[str]] = []
    counter = 0
    for start in sorted(edges):
        if start in index:
            continue
        work = [(start, iter(sorted(edges[start])))]
        index[start] = low[start] = counter
        counter += 1
        stack.append(start)
        on_stack.add(start)
        while work:
            node, neighbours = work[-1]
            advanced = False
            for neighbour in neighbours:
                if neighbour not in index:
                    index[neighbour] = low[neighbour] = counter
                    counter += 1
                    stack.append(neighbour)
                    on_stack.add(neighbour)
                    work.append((neighbour, iter(sorted(edges.get(neighbour, ())))))
                    advanced = True
                    break
                if neighbour in on_stack:
                    low[node] = min(low[node], index[neighbour])
            if advanced:
                continue
            work.pop()
            if work:
                parent = work[-1][0]
                low[parent] = min(low[parent], low[node])
            if low[node] == index[node]:
                members = []
                while True:
                    member = stack.pop()
                    on_stack.discard(member)
                    members.append(member)
                    if member == node:
                        break
                if len(members) > 1:
                    components.append(frozenset(members))
    return components


def check_package_cycles(root: Path = ROOT, modules: tuple[str, ...] = MODULES,
                         external: tuple[str, ...] = EXTERNAL_SOURCE_ROOTS,
                         known: dict[str, tuple[frozenset[str], str]] | None = None,
                         group_id: str | None = None) -> tuple[int, int]:
    """A8 — no package cycle outside KNOWN_PACKAGE_CYCLES, and no listed cycle that is not exactly a cycle."""
    known = KNOWN_PACKAGE_CYCLES if known is None else known
    edges, witness, packages = package_graph(root, (*modules, *external), group_id or repository_group_id(root))
    components = strongly_connected_components(edges)
    listed = {members: name for name, (members, _) in known.items()}
    problems: list[str] = []
    for component in sorted(components, key=sorted):
        if component in listed:
            continue
        inside = sorted((a, b) for a in component for b in edges[a] if b in component)
        evidence = "; ".join(f"{a} -> {b} [{witness[(a, b)]}]" for a, b in inside[:6])
        problems.append(f"package cycle not in KNOWN_PACKAGE_CYCLES: {', '.join(sorted(component))} ({evidence})")
    current = set(components)
    for name, (members, _) in sorted(known.items()):
        if frozenset(members) not in current:
            problems.append(f"KNOWN_PACKAGE_CYCLES entry '{name}' is no longer exactly a package cycle: remove or "
                            "tighten it (the ratchet only shrinks)")
    if problems:
        fail("A8 package cycles: " + "; ".join(problems))
    return len(components), len(packages)


# A9 / AUD-ARC-03 — the dependency lists of the current architecture document are checked against the POMs. The
# structure is the contract: a `### minos-…` heading, and under it a bullet labelled "Dépendances". A document that
# loses that shape fails, it does not pass by default (ADR 0043 section 3). Prose elsewhere is not checked.
ARCHITECTURE_BLOCKS_DOC = ROOT / "docs" / "architecture" / "arc42" / "05-vue-blocs.md"
MODULE_HEADING = re.compile(r"^###\s+(minos-[a-z0-9-]+)")
ANY_HEADING = re.compile(r"^#{1,3}\s")
DEPENDENCIES_BULLET = re.compile(r"^-\s+\*\*Dépendances\*\*\s*:\s*(.*)$")
MODULE_TOKEN = re.compile(r"`(minos-[a-z0-9-]+)`")


def check_architecture_documentation(graph: dict[str, frozenset[str]], document: Path = ARCHITECTURE_BLOCKS_DOC,
                                     modules: tuple[str, ...] = MODULES) -> int:
    """A9 — every module's "Dépendances" line in the arc42 block view equals its direct internal POM dependencies."""
    if not document.is_file():
        fail(f"A9 architecture document is missing: {document}")
    sections: dict[str, list[str | None]] = {}
    current: str | None = None
    for line in document.read_text(encoding="utf-8").splitlines():
        heading = MODULE_HEADING.match(line)
        if heading:
            current = heading.group(1)
            sections.setdefault(current, [])
            continue
        if ANY_HEADING.match(line):
            current = None
            continue
        bullet = DEPENDENCIES_BULLET.match(line)
        if current is not None and bullet:
            sections[current].append(bullet.group(1))
    problems: list[str] = []
    for module in modules:
        if module not in sections:
            problems.append(f"{module} has no section in {document.name}")
            continue
        lines = sections[module]
        if len(lines) != 1:
            problems.append(f"{module}: expected one 'Dépendances' line in its section, found {len(lines)}")
            continue
        expected = set(graph[module])
        text = lines[0]
        if module == ASSEMBLY_MODULE and "tous les modules" in text.casefold():
            everything = set(modules) - {module}
            if expected != everything:
                problems.append(f"{module}: documented as depending on all modules, the POM misses "
                                f"{', '.join(sorted(everything - expected))}")
            continue
        documented = set(MODULE_TOKEN.findall(text)) - {module}
        if documented != expected:
            line = ", ".join(f"`{name}`" for name in sorted(expected)) or "aucune"
            problems.append(f"{module}: documented {sorted(documented)}, POM declares {sorted(expected)}; "
                            f"expected line: - **Dépendances** : {line}")
    for module in sorted(set(sections) - set(modules)):
        problems.append(f"{document.name} has a section for {module}, which is not a governed module")
    if problems:
        fail("A9 current architecture documentation diverges from the POMs: " + "; ".join(problems))
    return len(modules)


def check_pom_rules(root: Path = ROOT) -> dict[str, dict[str, str]]:
    """Every POM rule over the governed modules; returns the scoped internal dependencies of each module."""
    group_id = repository_group_id(root)
    poms = {module: parse_pom(module, root) for module in MODULES}
    for module, pom in poms.items():
        check_pom_layout(module, pom)
        check_no_hidden_internal_dependencies(module, pom, group_id)
    scoped = {module: minos_dependencies(module, pom, group_id) for module, pom in poms.items()}
    check_dependency_policy(scoped)
    return scoped


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


def check_or_write_dependency_document(graph: dict[str, frozenset[str]], write_doc: bool,
                                       document: Path = GENERATED_DEPENDENCY_DOC) -> None:
    expected = render_dependency_document(graph)
    if write_doc:
        document.parent.mkdir(parents=True, exist_ok=True)
        document.write_text(expected, encoding="utf-8", newline="\n")
        print(f"M21 generated dependency documentation: {document.name}")
        return

    if not document.is_file():
        fail(
            "generated module dependency documentation is missing; run: "
            "python scripts/architecture/check-module-boundaries.py --write-doc"
        )
    actual = document.read_text(encoding="utf-8")
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
        scoped = check_pom_rules()
        graph = {module: frozenset(dependencies) for module, dependencies in scoped.items()}
        total, counts = check_java_layout()
        check_source_boundaries()
        packages = check_package_ownership()
        cycles, cycle_packages = check_package_cycles()
        documented = check_architecture_documentation(graph)
        check_or_write_dependency_document(graph, write_doc)
        for module in MODULES:
            dependencies = ",".join(sorted(graph[module])) or "-"
            print(f"M21 module-boundary {module}: sources={counts[module]} dependencies={dependencies}")
        print(f"A3 package ownership: packages={packages}, each owned by exactly one module")
        print(f"A8 package cycles: {cycles} known cycles over "
              f"{sum(len(members) for members, _ in KNOWN_PACKAGE_CYCLES.values())} packages, none new "
              f"({cycle_packages} packages analysed, plugin included)")
        print(f"A9 architecture document: {documented} module sections match the POM dependencies")
        print(
            f"M21 MODULE BOUNDARY CONSISTENCY SUCCESS "
            f"(modules={len(MODULES)}, sources={total}, dependencyPolicy=explicit-v1, hexagonalPolicy=A2-ADR-0042, "
            f"packagePolicy=A3-ADR-0044, cyclePolicy=A8-ratchet, documentation=A9-arc42-05, "
            f"reactor=root-pom-modules)"
        )
        return 0
    except Exception as exception:
        print(f"M21 MODULE BOUNDARY CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

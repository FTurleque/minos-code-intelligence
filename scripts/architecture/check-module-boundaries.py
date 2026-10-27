#!/usr/bin/env python3
"""Enforce MINOS source ownership, Maven dependency directions, and generated architecture facts.

The governed module list is checked against the root reactor (A7), and every Java package belongs to
exactly one module (A3 / ADR 0068). Architecture debt is held by ratchets (A8, A10 to A15, A18); the generated
dependency document also shows the execution edges created by META-INF/services files.
Self-test: scripts/architecture/test_check_module_boundaries.py.
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
    "minos-storage",
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
    "minos-storage",
    "minos-provider-scip",
    "minos-integration-git",
})
APPLICATION_MODULE = "minos-application"
BOOTSTRAP_MODULE = "minos-bootstrap"
ASSEMBLY_MODULE = "minos-app"
SURFACE_MODULES = frozenset({"minos-cli", "minos-api", "minos-mcp", "minos-nexus"})
# Surfaces may see the composition root at runtime or in tests, never at compile time.
SURFACE_TO_BOOTSTRAP_SCOPES = frozenset({"runtime", "test"})

# Explicitly allowed direct MINOS dependencies. This is a maximum set, not a requirement:
# removing a dependency is allowed, adding a dependency outside this policy is blocked.
ALLOWED_DEPENDENCIES: dict[str, frozenset[str]] = {
    "minos-domain": frozenset(),
    "minos-engine": frozenset({"minos-domain"}),
    # ADR 0058: every module a class uses is declared, including the domain reached through minos-engine.
    "minos-runtime-local": frozenset({"minos-domain", "minos-engine"}),
    # ADR 0068 section 4: one storage module; its local and PostgreSQL backends are kept apart by package rules
    # (R1, R2 and BACKEND_TYPES_NAMED_ACROSS_PACKAGES), not by Maven.
    "minos-storage": frozenset({"minos-domain", "minos-engine"}),
    "minos-provider-scip": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage"
    }),
    "minos-integration-git": frozenset({"minos-domain", "minos-engine"}),
    "minos-application": frozenset({"minos-domain", "minos-engine"}),
    "minos-bootstrap": frozenset({
        "minos-domain", "minos-engine", "minos-application", "minos-runtime-local", "minos-storage",
        "minos-provider-scip", "minos-integration-git"
    }),
    "minos-nexus": frozenset({"minos-domain", "minos-application", "minos-bootstrap"}),
    "minos-cli": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-nexus", "minos-bootstrap"}),
    "minos-api": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-bootstrap"}),
    "minos-mcp": frozenset({"minos-domain", "minos-engine", "minos-application", "minos-bootstrap"}),
    "minos-app": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage",
        "minos-provider-scip", "minos-integration-git",
        "minos-application", "minos-bootstrap", "minos-nexus", "minos-cli", "minos-api", "minos-mcp"
    }),
}

# A3 / ADR 0068 — one package, one module. A Java package belongs to exactly one module: no package is
# declared by the production sources of two modules, and no test is declared in a package whose production
# sources live in another module. Either would let package-private visibility cross a jar boundary. No split
# is tolerated (see check_package_ownership).

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", re.MULTILINE)
ARTIFACT_TO_MODULE = {
    "minos-domain": "minos-domain",
    "minos-engine": "minos-engine",
    "minos-runtime-local": "minos-runtime-local",
    "minos-storage": "minos-storage",
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


# Plugins that add another directory to the compiled sources (E02): forbidden in every <build>, profiles included.
SOURCE_ADDING_PLUGINS = frozenset({"build-helper-maven-plugin"})


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
            if artifact in SOURCE_ADDING_PLUGINS:
                fail(f"{module}: {artifact}{where} is forbidden (it adds the sources of another directory to the "
                     "build without any compiler setting)")
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


# R1 / R2 (E04, ADR 0068 section 4) — the PostgreSQL backend is optional in production: the composition root discovers
# it by ServiceLoader and the SCIP provider never needs it. Neither names a type of its package nor of its driver, so
# that sharing one storage module with the local backend gives them no compile-time path to PostgreSQL.
POSTGRESQL_BLIND_MODULES = {BOOTSTRAP_MODULE: "R1", "minos-provider-scip": "R2"}
POSTGRESQL_BACKEND_PACKAGE = "storage.postgresql"
POSTGRESQL_DRIVER_PACKAGE = "org.postgresql"


def check_postgresql_backend_isolation(root: Path = ROOT, group_id: str | None = None) -> int:
    """R1 / R2 — no production source of minos-bootstrap nor of minos-provider-scip names the PostgreSQL backend
    package or the PostgreSQL driver, by single, static or wildcard import or by qualified name; comments and string
    literals are ignored."""
    group = group_id or repository_group_id(root)
    prefixes = (f"{group}.{POSTGRESQL_BACKEND_PACKAGE}", POSTGRESQL_DRIVER_PACKAGE)
    qualified = re.compile(r"\b(?:" + "|".join(re.escape(prefix) for prefix in prefixes) + r")(?:\.\w+)+")
    violations: list[str] = []
    for module, rule in sorted(POSTGRESQL_BLIND_MODULES.items()):
        source_root = root / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            code = COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8"))
            found: set[str] = set()
            for _static, name, wildcard in SOURCE_IMPORT.findall(code):
                if any(name == prefix or name.startswith(prefix + ".") for prefix in prefixes):
                    found.add(name + (".*" if wildcard else ""))
            found.update(qualified.findall(SOURCE_IMPORT.sub(" ", code)))
            relative = source.relative_to(root).as_posix()
            violations += [f"{rule} {relative} -> {name}" for name in sorted(found)]
    if violations:
        fail("R1/R2 PostgreSQL backend isolation violated (minos-bootstrap discovers the backend by ServiceLoader and "
             "minos-provider-scip never needs it): " + "; ".join(violations))
    return len(POSTGRESQL_BLIND_MODULES)


RUNTIME_LOCAL_MODULE = "minos-runtime-local"
RUNTIME_LOCAL_PACKAGE = "runtime.local"
RUNTIME_LOCAL_GUARDED_PACKAGES = ("acl", "process", "sandbox", "workspace")
# The only types of the guarded packages another module may name. The list only grows by a visible edit:
# every other public type there (icacls launcher and platform access control, process environment, sandbox plan
# transformer, workspace deletion, cgroup and Job Object ownership) is public for the sub-packages of the module or
# for ServiceLoader, not for its callers.
RUNTIME_LOCAL_EXPORTED_TYPES = frozenset({
    "process.BoundedProcessOutput",
    "process.CommandLocator",
    "process.IndexerProcessPlan",
    "process.IndexerProcessPlanFactory",
    "process.InstallationProcessEnvironment",
    "process.ProcessIndexerExecutor",
    "sandbox.WorkerResourceContainment",
    "sandbox.WorkerSandboxBackend",
    "sandbox.WorkerSandboxBackends",
    "sandbox.WorkerSandboxQualification",
    "sandbox.WorkerSandboxSelection",
    "workspace.FileResumableRunMarkers",
})
# Members of an exported type that replace the ownership boundary of a run: reserved to the module.
RUNTIME_LOCAL_RESERVED_MEMBERS = frozenset({"ProcessPlanTransformer", "executeSandboxed"})
ANY_WORD = re.compile(r"\b[A-Za-z_]\w*\b")


def guarded_runtime_local_type(name: str, prefix: str) -> tuple[str, str] | None:
    """(guarded package, 'package.Type') named by a qualified name under the guarded packages, if any."""
    if not name.startswith(prefix + "."):
        return None
    parts = name[len(prefix) + 1:].split(".")
    if parts[0] not in RUNTIME_LOCAL_GUARDED_PACKAGES:
        return None
    if len(parts) < 2 or not parts[1][:1].isupper():
        return parts[0], parts[0]
    return parts[0], parts[0] + "." + parts[1]


def check_runtime_local_exports(root: Path = ROOT, group_id: str | None = None,
                                exported: frozenset[str] = RUNTIME_LOCAL_EXPORTED_TYPES) -> int:
    """A10 — the sandbox, process and workspace primitives of minos-runtime-local are not a public API.

    Java has no module visibility without JPMS: a type shared by two sub-packages of the module is public for
    every module that depends on it. Outside minos-runtime-local, production and test sources may name only
    the exported types of its guarded packages (single or static import, fully qualified name); a wildcard
    import of a guarded package is refused, and so is any use of a reserved member. The list is a ratchet:
    every exported type must exist and must still be named by another module. Comments and string literals
    are ignored.
    """
    group = group_id or repository_group_id(root)
    prefix = group + "." + RUNTIME_LOCAL_PACKAGE
    qualified_name = qualified_name_pattern(group)
    base = root / RUNTIME_LOCAL_MODULE / "src" / "main" / "java" / Path(*prefix.split("."))
    missing = sorted(name for name in exported if not (base / Path(*name.split("."))).with_suffix(".java").is_file())
    if missing:
        fail("A10 runtime-local export list names a type that does not exist: " + ", ".join(missing))
    violations: list[str] = []
    used: set[str] = set()
    for module in MODULES:
        if module == RUNTIME_LOCAL_MODULE:
            continue
        for kind in ("main", "test"):
            source_root = root / module / "src" / kind / "java"
            if not source_root.is_dir():
                continue
            for source in sorted(source_root.rglob("*.java")):
                code = COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8"))
                found: set[str] = set()
                uses_runtime_local = False
                for _static, name, wildcard in SOURCE_IMPORT.findall(code):
                    uses_runtime_local = uses_runtime_local or name.startswith(prefix)
                    guarded = guarded_runtime_local_type(name, prefix)
                    if guarded is None:
                        continue
                    package, type_name = guarded
                    if type_name == package:
                        found.add(f"{prefix}.{package}.*" if wildcard else f"{prefix}.{package}")
                    elif type_name not in exported:
                        found.add(f"{prefix}.{type_name}")
                    else:
                        used.add(type_name)
                body = SOURCE_IMPORT.sub(" ", code)
                for qualified in qualified_name.findall(body):
                    uses_runtime_local = uses_runtime_local or qualified.startswith(prefix)
                    guarded = guarded_runtime_local_type(qualified, prefix)
                    if guarded is None:
                        continue
                    if guarded[1] in exported:
                        used.add(guarded[1])
                    else:
                        found.add(f"{prefix}.{guarded[1]}")
                if uses_runtime_local:
                    for member in sorted(RUNTIME_LOCAL_RESERVED_MEMBERS & set(ANY_WORD.findall(body))):
                        found.add(f"reserved member {member}")
                relative = source.relative_to(root).as_posix()
                violations.extend(f"{relative} -> {name}" for name in sorted(found))
    if violations:
        fail("A10 runtime-local export boundary violated (a module names a sandbox, process or workspace type of "
             "minos-runtime-local that is not in RUNTIME_LOCAL_EXPORTED_TYPES, or a reserved member; these types "
             "are public for the sub-packages of the module only): " + "; ".join(violations))
    obsolete = sorted(exported - used)
    if obsolete:
        fail("A10 runtime-local export list is a ratchet: no other module names " + ", ".join(obsolete)
             + " any more; remove the entry")
    return len(exported)


# A11 to A14 — debt held by ratchets (AUD-ARC-01, -05, -08, -12). Each table lists, from the production sources, what
# exists today and nothing more: a name outside the table fails, and so does an entry nobody uses any more. A11 and A13
# count, per type, the production files that use it, and A12 counts, per class, its launch sites: one more use fails,
# and one less fails until the count is lowered. The tables only shrink, by a visible edit, when a structural change
# removes a use; a `var` or a chained call is not a removal. Names are written relative to the reactor groupId (read
# from the root POM). Test sources are not concerned.
ENGINE_MODULE = "minos-engine"

# A11 / AUD-ARC-01, ADR 0058 § 3 — the minos-engine types each surface uses, with the number of its production files
# that use each: by import, by fully qualified name, or by calling a MinosApplication accessor that returns the type
# (minos_application_accessors). The bytecode view of the same debt is ENGINE_TYPES_USED_BY_SURFACE_BYTECODE in
# minos-app ModuleArchitectureTest: a lot that removes a use updates both tables.
ENGINE_TYPES_NAMED_BY_SURFACE: dict[str, dict[str, int]] = {
    "minos-api": {
        # Kept: published read models, the records the application port ProjectSymbolQuery returns (ADR 0057,
        # point 6, decision D4 = a of AUD-ARC-01); LocalMinosApi converts them into the public DTOs.
        "query.RelationshipResult": 1,
        "query.SymbolResult": 1,
        "query.UsageResult": 1,
    },
    "minos-cli": {
        # Kept: published read models returned by ProjectSymbolQuery, see minos-api.
        "query.RelationshipResult": 1,
        "query.SymbolResult": 1,
        "query.UsageResult": 1,
        # Kept: a persisted I/O port implemented by the storage adapter; DockerRuntimeBootstrap, a process entry point,
        # receives it from the composition root (ADR 0068 places the ports in the engine).
        "registry.ProjectPathMappings": 1,
        # Kept: ADR 0068 section 3 places com.minos.runtime, MinosVersion included, in the engine (decision Q1 = A of
        # the transverse core lot, 2026-10-25).
        "runtime.MinosVersion": 1,
    },
    "minos-mcp": {
        # Kept: see minos-cli, ADR 0068 section 3.
        "runtime.MinosVersion": 1,
    },
    # The NEXUS export goes through KnowledgeExportUseCase (minos-application); minos-nexus does not depend on the
    # engine (AUD-ARC-01, lot 5).
    "minos-nexus": {},
}

# A12 / AUD-ARC-05, ADR 0068 — classes of the inner layers that launch a process (module/name.Type), with their number
# of launch sites. Empty: the Windows ACL mechanism of private storage is the PlatformAccessControl port, provided by
# minos-runtime-local (com.minos.runtime.local.acl).
PROCESS_LAUNCHER_MODULES = ("minos-domain", ENGINE_MODULE, APPLICATION_MODULE)
PROCESS_LAUNCHERS: dict[str, int] = {}
PROCESS_LAUNCH = re.compile(r"\bProcessBuilder\b|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\b")
PROCESS_LAUNCH_SITE = re.compile(r"\bnew\s+ProcessBuilder\b|\bProcessBuilder\s*::\s*new\b"
                                 r"|\bProcessBuilder\s*\.\s*startPipeline\b"
                                 r"|\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\b")

# A13 / AUD-ARC-08, ADR 0068 — the types of one adapter another adapter names, per (naming, named) couple, with the
# number of files of the naming adapter that name each.
ADAPTER_TYPES_NAMED_BY_ADAPTER: dict[tuple[str, str], dict[str, int]] = {
    ("minos-provider-scip", "minos-runtime-local"): {
        "runtime.local.StrongProcessOwnershipIndexerExecutor": 1,
        "runtime.local.process.BoundedProcessOutput": 3,
        "runtime.local.process.CommandLocator": 10,
        "runtime.local.process.IndexerProcessPlan": 4,
        "runtime.local.process.IndexerProcessPlanFactory": 4,
        "runtime.local.process.InstallationProcessEnvironment": 3,
        "runtime.local.process.ProcessIndexerExecutor": 1,
        "runtime.local.sandbox.WorkerSandboxBackend": 1,
        "runtime.local.sandbox.WorkerSandboxBackends": 1,
    },
    ("minos-provider-scip", "minos-storage"): {
        "storage.local.store.FileSymbolSnapshotStore": 1,
    },
}

# D4 (SH-02, E04), ADR 0068 section 4 — inside minos-storage, the types of com.minos.storage.local that the production
# sources of com.minos.storage.postgresql name, each with the number of files that name it. The list only shrinks (SH-03
# moves the shared codecs to com.minos.storage.codec and empties it); com.minos.storage.local names no type of
# com.minos.storage.postgresql at all.
STORAGE_MODULE = "minos-storage"
LOCAL_BACKEND_PACKAGE = "storage.local"
BACKEND_TYPES_NAMED_ACROSS_PACKAGES: dict[str, int] = {
    "storage.local.registry.ProjectPathMappingStore": 1,
    "storage.local.store.KnowledgeSnapshotCodecs": 2,
    "storage.local.store.SnapshotCodec": 2,
    "storage.local.store.SnapshotIntegrityService": 1,
}

# A14 / AUD-ARC-12, ADR 0068 — the package roots (first segment under the groupId) each module owns. A new package
# goes under one of its module's roots; a new root is a decision, written here by a visible edit (ADR 0068). A root may
# be owned by several modules (runtime, storage, program, integration): A3 keeps each package in one module, and
# ADR 0068 gives the adapters a module namespace below such a root (com.minos.runtime.local, com.minos.storage.local).
PACKAGE_ROOTS_BY_MODULE: dict[str, frozenset[str]] = {
    # diagnostics: the whole package (PublicErrorMessages, without I/O nor engine dependency) moved down from
    # minos-engine with its qualified name unchanged (ADR 0068 section 3, step 1; AUD-ARC-01, lot 6).
    "minos-domain": frozenset({"diagnostics", "domain", "program", "semantic"}),
    "minos-engine": frozenset({
        "discovery", "dynamic", "git", "hosted", "incremental", "io", "orchestration", "query",
        "registry", "remote", "runtime", "source", "storage", "store",
    }),
    "minos-runtime-local": frozenset({"runtime"}),
    "minos-storage": frozenset({"storage"}),
    "minos-provider-scip": frozenset({"adapter"}),
    "minos-integration-git": frozenset({"integration"}),
    "minos-application": frozenset({
        "application", "architecture", "context", "impact", "output", "program", "workspace",
    }),
    "minos-bootstrap": frozenset({"bootstrap"}),
    "minos-nexus": frozenset({"nexus"}),
    "minos-cli": frozenset({"cli"}),
    "minos-api": frozenset({"api"}),
    "minos-mcp": frozenset({"mcp"}),
    "minos-app": frozenset({"app", "integration"}),
}


def production_types(root: Path, module: str) -> dict[str, str]:
    """Top-level production types of one module: qualified name -> package."""
    source_root = root / module / "src" / "main" / "java"
    if not source_root.is_dir():
        return {}
    return {".".join(source.relative_to(source_root).with_suffix("").parts):
            ".".join(source.relative_to(source_root).parts[:-1]) for source in sorted(source_root.rglob("*.java"))}


PUBLIC_METHOD = re.compile(
    r"\bpublic\s+((?:(?:static|final|synchronized|abstract|default)\s+)*)([A-Za-z_][\w.<>,?\[\] ]*?)\s+([a-z]\w*)\s*\(")
RETURN_TYPE_WORD = re.compile(r"\b[A-Z]\w*(?:\.[A-Z]\w*)*")
ACCESSOR_USE = re.compile(r"(::|\.)\s*([A-Za-z_]\w*)\b(\s*\()?")


def literals_as_tokens(text: str) -> str:
    """Blanks comments and replaces each string or character literal by one token, so that a literal argument still
    counts as an argument."""
    return COMMENTS_AND_LITERALS.sub(lambda match: " " if match.group(0).startswith("/") else "0", text)


def minos_application_accessors(root: Path = ROOT, group_id: str | None = None) -> dict[str, tuple[int, str]]:
    """A11 — the public instance methods of MinosApplication whose return type names a minos-engine type: method name
    -> (parameter count, engine type relative to the groupId). Read from the source, never written in this guard. A
    type argument (Optional<T>, List<T>) counts; a nested type counts as its top-level type. The application source is
    required as soon as minos-application has production sources (fail-closed)."""
    group = group_id or repository_group_id(root)
    source_root = root / APPLICATION_MODULE / "src" / "main" / "java"
    source = source_root / Path(*group.split(".")) / "application" / "MinosApplication.java"
    if not source.is_file():
        if source_root.is_dir():
            fail(f"A11 cannot read the accessors of MinosApplication: {source.relative_to(root).as_posix()} is "
                 "missing (the ratchet would stop counting the uses without a name)")
        return {}
    code = literals_as_tokens(source.read_text(encoding="utf-8"))
    imported = {name.rsplit(".", 1)[-1]: name for static, name, wildcard in SOURCE_IMPORT.findall(code)
                if not static and not wildcard}
    engine = production_types(root, ENGINE_MODULE)
    qualified_name = qualified_name_pattern(group)
    accessors: dict[str, tuple[int, str]] = {}
    depth = 0
    position = 0
    for match in PUBLIC_METHOD.finditer(code):
        depth += code.count("{", position, match.start()) - code.count("}", position, match.start())
        position = match.start()
        modifiers, returned, name = match.groups()
        if depth != 1 or re.search(r"\bstatic\b", modifiers):
            continue
        candidates = qualified_name.findall(returned)
        for word in RETURN_TYPE_WORD.findall(returned):
            head, _, tail = word.partition(".")
            if head in imported:
                candidates.append(imported[head] + ("." + tail if tail else ""))
        types = sorted({resolved.removeprefix(group + ".") for resolved in
                        (resolve_adapter_class(candidate, engine) for candidate in candidates) if resolved})
        if len(types) > 1:
            fail(f"A11 accessor MinosApplication.{name} returns several minos-engine types ({', '.join(types)}); "
                 "the ratchet counts one type per accessor")
        if types:
            accessors[name] = (call_argument_count(code, match.end() - 1, "A11 unbalanced MinosApplication method"),
                               types[0])
    return accessors


def accessor_types_used(code: str, accessors: dict[str, tuple[int, str]], group: str) -> set[str]:
    """Engine types (qualified) a source reaches through a MinosApplication accessor: a call with the accessor's arity,
    or a method reference. The code is the output of literals_as_tokens, imports removed."""
    used: set[str] = set()
    for match in ACCESSOR_USE.finditer(code):
        accessor = accessors.get(match.group(2))
        if accessor is None:
            continue
        if match.group(1) == "::" or (match.group(3) and accessor[0] == call_argument_count(
                code, match.end() - 1, "A11 unbalanced accessor call")):
            used.add(f"{group}.{accessor[1]}")
    return used


def types_named_by_module(root: Path, module: str, targets: dict[str, str], qualified_name: re.Pattern[str],
                          accessors: dict[str, tuple[int, str]] | None = None, group: str = "",
                          naming_package: str = "") -> tuple[dict[str, set[str]], list[str]]:
    """Target types the production sources of one module use (type -> files): by single or static import, by fully
    qualified name and, when accessors are given, by a call of a MinosApplication accessor that returns the type. Also
    every wildcard import of a target package ('file -> package.*'). Comments and string literals are ignored; a nested
    type counts as its top-level type. A naming package (dotted, under the source root) restricts the naming files to
    that package and its subpackages."""
    named: dict[str, set[str]] = {}
    wildcards: list[str] = []
    packages = set(targets.values())
    source_root = root / module / "src" / "main" / "java"
    naming_root = source_root.joinpath(*naming_package.split(".")) if naming_package else source_root
    if not naming_root.is_dir():
        return named, wildcards
    for source in sorted(naming_root.rglob("*.java")):
        text = source.read_text(encoding="utf-8")
        code = COMMENTS_AND_LITERALS.sub(" ", text)
        relative = source.relative_to(root).as_posix()
        names = []
        for static, name, wildcard in SOURCE_IMPORT.findall(code):
            if wildcard and not static:
                if name in packages:
                    wildcards.append(f"{relative} -> {name}.*")
                continue
            names.append(name)
        names += qualified_name.findall(SOURCE_IMPORT.sub(" ", code))
        if accessors:
            names += sorted(accessor_types_used(SOURCE_IMPORT.sub(" ", literals_as_tokens(text)), accessors, group))
        for name in names:
            resolved = resolve_adapter_class(name, targets)
            if resolved:
                named.setdefault(resolved, set()).add(relative)
    return named, wildcards


def named_type_ratchet_problems(rule: str, label: str, table: str, root: Path, naming: str, target: str,
                                listed: dict[str, int], group: str, qualified_name: re.Pattern[str],
                                accessors: dict[str, tuple[int, str]] | None = None, naming_package: str = "",
                                target_package: str = "") -> list[str]:
    """Problems of one (naming module, target module) list: unknown entry, invalid count, type outside the list, count
    different from the number of files that use the type, dead entry. Naming and target packages (dotted, under the
    source root) narrow the list to the types of one package named by the files of another, inside one module."""
    targets = {name: package for name, package in production_types(root, target).items()
               if not target_package or name.startswith(target_package + ".")}
    prefix = group + "."
    problems = [f"{label}: {table} entry {name} names no production type of {target}"
                for name in sorted(listed) if prefix + name not in targets]
    problems += [f"{label}: {table} entry {name} has the count {count!r}; write the positive number of files that "
                 "use it" for name, count in sorted(listed.items())
                 if isinstance(count, bool) or not isinstance(count, int) or count < 1]
    named, wildcards = types_named_by_module(root, naming, targets, qualified_name, accessors, group, naming_package)
    problems += [f"{label}: wildcard import of a {target} package {wildcard}" for wildcard in wildcards]
    for qualified in sorted(named):
        name = qualified.removeprefix(prefix)
        files = sorted(named[qualified])
        if name not in listed:
            problems.append(f"{label}: {', '.join(files)} uses {qualified}, absent from {table}")
        elif listed[name] != len(files):
            advice = ("a new use goes through a port, not into the table" if len(files) > listed[name]
                      else f"write {len(files)} (the ratchet only shrinks)")
            problems.append(f"{label}: {table} entry {name} is listed for {listed[name]} file(s) and used by "
                            f"{len(files)} ({', '.join(files)}); {advice}")
    used = {qualified.removeprefix(prefix) for qualified in named}
    problems += [f"{label}: {table} entry {name} is no longer named; remove it (the ratchet only shrinks)"
                 for name in sorted(listed) if prefix + name in targets and name not in used]
    return problems


def check_engine_types_named_by_surfaces(root: Path = ROOT, group_id: str | None = None,
                                         listed: dict[str, dict[str, int]] | None = None) -> dict[str, int]:
    """A11 — each surface uses only the minos-engine types of its ENGINE_TYPES_NAMED_BY_SURFACE list, each from the
    listed number of files; a call of a MinosApplication accessor counts as a use of the engine type it returns."""
    listed = ENGINE_TYPES_NAMED_BY_SURFACE if listed is None else listed
    group = group_id or repository_group_id(root)
    qualified_name = qualified_name_pattern(group)
    accessors = minos_application_accessors(root, group)
    unknown = sorted(set(listed) - SURFACE_MODULES)
    problems = [f"ENGINE_TYPES_NAMED_BY_SURFACE names {module}, which is not a surface" for module in unknown]
    for surface in sorted(SURFACE_MODULES):
        problems += named_type_ratchet_problems("A11", surface, "ENGINE_TYPES_NAMED_BY_SURFACE", root, surface,
                                                ENGINE_MODULE, listed.get(surface, {}), group, qualified_name,
                                                accessors)
    if problems:
        fail("A11 surface -> minos-engine ratchet (ADR 0058 section 3: a new engine type used by a surface needs a "
             "justification; go through an application port, or add the type to ENGINE_TYPES_NAMED_BY_SURFACE in "
             "a reviewed change): " + "; ".join(problems))
    return {surface: len(listed.get(surface, {})) for surface in sorted(SURFACE_MODULES)}


def check_process_launchers(root: Path = ROOT, group_id: str | None = None,
                            listed: dict[str, int] | None = None,
                            modules: tuple[str, ...] = PROCESS_LAUNCHER_MODULES) -> int:
    """A12 — in the domain, the engine and the application, only the PROCESS_LAUNCHERS classes launch a process (a
    class that mentions ProcessBuilder or Runtime.getRuntime().exec), each with its listed number of launch sites (new
    ProcessBuilder, ProcessBuilder::new, ProcessBuilder.startPipeline, Runtime.getRuntime().exec). Comments and string
    literals are ignored."""
    listed = PROCESS_LAUNCHERS if listed is None else listed
    prefix = (group_id or repository_group_id(root)) + "."
    found: dict[str, tuple[str, int]] = {}
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            code = COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8"))
            if PROCESS_LAUNCH.search(code):
                name = ".".join(source.relative_to(source_root).with_suffix("").parts).removeprefix(prefix)
                found[f"{module}/{name}"] = (source.relative_to(root).as_posix(),
                                             len(PROCESS_LAUNCH_SITE.findall(code)))
    problems = [f"{path} launches a process and is not in PROCESS_LAUNCHERS ({entry})"
                for entry, (path, _) in sorted(found.items()) if entry not in listed]
    problems += [f"PROCESS_LAUNCHERS entry {entry} has the count {count!r}, which is not a count of launch sites"
                 for entry, count in sorted(listed.items())
                 if isinstance(count, bool) or not isinstance(count, int) or count < 0]
    for entry, (path, sites) in sorted(found.items()):
        if entry in listed and listed[entry] != sites:
            advice = ("a new launch goes through a port provided by minos-runtime-local" if sites > listed[entry]
                      else f"write {sites} (the ratchet only shrinks)")
            problems.append(f"{path}: PROCESS_LAUNCHERS entry {entry} is listed with {listed[entry]} launch site(s) "
                            f"and has {sites}; {advice}")
    problems += [f"PROCESS_LAUNCHERS entry {entry} launches no process any more; remove it (the ratchet only shrinks)"
                 for entry in sorted(set(listed) - set(found))]
    if problems:
        fail("A12 process launch outside minos-runtime-local (ADR 0068: the process runtime belongs to the local "
             "runtime adapter; inject a port it provides): " + "; ".join(problems))
    return len(listed)


def check_adapter_types_named_by_adapters(root: Path = ROOT, group_id: str | None = None,
                                          listed: dict[tuple[str, str], dict[str, int]] | None = None) -> int:
    """A13 — an adapter names the types of another adapter only through ADAPTER_TYPES_NAMED_BY_ADAPTER, each from the
    listed number of files."""
    listed = ADAPTER_TYPES_NAMED_BY_ADAPTER if listed is None else listed
    group = group_id or repository_group_id(root)
    qualified_name = qualified_name_pattern(group)
    problems = [f"ADAPTER_TYPES_NAMED_BY_ADAPTER names {naming} -> {named}, which is not a couple of adapters"
                for naming, named in sorted(listed) if {naming, named} - ADAPTER_MODULES or naming == named]
    for naming in sorted(ADAPTER_MODULES):
        for named in sorted(ADAPTER_MODULES - {naming}):
            problems += named_type_ratchet_problems("A13", f"{naming} -> {named}", "ADAPTER_TYPES_NAMED_BY_ADAPTER",
                                                    root, naming, named, listed.get((naming, named), {}),
                                                    group, qualified_name)
    if problems:
        fail("A13 adapter -> adapter ratchet (ADR 0068: adapters meet through engine ports; a new coupling is "
             "added to ADAPTER_TYPES_NAMED_BY_ADAPTER in a reviewed change): " + "; ".join(problems))
    return sum(len(types) for types in listed.values())


def check_backend_types_named_across_packages(root: Path = ROOT, group_id: str | None = None,
                                              listed: dict[str, int] | None = None) -> int:
    """D4 — inside minos-storage, the PostgreSQL backend names the types of the local backend only through
    BACKEND_TYPES_NAMED_ACROSS_PACKAGES, each from the listed number of files; the local backend names none of the
    PostgreSQL backend."""
    listed = BACKEND_TYPES_NAMED_ACROSS_PACKAGES if listed is None else listed
    group = group_id or repository_group_id(root)
    qualified_name = qualified_name_pattern(group)
    local, postgresql = f"{group}.{LOCAL_BACKEND_PACKAGE}", f"{group}.{POSTGRESQL_BACKEND_PACKAGE}"
    problems = named_type_ratchet_problems("D4", "postgresql -> local", "BACKEND_TYPES_NAMED_ACROSS_PACKAGES", root,
                                           STORAGE_MODULE, STORAGE_MODULE, listed, group, qualified_name,
                                           naming_package=postgresql, target_package=local)
    problems += named_type_ratchet_problems("D4", "local -> postgresql", "the empty list of the local backend", root,
                                            STORAGE_MODULE, STORAGE_MODULE, {}, group, qualified_name,
                                            naming_package=local, target_package=postgresql)
    if problems:
        fail("D4 storage backends ratchet (ADR 0068 section 4: local and postgresql do not depend on each other; SH-03 "
             "moves the shared codecs out of the local backend): " + "; ".join(problems))
    return len(listed)


# SH-03 (E04), ADR 0068 section 4 — inside minos-storage, production and test sources alike: the local and PostgreSQL
# backends do not name each other, and the shared packages (the snapshot codec and the MINOS_HOME path mapping) name
# neither backend, nor the PostgreSQL driver, nor JDBC. Each rule: (label, source package, forbidden prefixes); a prefix
# under the groupId is written without it.
STORAGE_PACKAGE_RULES: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("local -> postgresql", LOCAL_BACKEND_PACKAGE, (POSTGRESQL_BACKEND_PACKAGE, POSTGRESQL_DRIVER_PACKAGE)),
    ("postgresql -> local", POSTGRESQL_BACKEND_PACKAGE, (LOCAL_BACKEND_PACKAGE,)),
    ("codec -> backends", "storage.codec",
     (LOCAL_BACKEND_PACKAGE, POSTGRESQL_BACKEND_PACKAGE, POSTGRESQL_DRIVER_PACKAGE, "java.sql")),
    ("home -> backends", "storage.home",
     (LOCAL_BACKEND_PACKAGE, POSTGRESQL_BACKEND_PACKAGE, POSTGRESQL_DRIVER_PACKAGE, "java.sql")),
)
EXTERNAL_PACKAGE_PREFIXES = (POSTGRESQL_DRIVER_PACKAGE, "java.")


def check_storage_package_isolation(root: Path = ROOT, group_id: str | None = None) -> int:
    """SH-03 — no production nor test source of a STORAGE_PACKAGE_RULES package of minos-storage names a forbidden
    package, by single, static or wildcard import or by qualified name; comments and string literals are ignored."""
    group = group_id or repository_group_id(root)
    violations: list[str] = []
    for label, package, forbidden in STORAGE_PACKAGE_RULES:
        prefixes = tuple(prefix if prefix.startswith(EXTERNAL_PACKAGE_PREFIXES) else f"{group}.{prefix}"
                         for prefix in forbidden)
        qualified = re.compile(r"\b(?:" + "|".join(re.escape(prefix) for prefix in prefixes) + r")(?:\.\w+)+")
        for kind in ("main", "test"):
            package_root = root / STORAGE_MODULE / "src" / kind / "java" / Path(*f"{group}.{package}".split("."))
            if not package_root.is_dir():
                continue
            for source in sorted(package_root.rglob("*.java")):
                code = COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8"))
                found: set[str] = set()
                for _static, name, wildcard in SOURCE_IMPORT.findall(code):
                    if any(name == prefix or name.startswith(prefix + ".") for prefix in prefixes):
                        found.add(name + (".*" if wildcard else ""))
                found.update(qualified.findall(SOURCE_IMPORT.sub(" ", code)))
                relative = source.relative_to(root).as_posix()
                violations += [f"{label} {relative} -> {name}" for name in sorted(found)]
    if violations:
        fail("Storage package isolation violated (ADR 0068 section 4: the backends meet only through the shared codec "
             "and path-mapping packages, which name neither backend nor JDBC): " + "; ".join(violations))
    return len(STORAGE_PACKAGE_RULES)


def check_package_roots(root: Path = ROOT, group_id: str | None = None,
                        listed: dict[str, frozenset[str]] | None = None,
                        modules: tuple[str, ...] = MODULES) -> int:
    """A14 — every production package of a module sits under one of its PACKAGE_ROOTS_BY_MODULE roots."""
    listed = PACKAGE_ROOTS_BY_MODULE if listed is None else listed
    group = group_id or repository_group_id(root)
    problems = [f"PACKAGE_ROOTS_BY_MODULE names {module}, which is not a governed module"
                for module in sorted(set(listed) - set(modules))]
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        roots: dict[str, str] = {}
        if source_root.is_dir():
            for source in sorted(source_root.rglob("*.java")):
                package = declared_package(source)
                segment = package[len(group) + 1:].split(".")[0] if package.startswith(group + ".") else ""
                roots.setdefault(segment, source.relative_to(root).as_posix())
        allowed = listed.get(module, frozenset())
        problems += [f"{module}: new package root {group}.{segment or '<none>'} ({path}); put the package under "
                     f"one of {', '.join(sorted(allowed)) or 'no root'}" for segment, path in sorted(roots.items())
                     if segment not in allowed]
        problems += [f"{module}: root {group}.{segment} holds no production source any more; remove it (the ratchet "
                     "only shrinks)" for segment in sorted(allowed - set(roots))]
    if problems:
        fail("A14 package roots (ADR 0068: no rename of existing packages, no new root without a decision): "
             + "; ".join(problems))
    return sum(len(roots) for roots in listed.values())


SERVICE_LOADER_LOAD = re.compile(r"\bServiceLoader\s*\.\s*load\s*\(")


def blank_comments_and_literals(code: str) -> str:
    """Blanks comments and string literals but keeps every newline, so that line numbers stay exact."""
    return COMMENTS_AND_LITERALS.sub(lambda match: re.sub(r"[^\n]", " ", match.group(0)), code)


def call_argument_count(code: str, open_parenthesis: int, label: str = "A16 unbalanced ServiceLoader.load call") -> int:
    """Top-level arguments of the call whose opening parenthesis is at the given index (0 for an empty call); an
    unbalanced call fails the guard with the given label."""
    depth = 0
    commas = 0
    empty = True
    for character in code[open_parenthesis + 1:]:
        if character in "([{":
            depth += 1
        elif character in ")]}":
            if depth == 0:
                return 0 if empty else commas + 1
            depth -= 1
        elif character == "," and depth == 0:
            commas += 1
        if not character.isspace():
            empty = False
    fail(label)
    return 0


def check_service_loader_class_loaders(root: Path = ROOT, modules: tuple[str, ...] = MODULES) -> int:
    """A16 / ADR 0042 — every production ServiceLoader.load call passes an explicit class loader.

    The one-argument form uses the thread context class loader: in a Java host that embeds MINOS with another
    context loader, an optional provider silently disappears (AUD-ARC-04). Production sources of the reactor
    must call ServiceLoader.load(Service.class, loader). Comments and string literals are ignored. Returns the
    number of production discovery sites.
    """
    violations: list[str] = []
    sites = 0
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            code = blank_comments_and_literals(source.read_text(encoding="utf-8"))
            for match in SERVICE_LOADER_LOAD.finditer(code):
                sites += 1
                if call_argument_count(code, match.end() - 1) < 2:
                    line = code.count("\n", 0, match.start()) + 1
                    violations.append(f"{source.relative_to(root).as_posix()}:{line}")
    if violations:
        fail("A16 ServiceLoader.load without an explicit class loader (the thread context class loader must not "
             "drive production discovery; pass the loader of the service port): " + "; ".join(violations))
    return sites

# A18 / AUD-ARC-07, ADR 0056 — read use cases read the active snapshot through SnapshotReadView and the
# CodeKnowledgeReader port. Only the com.minos.store package of minos-engine names the in-memory index; the deprecated
# SnapshotQueryView, which hands that index out, is named or reached (loadActiveQueryView(, queryStore()) only by the
# closed list below; minos-application names no write port (CodeKnowledgeStore). The words are searched in the code
# with comments and literals blanked: imports (single, static, wildcard), qualified names, nested types and chained
# calls all show the word. The list only shrinks.
LEGACY_SNAPSHOT_VIEW_USERS = frozenset({
    # Both snapshot stores implement the deprecated loadActiveQueryView by SnapshotQueryView.of(read view).
    "minos-storage/storage.local.store.FileSymbolSnapshotStore",
    "minos-storage/storage.postgresql.snapshot.PostgresCodeKnowledgeSnapshotStore",
    # Deprecated public overload analyze(SnapshotQueryView, ...), delegating to analyze(SnapshotReadView, ...).
    "minos-application/impact.ImpactAnalysisService",
})
CORE_STORE_PACKAGE = "store"
MEMORY_INDEX = re.compile(r"\bInMemoryCodeKnowledgeStore\b")
LEGACY_SNAPSHOT_VIEW = re.compile(r"\bSnapshotQueryView\b|\bloadActiveQueryView\s*\(|\bqueryStore\s*\(\s*\)")
WRITE_PORT = re.compile(r"\bCodeKnowledgeStore\b")


def check_snapshot_read_port(root: Path = ROOT, group_id: str | None = None,
                             listed: frozenset[str] = LEGACY_SNAPSHOT_VIEW_USERS,
                             modules: tuple[str, ...] = MODULES) -> int:
    """A18 — the in-memory index stays in the core, the deprecated view in its closed list, the write port out of
    minos-application (production sources; comments and string literals ignored)."""
    group = group_id or repository_group_id(root)
    core = f"{group}.{CORE_STORE_PACKAGE}"
    problems: list[str] = []
    users: set[str] = set()
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            if module == ENGINE_MODULE and declared_package(source) == core:
                continue
            relative = source.relative_to(root).as_posix()
            name = ".".join(source.relative_to(source_root).with_suffix("").parts).removeprefix(group + ".")
            entry = f"{module}/{name}"
            code = blank_comments_and_literals(source.read_text(encoding="utf-8"))
            for number, line in enumerate(code.splitlines(), start=1):
                if MEMORY_INDEX.search(line):
                    problems.append(f"{relative}:{number} names InMemoryCodeKnowledgeStore; read through "
                                    f"SnapshotReadView and CodeKnowledgeReader")
                legacy = ", ".join(re.sub(r"\s+", "", match.group(0)).replace("(", "(...)").replace("(...))", "()")
                                   for match in LEGACY_SNAPSHOT_VIEW.finditer(line))
                if legacy:
                    users.add(entry)
                    if entry not in listed:
                        problems.append(f"{relative}:{number} uses the deprecated snapshot view ({legacy}); use "
                                        f"loadActiveReadView and SnapshotReadView")
                if module == APPLICATION_MODULE and WRITE_PORT.search(line):
                    problems.append(f"{relative}:{number} names the write port CodeKnowledgeStore; a read use case "
                                    f"receives CodeKnowledgeReader")
    problems += [f"LEGACY_SNAPSHOT_VIEW_USERS entry {entry} does not exist or no longer uses the deprecated view; "
                 f"remove it (the ratchet only shrinks)" for entry in sorted(listed - users)]
    if problems:
        fail("A18 snapshot read port (ADR 0056: only minos-engine com.minos.store names the in-memory index; the "
             "deprecated SnapshotQueryView stays in LEGACY_SNAPSHOT_VIEW_USERS): " + "; ".join(problems))
    return len(listed)



# A15 / AUD-QUA-02 — a public production type is used by production, or declared. Each entry names one top-level
# public type of a reactor src/main (module/package.Type) with the reason it has no production caller. The list is a ratchet: an entry whose
# type is gone, or is now named by production, fails the guard.
UNREFERENCED_PRODUCTION_TYPES: dict[str, str] = {
    "minos-api/com.minos.api.LocalAdvancedCodeIntelligenceApi":
        "public Java API entry point (docs/user/java-api.md): its callers are outside the reactor",
    "minos-api/com.minos.api.LocalMinosMultiRepositoryApi":
        "public Java API entry point (docs/user/java-api.md, ADR 0042): its callers are outside the reactor",
    "minos-api/com.minos.api.LocalProviderPlatformApi":
        "public Java API entry point (docs/user/java-api.md, ADR 0042): its callers are outside the reactor",
    "minos-api/com.minos.api.LocalSemanticCodeIntelligenceApi":
        "public Java API entry point (docs/user/java-api.md): its callers are outside the reactor",
    "minos-application/com.minos.application.StorageBackends":
        "public storage facade kept by ADR 0042 and ADR 0068 (signature unchanged for external callers)",
    "minos-application/com.minos.program.analysis.ProgramGraphEvaluator":
        "ground-truth evaluation tool of the program-graph tests of two modules and of "
        "check-java-ast-provider-consistency.py; a test-jar would cost more (retirer-les-services-sans-appelant, D1)",
    "minos-engine/com.minos.hosted.HostedAvailabilityPort":
        "declared hosted boundary, not qualified: implemented by the operator of a hosted deployment",
    "minos-engine/com.minos.hosted.HostedTransportSecurityPort":
        "declared hosted boundary, not qualified: implemented by the operator of a hosted deployment",
    "minos-engine/com.minos.orchestration.InMemoryIndexStateStore":
        "in-memory state store used only by the tests of five modules; moving it needs a test-jar, not done here",
    "minos-nexus/com.minos.nexus.NexusSemanticSignalService":
        "public NEXUS semantic signal v2 surface (docs/developer/public-surfaces.md), not qualified by a client",
    "minos-storage/com.minos.storage.local.store.CodeKnowledgeSnapshotBinaryCodec":
        "declared snapshot codec boundary, excluded by AUD-QUA-02 itself",
}
TOP_LEVEL_PUBLIC_TYPE = re.compile(
    r"^public\s+(?:(?:abstract|final|sealed|non-sealed|static|strictfp)\s+)*"
    r"(?:class|interface|enum|record|@interface)\s+([A-Z]\w*)", re.MULTILINE)
MAIN_METHOD = re.compile(r"\bpublic\s+static\s+void\s+main\s*\(")


def production_sources(root: Path = ROOT) -> list[tuple[str, Path]]:
    """(module, file) of every Java source of a reactor src/main."""
    sources: list[tuple[str, Path]] = []
    for module in MODULES:
        source_root = root / module / "src" / "main" / "java"
        if source_root.is_dir():
            sources.extend((module, source) for source in sorted(source_root.rglob("*.java")))
    return sources


def service_registered_types(root: Path = ROOT) -> set[str]:
    """Qualified names that a production META-INF/services file names (service interface or implementation)."""
    names: set[str] = set()
    for module in MODULES:
        services = root / module / "src" / "main" / "resources" / "META-INF" / "services"
        if not services.is_dir():
            continue
        for descriptor in sorted(path for path in services.iterdir() if path.is_file()):
            names.add(descriptor.name.strip())
            for line in descriptor.read_text(encoding="utf-8").splitlines():
                entry = line.split("#", 1)[0].strip()
                if entry:
                    names.add(entry)
    return names


def unreferenced_production_types(root: Path = ROOT) -> dict[str, str]:
    """'module/package.Type' -> source path, for every top-level public type of a reactor src/main that no other
    production source names and that neither a production META-INF/services file nor a main method exempts.

    A source names a type when the simple name appears in its code (comments and string literals removed) and the
    source is in the same package, imports the type, a member of it or its package, or writes its qualified name.
    Test sources do not count. Known blind spots, assumed: reflection, a reference from a type that is itself
    unreferenced, and a homonym imported from another package (counted as a reference)."""
    services = service_registered_types(root)
    parsed: list[tuple[str, Path, str, str]] = []
    for module, source in production_sources(root):
        if source.name in ("package-info.java", "module-info.java"):
            continue
        text = source.read_text(encoding="utf-8")
        match = PACKAGE.search(text)
        parsed.append((module, source, match.group(1) if match else "", COMMENTS_AND_LITERALS.sub(" ", text)))
    declared: list[tuple[str, str, str, Path, str]] = []
    for module, source, package, code in parsed:
        type_match = TOP_LEVEL_PUBLIC_TYPE.search(code)
        if type_match is None or type_match.group(1) != source.stem:
            continue
        declared.append((module, package, source.stem, source, code))
    result: dict[str, str] = {}
    for module, package, name, source, code in declared:
        qualified = f"{package}.{name}"
        if qualified in services or MAIN_METHOD.search(code):
            continue
        word = re.compile(r"\b" + re.escape(name) + r"\b")
        referenced = False
        for _other_module, other, other_package, other_code in parsed:
            if other == source or not word.search(other_code):
                continue
            if other_package == package or qualified in other_code:
                referenced = True
                break
            for _static, imported, wildcard in SOURCE_IMPORT.findall(other_code):
                if imported == qualified or imported.startswith(qualified + ".") or (wildcard and imported == package):
                    referenced = True
                    break
            if referenced:
                break
        if not referenced:
            result[f"{module}/{qualified}"] = source.relative_to(root).as_posix()
    return result


def check_unreferenced_production_types(root: Path = ROOT,
                                        declared: dict[str, str] = UNREFERENCED_PRODUCTION_TYPES) -> int:
    """A15 — every top-level public type of a reactor src/main is named by production, or listed with a reason."""
    unreasoned = sorted(entry for entry, reason in declared.items() if not reason.strip())
    if unreasoned:
        fail("A15 UNREFERENCED_PRODUCTION_TYPES entries need a written reason: " + ", ".join(unreasoned))
    found = unreferenced_production_types(root)
    undeclared = sorted(entry for entry in found if entry not in declared)
    if undeclared:
        fail("A15 public production types without a production caller (delete them, move them to tests, or list "
             "them in UNREFERENCED_PRODUCTION_TYPES with a reason): " + ", ".join(undeclared))
    existing = set()
    for module, source in production_sources(root):
        match = PACKAGE.search(source.read_text(encoding="utf-8"))
        existing.add(f"{module}/{match.group(1) if match else ''}.{source.stem}")
    stale = sorted(entry for entry in declared if entry not in existing)
    if stale:
        fail("A15 UNREFERENCED_PRODUCTION_TYPES is a ratchet: these types no longer exist, remove the entries: "
             + ", ".join(stale))
    useless = sorted(entry for entry in declared if entry in existing and entry not in found)
    if useless:
        fail("A15 UNREFERENCED_PRODUCTION_TYPES is a ratchet: production now names these types, remove the "
             "entries: " + ", ".join(useless))
    return len(declared)


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
    """A3 / ADR 0068 — one package, one module. Returns the number of production packages."""
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
# table can only shrink, by a visible edit, when a cycle is broken. The table is EMPTY since the change
# `casser-les-cycles-de-packages` lifted the last four cycles (15 packages): the mechanism stays, so a cycle that
# comes back is refused, and an entry added to excuse one is a visible change to review. What this does not see: references through reflection, ServiceLoader, or a type
# only named through a generic parameter without import (no bytecode is read). An edge added inside a listed cycle is
# not seen either: the packages of the component do not change.
EXTERNAL_SOURCE_ROOTS = ("minos-intellij",)
KNOWN_PACKAGE_CYCLES: dict[str, tuple[frozenset[str], str]] = {}


def declared_classes(root: Path, owners: tuple[str, ...]) -> tuple[dict[str, str], list[tuple[str, str, str]]]:
    """Every production class (FQN -> package) of the owners, and each source as (relative path, package, code)."""
    classes: dict[str, str] = {}
    sources: list[tuple[str, str, str]] = []
    for owner in owners:
        source_root = root / owner / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in sorted(source_root.rglob("*.java")):
            code = COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8"))
            match = PACKAGE.search(code)
            package = match.group(1) if match else ""
            classes[".".join(source.relative_to(source_root).with_suffix("").parts)] = package
            sources.append((source.relative_to(root).as_posix(), package, code))
    return classes, sources


def package_of_name(name: str, wildcard: bool, classes: dict[str, str], packages: set[str]) -> str | None:
    """The declared package a (possibly nested or static) import or qualified name designates, if any."""
    if wildcard and name in packages:
        return name
    parts = name.split(".")
    for size in range(len(parts), 0, -1):
        candidate = ".".join(parts[:size])
        if candidate in classes:
            return classes[candidate]
    return None


def packages_used_by(code: str, classes: dict[str, str], packages: set[str],
                     qualified_name: re.Pattern[str]) -> list[str]:
    """Declared packages named by the imports and the fully qualified names of one source."""
    used = [package_of_name(name, bool(wildcard), classes, packages)
            for _, name, wildcard in SOURCE_IMPORT.findall(code)]
    body = SOURCE_IMPORT.sub(" ", code)
    used += [package_of_name(name, False, classes, packages) for name in qualified_name.findall(body)]
    return [package for package in used if package is not None]


def package_graph(root: Path, owners: tuple[str, ...], group_id: str) -> tuple[
        dict[str, set[str]], dict[tuple[str, str], str], set[str]]:
    """Package -> packages it uses, one witness file per edge, and every declared package."""
    classes, sources = declared_classes(root, owners)
    packages = set(classes.values())
    qualified_name = qualified_name_pattern(group_id)
    edges: dict[str, set[str]] = {package: set() for package in packages}
    witness: dict[tuple[str, str], str] = {}
    for relative, package, code in sources:
        for target in packages_used_by(code, classes, packages, qualified_name):
            if target != package:
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


def documented_dependency_lines(document: Path) -> dict[str, list[str]]:
    """Module -> its "Dépendances" bullets, for each `### minos-…` section of the document."""
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in document.read_text(encoding="utf-8").splitlines():
        heading = MODULE_HEADING.match(line)
        if heading:
            current = heading.group(1)
            sections.setdefault(current, [])
        elif ANY_HEADING.match(line):
            current = None
        elif current is not None:
            bullet = DEPENDENCIES_BULLET.match(line)
            if bullet:
                sections[current].append(bullet.group(1))
    return sections


def documentation_problem(module: str, lines: list[str], expected: set[str], modules: tuple[str, ...]) -> str | None:
    """Why the "Dépendances" bullets of one module section do not match its POM, or None when they do."""
    if len(lines) != 1:
        return f"{module}: expected one 'Dépendances' line in its section, found {len(lines)}"
    text = lines[0]
    if module == ASSEMBLY_MODULE and "tous les modules" in text.casefold():
        missing = set(modules) - {module} - expected
        return f"{module}: documented as depending on all modules, the POM misses {', '.join(sorted(missing))}" \
            if missing else None
    documented = set(MODULE_TOKEN.findall(text)) - {module}
    if documented == expected:
        return None
    line = ", ".join(f"`{name}`" for name in sorted(expected)) or "aucune"
    return (f"{module}: documented {sorted(documented)}, POM declares {sorted(expected)}; "
            f"expected line: - **Dépendances** : {line}")


def check_architecture_documentation(graph: dict[str, frozenset[str]], document: Path = ARCHITECTURE_BLOCKS_DOC,
                                     modules: tuple[str, ...] = MODULES) -> int:
    """A9 — every module's "Dépendances" line in the arc42 block view equals its direct internal POM dependencies."""
    if not document.is_file():
        fail(f"A9 architecture document is missing: {document}")
    sections = documented_dependency_lines(document)
    problems: list[str] = []
    for module in modules:
        if module not in sections:
            problems.append(f"{module} has no section in {document.name}")
            continue
        problem = documentation_problem(module, sections[module], set(graph[module]), modules)
        if problem:
            problems.append(problem)
    problems += [f"{document.name} has a section for {module}, which is not a governed module"
                 for module in sorted(set(sections) - set(modules))]
    if problems:
        fail("A9 current architecture documentation diverges from the POMs: " + "; ".join(problems))
    return len(modules)


# A17 / ADR 0058 — the ALLOWED table of the ArchUnit test and ALLOWED_DEPENDENCIES are one policy written twice: they
# must stay identical, or the strict ArchUnit rule and this guard judge the same dependency differently.
ARCHITECTURE_TEST = (ROOT / "minos-app" / "src" / "test" / "java" / "com" / "minos" / "app" / "architecture"
                     / "ModuleArchitectureTest.java")
ALLOWED_TABLE = re.compile(r"ALLOWED\s*=\s*Map\.ofEntries\((.*?)\);", re.S)
ALLOWED_ENTRY = re.compile(r'Map\.entry\(\s*"([^"]+)"\s*,\s*Set\.of\(([^)]*)\)\s*\)', re.S)


def check_architecture_test_policy(test: Path = ARCHITECTURE_TEST,
                                   allowed: dict[str, frozenset[str]] = ALLOWED_DEPENDENCIES) -> int:
    """A17 — ModuleArchitectureTest.ALLOWED equals ALLOWED_DEPENDENCIES, module by module (fail-closed if unreadable)."""
    if not test.is_file():
        fail(f"A17 ArchUnit policy table is missing: {test.name}")
    table = ALLOWED_TABLE.search(COMMENTS_AND_LITERALS.sub(lambda m: m.group(0) if m.group(0).startswith('"') else " ",
                                                            test.read_text(encoding="utf-8")))
    if table is None:
        fail(f"A17 cannot read the ALLOWED table of {test.name}: the ArchUnit policy cannot be compared")
    java = {module: frozenset(re.findall(r'"([^"]+)"', deps)) for module, deps in ALLOWED_ENTRY.findall(table.group(1))}
    problems = []
    for module in sorted(set(allowed) | set(java)):
        if module not in java:
            problems.append(f"{module} is absent from {test.name}")
        elif module not in allowed:
            problems.append(f"{module} is absent from ALLOWED_DEPENDENCIES")
        elif java[module] != allowed[module]:
            problems.append(f"{module}: {test.name} allows {sorted(java[module])}, ALLOWED_DEPENDENCIES allows "
                            f"{sorted(allowed[module])}")
    if problems:
        fail("A17 ArchUnit and guard dependency tables diverge (ADR 0058: one policy, two tables kept identical; edit "
             "both in the same change): " + "; ".join(problems))
    return len(allowed)


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


RuntimeEdges = dict[tuple[str, str], tuple[str, ...]]


def runtime_service_edges(root: Path = ROOT, modules: tuple[str, ...] = MODULES) -> RuntimeEdges:
    """AUD-ARC-11 — execution edges a POM does not show: (declaring module, providing module) -> services.

    A production META-INF/services file of module Y whose service interface is a production type of module X != Y
    makes X load an implementation of Y at run time. A service interface outside the reactor is not an edge. An
    implementation that is not a production type of the module that registers it fails, except in the assembly
    minos-app, which may register an implementation of a module it depends on (D7, ADR 0068 section 4: the optional
    PostgreSQL backend); the edge then goes to the module that provides the implementation.
    """
    owners = {qualified: module for module in modules for qualified in production_types(root, module)}
    edges: dict[tuple[str, str], set[str]] = {}
    for module in modules:
        services = root / module / "src" / "main" / "resources" / "META-INF" / "services"
        if not services.is_dir():
            continue
        for descriptor in sorted(path for path in services.iterdir() if path.is_file()):
            service = descriptor.name.strip()
            for line in descriptor.read_text(encoding="utf-8").splitlines():
                implementation = line.split("#", 1)[0].strip()
                if not implementation:
                    continue
                providing = owners.get(implementation)
                assembled = module == ASSEMBLY_MODULE and providing in ALLOWED_DEPENDENCIES[ASSEMBLY_MODULE]
                if providing != module and not assembled:
                    fail(f"{module}: META-INF/services/{service} names {implementation}, which is not a production "
                         f"type of {module}")
                declaring = owners.get(service)
                if declaring is not None and declaring != providing:
                    edges.setdefault((declaring, providing), set()).add(service)
    return {edge: tuple(sorted(services)) for edge, services in sorted(edges.items())}


def runtime_edge_row(declaring: str, providing: str, services: tuple[str, ...]) -> str:
    return f"| `{declaring}` | `{providing}` | {', '.join(f'`{service}`' for service in services)} |"


def render_dependency_document(graph: dict[str, frozenset[str]], runtime_edges: RuntimeEdges | None = None) -> str:
    runtime_edges = runtime_edges or {}
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
    for declaring, providing in runtime_edges:
        lines.append(f"    {mermaid_id(declaring)} -.-> {mermaid_id(providing)}")
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
        "## Arêtes d'exécution par service",
        "",
        "Un module déclare un service (`ServiceLoader`) qu'un autre module fournit par un fichier",
        "`META-INF/services` : le premier charge le second à l'exécution, sans dépendance Maven dans ce sens.",
        "Ces arêtes sont en pointillés dans le diagramme et ne comptent pas dans la politique de dépendances.",
        "",
        "| Module qui déclare | Module qui fournit | Services |",
        "|---|---|---|",
    ])
    lines.extend(runtime_edge_row(declaring, providing, services)
                 for (declaring, providing), services in runtime_edges.items())
    if not runtime_edges:
        lines.append("| — | — | — |")
    lines.extend([
        "",
        "Le sens d'une flèche pleine est **module → dépendance directe** ; une flèche en pointillés va",
        "du module qui déclare un service au module qui le fournit. Les dépendances transitives ne sont pas répétées.",
        "Le mode normal du checker échoue si ce fichier n'est plus exactement aligné avec les POMs et les fichiers",
        "`META-INF/services` courants.",
        "",
    ])
    return "\n".join(lines)


def check_or_write_dependency_document(graph: dict[str, frozenset[str]], write_doc: bool,
                                       document: Path = GENERATED_DEPENDENCY_DOC,
                                       runtime_edges: RuntimeEdges | None = None) -> None:
    runtime_edges = runtime_edges or {}
    expected = render_dependency_document(graph, runtime_edges)
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
    missing = [f"{declaring} -> {providing}" for (declaring, providing), services in runtime_edges.items()
               if runtime_edge_row(declaring, providing, services) not in actual.splitlines()]
    if missing:
        fail("generated module dependency documentation misses the execution edge(s) " + ", ".join(missing)
             + "; run: python scripts/architecture/check-module-boundaries.py --write-doc")
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
        postgresql_blind = check_postgresql_backend_isolation()
        exported = check_runtime_local_exports()
        engine_types = check_engine_types_named_by_surfaces()
        launchers = check_process_launchers()
        adapter_types = check_adapter_types_named_by_adapters()
        backend_types = check_backend_types_named_across_packages()
        package_roots = check_package_roots()
        service_loaders = check_service_loader_class_loaders()
        snapshot_view_users = check_snapshot_read_port()
        unreferenced = check_unreferenced_production_types()
        packages = check_package_ownership()
        cycles, cycle_packages = check_package_cycles()
        documented = check_architecture_documentation(graph)
        architecture_test = check_architecture_test_policy()
        runtime_edges = runtime_service_edges()
        check_or_write_dependency_document(graph, write_doc, runtime_edges=runtime_edges)
        for module in MODULES:
            dependencies = ",".join(sorted(graph[module])) or "-"
            print(f"M21 module-boundary {module}: sources={counts[module]} dependencies={dependencies}")
        print(f"A3 package ownership: packages={packages}, each owned by exactly one module")
        print(f"A8 package cycles: {cycles} known cycles over "
              f"{sum(len(members) for members, _ in KNOWN_PACKAGE_CYCLES.values())} packages, none new "
              f"({cycle_packages} packages analysed, plugin included)")
        print(f"A9 architecture document: {documented} module sections match the POM dependencies")
        print(f"A17 ArchUnit policy: {architecture_test} modules, ModuleArchitectureTest.ALLOWED equals ALLOWED_DEPENDENCIES")
        print(f"A10 runtime-local exports: {exported} types of the guarded packages may be named by other "
              f"modules, none other is")
        print("A11 surface -> minos-engine types: "
              + ", ".join(f"{surface}={count} ({sum(ENGINE_TYPES_NAMED_BY_SURFACE.get(surface, {}).values())} files)"
                          for surface, count in engine_types.items())
              + " listed in ENGINE_TYPES_NAMED_BY_SURFACE (names and MinosApplication accessors), none other is used")
        print(f"A12 process launchers: {launchers} class(es) of domain, engine and application listed with "
              f"{sum(PROCESS_LAUNCHERS.values())} launch site(s), none other launches a process")
        print(f"A13 adapter -> adapter types: {adapter_types} listed in ADAPTER_TYPES_NAMED_BY_ADAPTER "
              f"({sum(sum(types.values()) for types in ADAPTER_TYPES_NAMED_BY_ADAPTER.values())} files), none other "
              f"is named")
        print(f"R1/R2 PostgreSQL backend isolation: {postgresql_blind} modules name neither the backend package nor "
              "its driver")
        print(f"D4 storage backends: {backend_types} types of the local backend listed in "
              f"BACKEND_TYPES_NAMED_ACROSS_PACKAGES ({sum(BACKEND_TYPES_NAMED_ACROSS_PACKAGES.values())} files), none "
              "other is named by the PostgreSQL backend, and the local backend names none of it")
        print(f"A14 package roots: {package_roots} roots over {len(MODULES)} modules, no new root")
        print(f"AUD-ARC-11 execution edges by service: "
              + (", ".join(f"{a} -> {b}" for a, b in runtime_edges) or "none") + " (documented)")
        print(f"A16 service discovery: {service_loaders} production ServiceLoader.load calls, each with an "
              f"explicit class loader")
        print(f"A18 snapshot read port: {snapshot_view_users} classes listed in LEGACY_SNAPSHOT_VIEW_USERS use the "
              f"deprecated view, only minos-engine com.minos.store names InMemoryCodeKnowledgeStore, minos-application "
              f"names no CodeKnowledgeStore")
        print(f"A15 unreferenced production types: {unreferenced} listed in UNREFERENCED_PRODUCTION_TYPES, "
              f"every other public type is named by production")
        print(
            f"M21 MODULE BOUNDARY CONSISTENCY SUCCESS "
            f"(modules={len(MODULES)}, sources={total}, dependencyPolicy=explicit-v1, hexagonalPolicy=A2-ADR-0042, "
            f"packagePolicy=A3-ADR-0068, cyclePolicy=A8-ratchet, documentation=A9-arc42-05, "
            f"reactor=root-pom-modules)"
        )
        return 0
    except Exception as exception:
        print(f"M21 MODULE BOUNDARY CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

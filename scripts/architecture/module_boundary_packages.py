"""Package and reactor rules of check-module-boundaries.py: A15 unreferenced production
types, the Java source layout, A7 reactor modules, A3 package ownership and A8 package cycles.

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from module_boundary_policy import MODULES, NS, ROOT, fail, repository_group_id
from module_boundary_sources import (
    PACKAGE, SOURCE_IMPORT, declared_package, name_reading_code, production_sources, qualified_name_pattern, read_java)


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
    "minos-api/com.minos.api.LocalSemanticExplorationApi":
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
    "minos-storage/com.minos.storage.codec.CodeKnowledgeSnapshotBinaryCodec":
        "declared snapshot codec boundary, excluded by AUD-QUA-02 itself",
}
TOP_LEVEL_PUBLIC_TYPE = re.compile(
    r"^public\s+(?:(?:abstract|final|sealed|non-sealed|static|strictfp)\s+)*"
    r"(?:class|interface|enum|record|@interface)\s+([A-Z]\w*)", re.MULTILINE)
MAIN_METHOD = re.compile(r"\bpublic\s+static\s+void\s+main\s*\(")


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
    for module, source in production_sources(root, MODULES):
        if source.name in ("package-info.java", "module-info.java"):
            continue
        text = read_java(source)
        match = PACKAGE.search(text)
        parsed.append((module, source, match.group(1) if match else "", name_reading_code(text, source)))
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


def require_written_reasons(declared: dict[str, str]) -> None:
    """A15 — every entry of the closed list carries its reason."""
    unreasoned = sorted(entry for entry, reason in declared.items() if not reason.strip())
    if unreasoned:
        fail("A15 UNREFERENCED_PRODUCTION_TYPES entries need a written reason: " + ", ".join(unreasoned))


def declared_production_entries(root: Path) -> set[str]:
    """module/package.Type of every production source of the reactor."""
    existing = set()
    for module, source in production_sources(root, MODULES):
        match = PACKAGE.search(read_java(source))
        existing.add(f"{module}/{match.group(1) if match else ''}.{source.stem}")
    return existing


def check_unreferenced_production_types(root: Path = ROOT,
                                        declared: dict[str, str] = UNREFERENCED_PRODUCTION_TYPES) -> int:
    """A15 — every top-level public type of a reactor src/main is named by production, or listed with a reason."""
    require_written_reasons(declared)
    found = unreferenced_production_types(root)
    undeclared = sorted(entry for entry in found if entry not in declared)
    if undeclared:
        fail("A15 public production types without a production caller (delete them, move them to tests, or list "
             "them in UNREFERENCED_PRODUCTION_TYPES with a reason): " + ", ".join(undeclared))
    existing = declared_production_entries(root)
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

                text = read_java(source)
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

    A module added to the reactor and forgotten here would escape every rule of the guard, POM and
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


# A3 / ADR 0068 — one package, one module. A Java package belongs to exactly one module: no package is
# declared by the production sources of two modules, and no test is declared in a package whose production
# sources live in another module. Either would let package-private visibility cross a jar boundary. No split
# is tolerated (see check_package_ownership).
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
            code = name_reading_code(read_java(source), source)
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

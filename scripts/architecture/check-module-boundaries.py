#!/usr/bin/env python3
"""Enforce MINOS source ownership, Maven dependency directions, and generated architecture facts.

The governed module list is checked against the root reactor (A7), and every Java package belongs to
exactly one module (A3 / ADR 0068). Architecture debt is held by ratchets (A8, A10 to A15, A18); the generated
dependency document also shows the execution edges created by META-INF/services files.

This script holds the POM rules (A2 dependency policy, hexagonal families, hidden internal dependencies) and runs
every rule. The other rules live next to it: module_boundary_policy (module-boundaries.json), module_boundary_sources
(Java source reading), module_boundary_adapters (adapter, storage and assembly isolation), module_boundary_ratchets
(A11 to A14, A16, A18), module_boundary_packages (A3, A7, A8, A15) and module_boundary_documents (A9, A17 and the
generated dependency document).
Self-test: scripts/architecture/test_check_module_boundaries*.py.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from gate_runner import run_gate
from module_boundary_policy import (
    ADAPTER_MODULES, ALLOWED_DEPENDENCIES, APPLICATION_MODULE, ARTIFACT_TO_MODULE, ASSEMBLY_MODULE, BOOTSTRAP_MODULE,
    CONTRACT_MODULES, CONTRACT_MODULE_DEPENDENCIES, CONTRACT_TO_BOOTSTRAP_SCOPES, MODULES, NS, ROOT, SURFACE_MODULES,
    SURFACE_TO_BOOTSTRAP_SCOPES, TEST_ONLY_DEPENDENCIES, fail, repository_group_id)
from module_boundary_adapters import (
    check_assembly_classes, check_embedded_adapters, check_postgresql_backend_isolation, check_runtime_local_exports,
    check_source_boundaries, check_storage_package_isolation)
from module_boundary_ratchets import (
    ADAPTER_COUPLING_REASONS, ADAPTER_TYPES_NAMED_BY_ADAPTER, ENGINE_MODULE, ENGINE_TYPES_NAMED_BY_SURFACE,
    PROCESS_LAUNCHERS, check_adapter_types_named_by_adapters, check_engine_types_named_by_surfaces,
    check_minos_application_accessors, check_package_roots, check_process_launchers, check_service_loader_class_loaders,
    check_snapshot_read_port, engine_concrete_code)
from module_boundary_packages import (
    KNOWN_PACKAGE_CYCLES, check_java_layout, check_package_cycles, check_package_ownership, check_reactor_modules,
    check_unreferenced_production_types)
from module_boundary_documents import (
    check_architecture_documentation, check_architecture_test_policy, check_or_write_dependency_document,
    runtime_service_edges)


def parse_pom(module: str, root: Path = ROOT) -> ET.Element:
    pom = root / module / "pom.xml"
    if not pom.is_file():
        fail(f"{module}: missing pom.xml")
    return ET.parse(pom).getroot()


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


def check_application_test_jar_scope(module: str, root: ET.Element, group_id: str | None = None) -> None:
    """A2 / AUD-ARC-02, ADR 0087 section 2 — the test-jar of minos-application (MinosApplicationTestAccess, the test
    access to the package-private ports of MinosApplication) is declared in test scope only. Reads <type>, which
    minos_dependencies does not: its merged scopes are those of the edges, unchanged."""
    group_id = group_id or repository_group_id()
    for dependency in root.findall("m:dependencies/m:dependency", NS):
        if (dependency.findtext("m:artifactId", default="", namespaces=NS).strip() != APPLICATION_MODULE
                or dependency.findtext("m:groupId", default="", namespaces=NS).strip() != group_id
                or dependency.findtext("m:type", default="", namespaces=NS).strip() != "test-jar"):
            continue
        scope = (dependency.findtext("m:scope", default="", namespaces=NS) or "compile").strip()
        if scope != "test":
            fail(f"{module}: the test-jar of {APPLICATION_MODULE} is declared in scope {scope}; it carries the test "
                 "access to the ports of MinosApplication and is allowed in test scope only (ADR 0087)")


APPLICATION_TEST_ACCESS = "MinosApplicationTestAccess"


def check_application_test_jar_content(module: str, root: ET.Element, group_id: str | None = None) -> None:
    """A2 / AUD-SEC-12, ADR 0087 section 2 — the POM of minos-application declares one execution of the test-jar goal
    of maven-jar-plugin (build, plugin management and profiles read), whose <includes> holds exactly the class file of
    MinosApplicationTestAccess, with no <excludes>: the test-jar read by the tests of the other modules publishes that
    class alone. The packaging IT ApplicationTestAccessPackagingIT checks the jar actually built."""
    if module != APPLICATION_MODULE:
        return
    group_id = group_id or repository_group_id()
    expected = "/".join(group_id.split(".")) + f"/application/{APPLICATION_TEST_ACCESS}.class"
    pom = f"{APPLICATION_MODULE}/pom.xml"
    executions = []
    for plugin in root.findall(".//m:plugin", NS):
        if plugin.findtext("m:artifactId", default="", namespaces=NS).strip() != "maven-jar-plugin":
            continue
        for execution in plugin.findall("m:executions/m:execution", NS):
            goals = [goal.text.strip() for goal in execution.findall("m:goals/m:goal", NS) if goal.text]
            if "test-jar" in goals:
                executions.append((plugin, execution))
    if len(executions) != 1:
        fail(f"{pom}: {len(executions)} executions of the test-jar goal of maven-jar-plugin; exactly one is allowed, "
             f"including {expected} only (ADR 0087)")
    plugin, execution = executions[0]
    includes_element = execution.find("m:configuration/m:includes", NS)
    if includes_element is None:
        includes_element = plugin.find("m:configuration/m:includes", NS)
    includes = ([(include.text or "").strip() for include in includes_element.findall("m:include", NS)]
                if includes_element is not None else [])
    if includes != [expected]:
        found = ", ".join(includes) if includes_element is not None else "no <includes>"
        fail(f"{pom}: the test-jar includes {found or 'an empty <includes>'}; it publishes {expected} only (ADR 0087)")
    excludes = [(exclude.text or "").strip() for configuration in (execution, plugin)
                for exclude in configuration.findall("m:configuration/m:excludes/m:exclude", NS)]
    if excludes:
        fail(f"{pom}: the test-jar declares <excludes> ({', '.join(excludes)}); it includes {expected} only "
             "(ADR 0087)")


def test_only_dependency_violations(module: str, dependencies: dict[str, str]) -> list[str]:
    """The TEST_ONLY_DEPENDENCIES of one module declared in a scope other than test."""
    return [f"{module} -> {dependency} [{dependencies[dependency]}] (TEST_ONLY_DEPENDENCIES: this dependency is "
            "allowed in test scope only; production code reaches it through engine ports)"
            for owner, dependency in sorted(TEST_ONLY_DEPENDENCIES)
            if owner == module and dependencies.get(dependency, "test") != "test"]


def surface_violations(module: str, dependencies: dict[str, str]) -> list[str]:
    """A2 — a surface reaches no adapter, sees the composition root at runtime or in tests only, and depends on no
    other surface (a contract module is allowed)."""
    violations = [
        f"{module} -> {adapter} [{dependencies[adapter]}] (a surface must reach adapters only "
        "through application ports; the composition root minos-bootstrap wires them)"
        for adapter in sorted(set(dependencies) & ADAPTER_MODULES)]
    bootstrap_scope = dependencies.get(BOOTSTRAP_MODULE)
    if bootstrap_scope is not None and bootstrap_scope not in SURFACE_TO_BOOTSTRAP_SCOPES:
        violations.append(
            f"{module} -> {BOOTSTRAP_MODULE} [{bootstrap_scope}] (a surface may see the composition "
            "root only in runtime or test scope, never at compile time)")
    violations += [
        f"{module} -> {surface} [{dependencies[surface]}] (a surface never depends on another surface; "
        "a translation shared by surfaces belongs to a contract module)"
        for surface in sorted((set(dependencies) & SURFACE_MODULES) - {module})]
    return violations


def contract_module_violations(module: str, dependencies: dict[str, str]) -> list[str]:
    """A2 — a contract module depends only on CONTRACT_MODULE_DEPENDENCIES, and on the composition root in test
    scope only."""
    violations = []
    for dependency, scope in sorted(dependencies.items()):
        if dependency == BOOTSTRAP_MODULE:
            if scope not in CONTRACT_TO_BOOTSTRAP_SCOPES:
                violations.append(
                    f"{module} -> {BOOTSTRAP_MODULE} [{scope}] (a contract module may see the composition "
                    "root only in test scope)")
        elif dependency not in CONTRACT_MODULE_DEPENDENCIES:
            violations.append(
                f"{module} -> {dependency} [{scope}] (a contract module depends only on "
                f"{', '.join(sorted(CONTRACT_MODULE_DEPENDENCIES))}; never on a surface, an adapter, "
                "the engine or the assembly)")
    return violations


def check_hexagonal_boundaries(scoped: dict[str, dict[str, str]]) -> None:
    """A2 / ADR 0042 — explicit, scope-aware hexagonal rules (all scopes unless stated)."""
    roles = (ADAPTER_MODULES | SURFACE_MODULES | CONTRACT_MODULES
             | {APPLICATION_MODULE, BOOTSTRAP_MODULE, ASSEMBLY_MODULE}
             | {name for couple in TEST_ONLY_DEPENDENCIES for name in couple})
    unknown = roles - set(MODULES)
    if unknown:
        fail(f"hexagonal policy references unknown modules: {', '.join(sorted(unknown))}")
    violations: list[str] = []
    for module in MODULES:
        dependencies = scoped[module]
        adapters = sorted(set(dependencies) & ADAPTER_MODULES)
        violations += test_only_dependency_violations(module, dependencies)
        if module == APPLICATION_MODULE:
            violations += [
                f"{module} -> {adapter} (the application layer must depend only on ports; "
                "wire the adapter from the composition root minos-bootstrap)" for adapter in adapters]
        elif module in SURFACE_MODULES:
            violations += surface_violations(module, dependencies)
        elif module in CONTRACT_MODULES:
            violations += contract_module_violations(module, dependencies)
        elif module in ADAPTER_MODULES:
            for forbidden in sorted({APPLICATION_MODULE, BOOTSTRAP_MODULE, ASSEMBLY_MODULE} | SURFACE_MODULES
                                    | CONTRACT_MODULES):
                if forbidden in dependencies:
                    violations.append(
                        f"{module} -> {forbidden} (an adapter must implement engine/domain ports and never "
                        "depend on the application, the composition root, a contract module or a surface)")
        elif module == BOOTSTRAP_MODULE:
            for upper in sorted(set(dependencies) & (SURFACE_MODULES | CONTRACT_MODULES | {ASSEMBLY_MODULE})):
                violations.append(f"{module} -> {upper} (the composition root sits below the contract modules "
                                  "and the surfaces)")
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


def check_pom_rules(root: Path = ROOT) -> dict[str, dict[str, str]]:
    """Every POM rule over the governed modules; returns the scoped internal dependencies of each module."""
    group_id = repository_group_id(root)
    poms = {module: parse_pom(module, root) for module in MODULES}
    for module, pom in poms.items():
        check_pom_layout(module, pom)
        check_no_hidden_internal_dependencies(module, pom, group_id)
        check_application_test_jar_scope(module, pom, group_id)
        check_application_test_jar_content(module, pom, group_id)
    scoped = {module: minos_dependencies(module, pom, group_id) for module, pom in poms.items()}
    check_dependency_policy(scoped)
    return scoped


def writes_dependency_document(arguments: list[str]) -> bool:
    """Refuses an unknown argument; returns whether the dependency document is to be written."""
    unknown = [argument for argument in arguments if argument != "--write-doc"]
    if unknown:
        fail(f"unknown arguments: {', '.join(unknown)}")
    return "--write-doc" in arguments


def run_rules(write_doc: bool) -> dict:
    """Runs every rule in order; returns the counters the report prints."""
    check_reactor_modules()
    scoped = check_pom_rules()
    graph = {module: frozenset(dependencies) for module, dependencies in scoped.items()}
    total, counts = check_java_layout()
    check_source_boundaries()
    postgresql_blind = check_postgresql_backend_isolation()
    exported = check_runtime_local_exports()
    engine_types = check_engine_types_named_by_surfaces()
    engine_accessors = check_minos_application_accessors()
    launchers = check_process_launchers()
    adapter_types = check_adapter_types_named_by_adapters()
    assembly_classes = check_assembly_classes()
    api_users, embedded_adapters = check_embedded_adapters()
    storage_packages = check_storage_package_isolation()
    package_roots = check_package_roots()
    service_loaders = check_service_loader_class_loaders()
    snapshot_view_users = check_snapshot_read_port()
    unreferenced = check_unreferenced_production_types()
    packages = check_package_ownership()
    cycles, cycle_packages = check_package_cycles()
    documented = check_architecture_documentation(scoped)
    architecture_test = check_architecture_test_policy()
    runtime_edges = runtime_service_edges()
    check_or_write_dependency_document(scoped, write_doc, runtime_edges=runtime_edges)
    return {name: value for name, value in locals().items() if name != "write_doc"}


def print_module_report(results: dict) -> None:
    """Prints the module, family, package, document and architecture test lines."""
    graph, counts, packages, cycles, cycle_packages, documented, architecture_test = (
        results[name] for name in (
            "graph", "counts", "packages", "cycles", "cycle_packages", "documented", "architecture_test"))
    for module in MODULES:
        dependencies = ",".join(sorted(graph[module])) or "-"
        print(f"M21 module-boundary {module}: sources={counts[module]} dependencies={dependencies}")
    print(f"A2 module families: adapters={len(ADAPTER_MODULES)}, contract modules={len(CONTRACT_MODULES)} "
          f"({', '.join(sorted(CONTRACT_MODULES))}), surfaces={len(SURFACE_MODULES)}, no surface depends on "
          "another surface")
    print(f"A3 package ownership: packages={packages}, each owned by exactly one module")
    print(f"A8 package cycles: {cycles} known cycles over "
          f"{sum(len(members) for members, _ in KNOWN_PACKAGE_CYCLES.values())} packages, none new "
          f"({cycle_packages} packages analysed, plugin included)")
    print(f"A9 architecture document: {documented} module sections match the POM dependencies")
    print(f"A17 ArchUnit policy: ModuleArchitectureTest reads the single source module-boundaries.json "
          f"({architecture_test} modules), no policy table is copied")


def print_ratchet_report(results: dict) -> None:
    """Prints the runtime export, engine type, accessor, launcher and adapter coupling lines."""
    exported, engine_types, engine_accessors, launchers, adapter_types = (
        results[name] for name in (
            "exported", "engine_types", "engine_accessors", "launchers", "adapter_types"))
    print(f"A10 runtime-local exports: {exported} types of the guarded packages may be named by other "
          f"modules, none other is")
    print("A11 surface and contract module -> minos-engine types: "
          + ", ".join(f"{surface}={count} ({sum(ENGINE_TYPES_NAMED_BY_SURFACE.get(surface, {}).values())} files)"
                      for surface, count in engine_types.items())
          + " listed in ENGINE_TYPES_NAMED_BY_SURFACE (names and MinosApplication accessors), none other is used")
    print(f"A11 MinosApplication engine accessors: {engine_accessors} public accessor(s) return a minos-engine "
          "type, all listed in MINOS_APPLICATION_ENGINE_ACCESSORS")
    print(f"A12 process launchers: {launchers} class(es) of domain, engine and application listed with "
          f"{sum(PROCESS_LAUNCHERS.values())} launch site(s), none other launches a process")
    concrete = engine_concrete_code()
    engine_lines = sum(lines for _, lines in concrete.values())
    class_files, class_lines = concrete.get("class", (0, 0))
    print(f"AUD-ARC-05 {ENGINE_MODULE} concrete code (measured, not ratcheted; ADR 0068): {class_files} class "
          f"files, {class_lines} of {engine_lines} lines ({100 * class_lines / max(engine_lines, 1):.1f}%); "
          + ", ".join(f"{kind}={files}/{lines}" for kind, (files, lines) in sorted(concrete.items())))
    print(f"A13 adapter -> adapter types: {adapter_types} listed in ADAPTER_TYPES_NAMED_BY_ADAPTER "
          f"({sum(sum(types.values()) for types in ADAPTER_TYPES_NAMED_BY_ADAPTER.values())} files), none other "
          f"is named; {len(ADAPTER_COUPLING_REASONS)} couple(s) justified in ADAPTER_COUPLING_REASONS")


def print_rule_report(results: dict) -> None:
    """Prints the assembly, embedded adapter, storage, package root, service and snapshot rule lines."""
    (postgresql_blind, assembly_classes, api_users, embedded_adapters, storage_packages, package_roots,
     service_loaders, snapshot_view_users, unreferenced, runtime_edges) = (
        results[name] for name in (
            "postgresql_blind", "assembly_classes", "api_users", "embedded_adapters", "storage_packages",
            "package_roots", "service_loaders", "snapshot_view_users", "unreferenced", "runtime_edges"))
    print(f"A20 assembly classes: {assembly_classes} production class(es) of minos-app listed in ASSEMBLY_CLASSES "
          f"with their role, none other")
    print(f"A19 embedded adapters: {api_users} file(s) of minos-application listed in INFRASTRUCTURE_API_USERS "
          f"name a JDK infrastructure API, {embedded_adapters} class(es) listed in EMBEDDED_ADAPTER_CLASSES, named "
          f"by no other production source of minos-application")
    print(f"R1/R2 PostgreSQL backend isolation: {postgresql_blind} modules name neither the backend package nor "
          "its driver")
    print(f"Storage packages: {storage_packages} rules over production and test sources, the two backends name "
          "each other nowhere, the codec and the path mapping name neither backend nor JDBC, no other module "
          "declares a package below com.minos.storage")
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


def run_checks() -> str:
    """Runs every rule, prints the report of each on stdout and returns the final success line."""
    results = run_rules(writes_dependency_document(sys.argv[1:]))
    print_module_report(results)
    print_ratchet_report(results)
    print_rule_report(results)
    return (
        f"M21 MODULE BOUNDARY CONSISTENCY SUCCESS "
        f"(modules={len(MODULES)}, sources={results['total']}, dependencyPolicy=explicit-v1, hexagonalPolicy=A2-ADR-0042, "
        f"packagePolicy=A3-ADR-0068, cyclePolicy=A8-ratchet, documentation=A9-arc42-05, "
        f"reactor=root-pom-modules)"
    )


def main() -> int:
    return run_gate("M21 MODULE BOUNDARY CONSISTENCY", run_checks)


if __name__ == "__main__":
    raise SystemExit(main())

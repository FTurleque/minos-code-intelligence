"""Documentation rules of check-module-boundaries.py: A9 module sections of the arc42 block
view, A17 ArchUnit policy reading, and the generated module dependency document (execution edges by service
included).

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files.
"""

from __future__ import annotations

import re
from pathlib import Path

from module_boundary_policy import ALLOWED_DEPENDENCIES, ASSEMBLY_MODULE, MODULES, ROOT, fail
from module_boundary_sources import COMMENTS_AND_LITERALS, production_types
from module_boundary_ratchets import PACKAGE_ROOTS_BY_MODULE


GENERATED_DEPENDENCY_DOC = ROOT / "docs" / "architecture" / "diagrams" / "module-dependencies.md"


# A9 / AUD-ARC-03, AUD-ARC-07 — the module sections of the current architecture document are checked against the
# POMs and the sources. The structure is the contract: a `### minos-…` heading, and under it bullets labelled
# "Dépendances" (exactly one), "Sources" (exactly one) and "Interfaces" (at most one). A document that loses that shape
# fails, it does not pass by default (ADR 0043 section 3). Prose elsewhere is not checked.
ARCHITECTURE_BLOCKS_DOC = ROOT / "docs" / "architecture" / "arc42" / "05-vue-blocs.md"
MODULE_HEADING = re.compile(r"^###\s+(minos-[a-z0-9-]+)")
ANY_HEADING = re.compile(r"^#{1,3}\s")
DOCUMENTED_BULLET = re.compile(r"^-\s+\*\*(Dépendances|Sources|Interfaces)\*\*\s*:\s*(.*)$")
MODULE_TOKEN = re.compile(r"`(minos-[a-z0-9-]+)`")
# A dependency token, then optionally a parenthesis that immediately follows it and opens with its Maven scope.
SCOPED_MODULE_TOKEN = re.compile(r"`(minos-[a-z0-9-]+)`(?:\s*\((test|runtime)\b[^)]*\))?")
DOCUMENTED_SCOPES = frozenset({"test", "runtime"})
SOURCE_ROOT_TOKEN = re.compile(r"com/minos/([a-z][a-z0-9]*)/")
INTERFACE_TOKEN = re.compile(r"`([A-Z][A-Za-z0-9]*)`")
ATTRIBUTION = re.compile(r"\bdéfini(?:e|s|es)?\s+dans\b([^.]*)")
PUBLIC_INTERFACE = re.compile(r"^public\s+(?:sealed\s+|non-sealed\s+)?interface\s+([A-Z]\w*)", re.MULTILINE)


def documented_bullets(document: Path) -> dict[str, dict[str, list[str]]]:
    """Module -> label ("Dépendances", "Sources", "Interfaces") -> its bullets, for each `### minos-…` section."""
    sections: dict[str, dict[str, list[str]]] = {}
    current: str | None = None
    for line in document.read_text(encoding="utf-8").splitlines():
        heading = MODULE_HEADING.match(line)
        if heading:
            current = heading.group(1)
            sections.setdefault(current, {"Dépendances": [], "Sources": [], "Interfaces": []})
        elif ANY_HEADING.match(line):
            current = None
        elif current is not None:
            bullet = DOCUMENTED_BULLET.match(line)
            if bullet:
                sections[current][bullet.group(1)].append(bullet.group(2))
    return sections


def documented_dependency_lines(document: Path) -> dict[str, list[str]]:
    """Module -> its "Dépendances" bullets, for each `### minos-…` section of the document."""
    return {module: bullets["Dépendances"] for module, bullets in documented_bullets(document).items()}


def shown_scope(scope: str) -> str | None:
    """The scope a document writes for a dependency: test or runtime; nothing for a compile-time dependency."""
    return scope if scope in DOCUMENTED_SCOPES else None


def scoped_line(expected: dict[str, str]) -> str:
    """The expected "Dépendances" line for these dependencies (module -> Maven scope)."""
    return ", ".join(f"`{name}`" + (f" ({shown_scope(scope)})" if shown_scope(scope) else "")
                     for name, scope in sorted(expected.items())) or "aucune"


def documentation_problem(module: str, lines: list[str], expected: dict[str, str] | set[str],
                          modules: tuple[str, ...]) -> str | None:
    """Why the "Dépendances" bullets of one module section do not match its POM (dependencies and the scope of each
    one declared outside compilation), or None when they do."""
    if not isinstance(expected, dict):
        expected = {name: "compile" for name in expected}
    if len(lines) != 1:
        return f"{module}: expected one 'Dépendances' line in its section, found {len(lines)}"
    text = lines[0]
    if module == ASSEMBLY_MODULE and "tous les modules" in text.casefold():
        missing = set(modules) - {module} - set(expected)
        return f"{module}: documented as depending on all modules, the POM misses {', '.join(sorted(missing))}" \
            if missing else None
    documented: dict[str, str | None] = {}
    for name, scope in SCOPED_MODULE_TOKEN.findall(text):
        if name != module:
            documented[name] = scope or None
    if set(documented) != set(expected):
        return (f"{module}: documented {sorted(documented)}, POM declares {sorted(expected)}; "
                f"expected line: - **Dépendances** : {scoped_line(expected)}")
    wrong = [f"{name} documented with scope {documented[name] or 'none'}, the POM declares it in "
             f"{expected[name]} (expected scope: {shown_scope(expected[name]) or 'none'})"
             for name in sorted(expected) if documented[name] != shown_scope(expected[name])]
    if wrong:
        return f"{module}: " + "; ".join(wrong) + f"; expected line: - **Dépendances** : {scoped_line(expected)}"
    return None


def sources_problem(module: str, lines: list[str], roots: frozenset[str]) -> str | None:
    """Why the "Sources" bullet of a module section does not cite exactly the package roots of the module, or None.
    A line that cites only the source directory of the module, without any root, is accepted."""
    if len(lines) != 1:
        return f"{module}: expected one 'Sources' line in its section, found {len(lines)}"
    cited = set(SOURCE_ROOT_TOKEN.findall(lines[0]))
    if not cited or cited == set(roots):
        return None
    problems = []
    if set(roots) - cited:
        problems.append(f"omits the package root(s) {', '.join(sorted(set(roots) - cited))}")
    if cited - set(roots):
        problems.append(f"cites the package root(s) {', '.join(sorted(cited - set(roots)))} the module does not own")
    expected = ", ".join(f"`com/minos/{root}/`" for root in sorted(roots))
    return f"{module}: its 'Sources' line " + " and ".join(problems) + f" (package roots of the module: {expected})"


def reactor_type_owners(root: Path, modules: tuple[str, ...]) -> dict[str, set[str]]:
    """Simple name of every top-level production type of the reactor -> the modules that declare it."""
    owners: dict[str, set[str]] = {}
    for module in modules:
        for qualified in production_types(root, module):
            owners.setdefault(qualified.rsplit(".", 1)[-1], set()).add(module)
    return owners


def public_interfaces(root: Path, module: str) -> list[str]:
    """The public top-level interfaces declared by the production sources of one module."""
    source_root = root / module / "src" / "main" / "java"
    found: set[str] = set()
    for source in sorted(source_root.rglob("*.java")) if source_root.is_dir() else []:
        found.update(PUBLIC_INTERFACE.findall(COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8"))))
    return sorted(found)


def interfaces_problem(module: str, lines: list[str], owners: dict[str, set[str]],
                       interfaces: list[str]) -> str | None:
    """Why the "Interfaces" bullet of a module section is wrong, or None: every cited type exists and belongs to the
    module or to a module the line names after "défini(e)(s) dans"; "aucune" only when the module declares no public
    interface. A section without such a line is accepted."""
    if not lines:
        return None
    if len(lines) > 1:
        return f"{module}: expected at most one 'Interfaces' line in its section, found {len(lines)}"
    text = lines[0]
    if text.strip().casefold().startswith("aucune"):
        return (f"{module}: its 'Interfaces' line says aucune, the module declares the public interface(s) "
                f"{', '.join(interfaces)}") if interfaces else None
    attributed = {module} | {name for clause in ATTRIBUTION.findall(text) for name in MODULE_TOKEN.findall(clause)}
    problems = []
    for name in dict.fromkeys(INTERFACE_TOKEN.findall(text)):
        declared = owners.get(name)
        if not declared:
            problems.append(f"{name} is no production type of the reactor")
        elif not declared & attributed:
            problems.append(f"{name} is declared by {', '.join(sorted(declared))}, not by "
                            f"{', '.join(sorted(attributed))}")
    return f"{module}: its 'Interfaces' line cites " + "; ".join(problems) if problems else None


def check_architecture_documentation(graph: dict[str, frozenset[str]] | dict[str, dict[str, str]],
                                     document: Path = ARCHITECTURE_BLOCKS_DOC, modules: tuple[str, ...] = MODULES,
                                     root: Path = ROOT,
                                     package_roots: dict[str, frozenset[str]] | None = None) -> int:
    """A9 — in the arc42 block view, every module section documents its direct internal POM dependencies (with the
    scope of those declared outside compilation), its package roots and existing, correctly attributed interfaces."""
    package_roots = PACKAGE_ROOTS_BY_MODULE if package_roots is None else package_roots
    if not document.is_file():
        fail(f"A9 architecture document is missing: {document}")
    sections = documented_bullets(document)
    owners = reactor_type_owners(root, modules)
    problems: list[str] = []
    for module in modules:
        if module not in sections:
            problems.append(f"{module} has no section in {document.name}")
            continue
        bullets = sections[module]
        dependencies = graph[module]
        problems += [problem for problem in (
            documentation_problem(module, bullets["Dépendances"], dependencies, modules),
            sources_problem(module, bullets["Sources"], package_roots.get(module, frozenset())),
            interfaces_problem(module, bullets["Interfaces"], owners, public_interfaces(root, module)),
        ) if problem]
    problems += [f"{document.name} has a section for {module}, which is not a governed module"
                 for module in sorted(set(sections) - set(modules))]
    if problems:
        fail("A9 current architecture documentation diverges from the POMs and the sources: " + "; ".join(problems))
    return len(modules)


# A17 / ADR 0058, AUD-ARC-04 — one policy, one file: ModuleArchitectureTest loads module-boundaries.json through
# ModuleBoundaryPolicy and declares no policy table of its own (no literal table of modules, dependencies or engine
# types assigned to a field); otherwise the ArchUnit rules and this guard could judge the same edge differently.
ARCHITECTURE_TEST_DIRECTORY = ROOT / "minos-app" / "src" / "test" / "java" / "com" / "minos" / "app" / "architecture"
ARCHITECTURE_TEST = ARCHITECTURE_TEST_DIRECTORY / "ModuleArchitectureTest.java"
ARCHITECTURE_POLICY_READER = ARCHITECTURE_TEST_DIRECTORY / "ModuleBoundaryPolicy.java"
POLICY_READ = re.compile(r"\bModuleBoundaryPolicy\s*\.\s*load\s*\(")
POLICY_FILE_NAMED = re.compile(r'"module-boundaries\.json"')
COPIED_TABLE = re.compile(r"\b(?:private\s+)?static\s+final\s+[^;=]*?\b([A-Z][A-Z0-9_]*)\s*=\s*"
                          r'(?:Map\.ofEntries|Map\.of|Set\.of|List\.of)\s*\(\s*(?:Map\.entry\s*\(\s*)?"minos-', re.S)
MODULE_ENTRY = re.compile(r'\bMap\.entry\s*\(\s*"minos-')


def check_architecture_test_policy(test: Path = ARCHITECTURE_TEST, reader: Path = ARCHITECTURE_POLICY_READER) -> int:
    """A17 — ModuleArchitectureTest loads the policy (ModuleBoundaryPolicy.load), whose reader names
    module-boundaries.json, and declares no literal policy table. Fail-closed when either file is missing."""
    for source in (test, reader):
        if not source.is_file():
            fail(f"A17 ArchUnit policy source is missing: {source.name}")
    code = COMMENTS_AND_LITERALS.sub(lambda m: m.group(0) if m.group(0).startswith('"') else " ",
                                     test.read_text(encoding="utf-8"))
    reader_code = COMMENTS_AND_LITERALS.sub(lambda m: m.group(0) if m.group(0).startswith('"') else " ",
                                            reader.read_text(encoding="utf-8"))
    problems = []
    if not POLICY_READ.search(code):
        problems.append(f"{test.name} no longer reads the policy through ModuleBoundaryPolicy.load")
    if not POLICY_FILE_NAMED.search(reader_code):
        problems.append(f"{reader.name} no longer names module-boundaries.json")
    problems += [f"{test.name} declares the policy table {name} again" for name in COPIED_TABLE.findall(code)]
    if MODULE_ENTRY.search(code) and not COPIED_TABLE.search(code):
        problems.append(f"{test.name} declares a literal Map.entry table of modules")
    if problems:
        fail("A17 ArchUnit policy (ADR 0058: one policy, read from scripts/architecture/module-boundaries.json by the "
             "guard and by ModuleArchitectureTest, never copied): " + "; ".join(problems))
    return len(ALLOWED_DEPENDENCIES)


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


def dependency_scopes(graph: dict[str, frozenset[str]] | dict[str, dict[str, str]]) -> dict[str, dict[str, str]]:
    """Module -> dependency -> Maven scope; a graph without scopes means compile-time dependencies."""
    return {module: dict(dependencies) if isinstance(dependencies, dict)
            else {name: "compile" for name in dependencies} for module, dependencies in graph.items()}


def render_dependency_document(graph: dict[str, frozenset[str]] | dict[str, dict[str, str]],
                               runtime_edges: RuntimeEdges | None = None) -> str:
    runtime_edges = runtime_edges or {}
    scopes = dependency_scopes(graph)
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
        for dependency, scope in sorted(scopes[module].items()):
            label = f"|{shown_scope(scope)}|" if shown_scope(scope) else ""
            lines.append(f"    {mermaid_id(module)} -->{label} {mermaid_id(dependency)}")
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
        dependencies = ", ".join(f"`{dependency}`" + (f" ({shown_scope(scope)})" if shown_scope(scope) else "")
                                 for dependency, scope in sorted(scopes[module].items())) or "—"
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


def check_or_write_dependency_document(graph: dict[str, frozenset[str]] | dict[str, dict[str, str]], write_doc: bool,
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

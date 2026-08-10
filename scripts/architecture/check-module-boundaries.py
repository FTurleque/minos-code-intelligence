#!/usr/bin/env python3
"""Enforce MINOS source ownership, Maven dependency directions, and generated architecture facts."""

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
    "minos-nexus",
    "minos-cli",
    "minos-api",
    "minos-mcp",
    "minos-app",
)

# Explicitly allowed direct MINOS dependencies. This is a maximum set, not a requirement:
# removing a dependency is allowed, adding a dependency outside this policy is blocked.
ALLOWED_DEPENDENCIES: dict[str, frozenset[str]] = {
    "minos-domain": frozenset(),
    "minos-engine": frozenset({"minos-domain"}),
    "minos-runtime-local": frozenset({"minos-engine"}),
    "minos-storage-local": frozenset({"minos-engine"}),
    "minos-storage-postgresql": frozenset({
        "minos-domain", "minos-engine", "minos-storage-local", "minos-application"
    }),
    "minos-provider-scip": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local"
    }),
    "minos-integration-git": frozenset({"minos-engine"}),
    "minos-application": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local",
        "minos-provider-scip", "minos-integration-git"
    }),
    "minos-nexus": frozenset({"minos-domain", "minos-application", "minos-storage-local"}),
    "minos-cli": frozenset({
        "minos-domain", "minos-engine", "minos-application", "minos-integration-git",
        "minos-storage-local", "minos-provider-scip", "minos-runtime-local", "minos-nexus"
    }),
    "minos-api": frozenset({
        "minos-domain", "minos-engine", "minos-application", "minos-storage-local",
        "minos-integration-git"
    }),
    "minos-mcp": frozenset({"minos-application"}),
    "minos-app": frozenset({
        "minos-domain", "minos-engine", "minos-runtime-local", "minos-storage-local",
        "minos-storage-postgresql", "minos-provider-scip", "minos-integration-git",
        "minos-application", "minos-nexus", "minos-cli", "minos-api", "minos-mcp"
    }),
}

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


def minos_dependencies(module: str, root: ET.Element) -> frozenset[str]:
    dependencies: set[str] = set()
    for dependency in root.findall("m:dependencies/m:dependency", NS):
        if dependency.findtext("m:groupId", default="", namespaces=NS) != "com.minos":
            continue
        artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
        target = ARTIFACT_TO_MODULE.get(artifact)
        if target is None:
            fail(f"{module}: unknown internal artifact dependency com.minos:{artifact}")
        if target == module:
            fail(f"{module}: self-dependency is forbidden")
        dependencies.add(target)
    return frozenset(dependencies)


def check_dependency_policy(graph: dict[str, frozenset[str]]) -> None:
    if set(ALLOWED_DEPENDENCIES) != set(MODULES):
        fail("dependency policy must cover every reactor module exactly once")

    for module, dependencies in graph.items():
        forbidden = dependencies - ALLOWED_DEPENDENCIES[module]
        if forbidden:
            fail(f"{module}: forbidden MINOS dependencies: {', '.join(sorted(forbidden))}")

    # Guard the core regardless of future whitelist edits.
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
                if match:
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

        roots = {module: parse_pom(module) for module in MODULES}
        for module, root in roots.items():
            check_pom_layout(module, root)
        graph = {module: minos_dependencies(module, root) for module, root in roots.items()}
        check_dependency_policy(graph)
        total, counts = check_java_layout()
        check_or_write_dependency_document(graph, write_doc)
        for module in MODULES:
            dependencies = ",".join(sorted(graph[module])) or "-"
            print(f"M21 module-boundary {module}: sources={counts[module]} dependencies={dependencies}")
        print(
            f"M21 MODULE BOUNDARY CONSISTENCY SUCCESS "
            f"(modules={len(MODULES)}, sources={total}, dependencyPolicy=explicit-v1)"
        )
        return 0
    except Exception as exception:
        print(f"M21 MODULE BOUNDARY CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

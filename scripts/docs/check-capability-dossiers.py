#!/usr/bin/env python3
"""Keep the durable capability dossiers consistent with the OpenSpec reference specs.

A capability dossier is `docs/architecture/diagrams/capacites/<capability>.md`: the Mermaid views,
the traceability table and the gaps of the capability `openspec/specs/<capability>/spec.md`
(schema `detailed-uml`, guide `docs/developer/openspec-detailed-uml.md`). Rules:

1. Every dossier names an existing capability (file name = spec directory name).
2. The spec of a capability that has a dossier ends with a `## Documentation liee` section (accented
   in the file) placed after `## Requirements`, holding no `###` heading, and linking to the dossier.
   `openspec archive` keeps that trailing section; a `###` inside it would be read as a requirement.
3. The dossier links back to its spec.
4. Every `## Vues` sub-section (`### `) holds a `Type :` line and exactly one ```mermaid block whose
   first line is a known Mermaid diagram keyword.
5. The `## Tracabilite` table (accented in the file) has one row per requirement of the spec, first
   cell = exact requirement title; no row names an unknown requirement and no requirement is missing.
6. The status cell of a row is one of STATUSES. A row marked `couverte` or `partielle` cites at least
   one proof in backticks that resolves to a file of the repository (`path`, `path:line` or
   `path#member`); every cited path that looks like a repository path must exist.
7. Every relative Markdown link of a dossier resolves to an existing file.
8. The dossier directory exists with its README.md index (a missing target fails, it never passes by
   default, GEN-0043), and a cited proof path holds no `..` segment.
9. A proof cited as `path#member` names a member that appears in that file.

What this gate does not prove: that a proof actually tests the requirement, that a diagram is
semantically right or renders (Mermaid syntax is checked only by its first keyword), nor that a
capability without a dossier is complete. `--report` prints the coverage of every capability.

Self-test: scripts/docs/test_check_capability_dossiers.py.
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
SPECS = Path("openspec/specs")
DOSSIERS = Path("docs/architecture/diagrams/capacites")
LINKED_SECTION = "## Documentation liée"
TRACE_SECTION = "## Traçabilité"
VIEWS_SECTION = "## Vues"
STATUSES = ("couverte", "partielle", "manquante", "non vérifiée")
PROVEN_STATUSES = ("couverte", "partielle")
MERMAID_KEYWORDS = (
    "classDiagram", "sequenceDiagram", "stateDiagram-v2", "stateDiagram", "flowchart", "graph",
    "erDiagram", "C4Context", "C4Container", "C4Component", "C4Deployment", "journey", "gantt",
    "requirementDiagram", "block-beta", "architecture-beta", "classDiagram-v2", "pie", "mindmap",
    "timeline", "gitGraph", "quadrantChart", "sankey-beta", "xychart-beta", "packet-beta", "kanban",
)
REQUIREMENT = re.compile(r"^### Requirement:\s*(.+?)\s*$")
SCENARIO = re.compile(r"^#### Scenario:")
LINK = re.compile(r"\]\(([^)\s]+)\)")
CODE = re.compile(r"`([^`]+)`")
# A repository path: a known top-level entry followed by a slash, or a file of the repository root.
REPO_PATH = re.compile(r"^(?:(?:minos-[\w-]+|scripts|docs|openspec|packaging|docker|fixtures|benchmarks|quality|\.github|\.claude)/"
                       r"|[\w.-]+\.(?:xml|kts|json|ya?ml|md|toml|properties)$)")


@dataclass
class Spec:
    name: str
    requirements: dict[str, int] = field(default_factory=dict)  # title -> scenario count
    text: str = ""


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def parse_spec(path: Path) -> Spec:
    spec = Spec(path.parent.name, text=read(path))
    current: str | None = None
    for line in spec.text.splitlines():
        match = REQUIREMENT.match(line)
        if match:
            current = match.group(1)
            spec.requirements[current] = 0
        elif SCENARIO.match(line) and current is not None:
            spec.requirements[current] += 1
        elif line.startswith("## "):
            current = None
    return spec


def section(text: str, heading: str) -> list[str] | None:
    """Lines of the `## ` section `heading` (without the heading), or None when absent."""
    lines = text.splitlines()
    if heading not in lines:
        return None
    start = lines.index(heading) + 1
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
    return lines[start:end]


def table_rows(lines: list[str]) -> list[list[str]]:
    rows = [line for line in lines if line.strip().startswith("|")]
    cells = [[cell.strip() for cell in row.strip().strip("|").split("|")] for row in rows]
    return [row for row in cells[2:] if any(row)] if len(cells) >= 2 else []


def clean_title(cell: str) -> str:
    """The requirement title of a cell; a title may itself start or end with a code span (`name` ...)."""
    title = cell.strip().strip("«»").strip()
    if len(title) > 1 and title.startswith("`") and title.endswith("`") and title.count("`") == 2:
        return title[1:-1]
    return title


def proof_paths(cell: str) -> list[str]:
    paths = []
    for token in CODE.findall(cell):
        candidate = re.split(r"[:#]", token.strip(), maxsplit=1)[0]
        if REPO_PATH.match(candidate):
            paths.append(candidate)
    return paths


def proof_members(cell: str) -> list[tuple[str, str]]:
    """The `path#member` proofs of a cell, as (path, member)."""
    members = []
    for token in CODE.findall(cell):
        path, _, member = token.strip().partition("#")
        if member and REPO_PATH.match(path):
            members.append((path, member))
    return members


def check_members(root: Path, title: str, cells: list[str], label: str) -> list[str]:
    """A cited member must appear in its existing file: a renamed or invented test is not a proof."""
    failures = []
    for path, member in (pair for cell in cells for pair in proof_members(cell)):
        file = root / path
        if ".." in Path(path).parts or not file.is_file():
            continue
        if member not in read(file):
            failures.append(f"{label}: requirement `{title}` cites `{member}`, absent from `{path}`")
    return failures


def check_links(owner: Path, text: str, label: str) -> list[str]:
    failures = []
    for target in LINK.findall(text):
        if re.match(r"^[a-z]+:", target) or target.startswith("#"):
            continue
        file_part = target.split("#", 1)[0]
        if file_part and not (owner.parent / file_part).resolve().exists():
            failures.append(f"{label}: broken link `{target}`")
    return failures


def view_blocks(views: list[str]) -> list[tuple[str, list[str]]]:
    """The `### ` sub-sections of the views section, as (title, body lines)."""
    blocks: list[tuple[str, list[str]]] = []
    for line in views:
        if line.startswith("### "):
            blocks.append((line[4:].strip(), []))
        elif blocks:
            blocks[-1][1].append(line)
    return blocks


def first_diagram_line(body: list[str], start: int) -> str:
    """First meaningful line of the block, past comments (`%%`) and a YAML front matter (`---` ... `---`)."""
    lines = [line.strip() for line in body[start + 1:] if line.strip() and not line.strip().startswith("%%")]
    if lines and lines[0] == "---":
        closing = next((i for i in range(1, len(lines)) if lines[i] == "---"), None)
        lines = lines[closing + 1:] if closing is not None else []
    return lines[0] if lines else ""


def check_view(title: str, body: list[str], label: str) -> list[str]:
    failures = []
    if not any(line.startswith("Type :") for line in body):
        failures.append(f"{label}: view `{title}` has no `Type :` line")
    starts = [i for i, line in enumerate(body) if line.strip() == "```mermaid"]
    if len(starts) != 1:
        return failures + [f"{label}: view `{title}` must hold exactly one mermaid block, found {len(starts)}"]
    first = first_diagram_line(body, starts[0])
    if first.split(" ")[0] not in MERMAID_KEYWORDS:
        failures.append(f"{label}: view `{title}` starts with `{first[:30]}`, not a Mermaid diagram keyword")
    return failures


def check_mermaid_views(text: str, label: str) -> list[str]:
    views = section(text, VIEWS_SECTION)
    if views is None:
        return [f"{label}: no `{VIEWS_SECTION}` section"]
    blocks = view_blocks(views)
    if not blocks:
        return [f"{label}: `{VIEWS_SECTION}` has no `### ` view"]
    return [failure for title, body in blocks for failure in check_view(title, body, label)]


def is_valid_proof(root: Path, path: str) -> bool:
    return ".." not in Path(path).parts and (root / path).exists()


def check_proofs(root: Path, title: str, status: str, paths: list[str], label: str) -> list[str]:
    failures = []
    for path in paths:
        if ".." in Path(path).parts:
            failures.append(f"{label}: requirement `{title}` cites proof `{path}` with a `..` segment")
        elif not (root / path).exists():
            failures.append(f"{label}: requirement `{title}` cites missing proof `{path}`")
    if status in PROVEN_STATUSES and not any(is_valid_proof(root, path) for path in paths):
        failures.append(f"{label}: requirement `{title}` is `{status}` without an existing proof path")
    return failures


def check_row(root: Path, row: list[str], label: str) -> list[str]:
    """One traceability row whose requirement is known: its status and its proofs."""
    title = clean_title(row[0])
    status = row[-1].strip().lower() if len(row) > 1 else ""
    failures = []
    if status not in STATUSES:
        failures.append(f"{label}: requirement `{title}` has status `{status}`, expected one of {', '.join(STATUSES)}")
    paths = [path for cell in row[1:-1] for path in proof_paths(cell)]
    return failures + check_proofs(root, title, status, paths, label) + check_members(root, title, row[1:-1], label)


def check_traceability(root: Path, spec: Spec, text: str, label: str) -> list[str]:
    lines = section(text, TRACE_SECTION)
    if lines is None:
        return [f"{label}: no `{TRACE_SECTION}` section"]
    failures = []
    seen: set[str] = set()
    for row in table_rows(lines):
        title = clean_title(row[0])
        if title not in spec.requirements:
            failures.append(f"{label}: traceability names unknown requirement `{title}`")
            continue
        seen.add(title)
        failures += check_row(root, row, label)
    failures += [f"{label}: requirement `{title}` is missing from the traceability table"
                 for title in spec.requirements if title not in seen]
    return failures


def check_spec_link(spec: Spec, spec_path: Path, dossier: Path, label: str) -> list[str]:
    lines = spec.text.splitlines()
    if LINKED_SECTION not in lines:
        return [f"{label}: spec has no `{LINKED_SECTION}` section linking to its dossier"]
    failures = []
    if "## Requirements" in lines and lines.index(LINKED_SECTION) < lines.index("## Requirements"):
        failures.append(f"{label}: `{LINKED_SECTION}` must follow `## Requirements`")
    body = section(spec.text, LINKED_SECTION) or []
    if any(line.startswith("###") for line in body):
        failures.append(f"{label}: `{LINKED_SECTION}` must hold no `###` heading")
    targets = {(spec_path.parent / t.split("#", 1)[0]).resolve() for t in LINK.findall("\n".join(body))}
    if dossier.resolve() not in targets:
        failures.append(f"{label}: `{LINKED_SECTION}` does not link to {dossier.name}")
    return failures


def load_specs(root: Path) -> dict[str, Spec]:
    directory = root / SPECS
    if not directory.is_dir():
        return {}
    return {path.parent.name: parse_spec(path) for path in sorted(directory.glob("*/spec.md"))}


def check(root: Path) -> tuple[list[str], int, int]:
    specs = load_specs(root)
    failures: list[str] = []
    if not specs:
        failures.append(f"no spec found under {SPECS.as_posix()}")
    if not (root / DOSSIERS / "README.md").is_file():
        failures.append(f"{(DOSSIERS / 'README.md').as_posix()} not found: the dossier index is missing")
    dossiers = sorted(p for p in (root / DOSSIERS).glob("*.md") if p.name != "README.md") if (root / DOSSIERS).is_dir() else []
    for dossier in dossiers:
        name = dossier.stem
        label = (DOSSIERS / dossier.name).as_posix()
        if name not in specs:
            failures.append(f"{label}: no capability `{name}` under {SPECS.as_posix()}")
            continue
        spec = specs[name]
        spec_path = root / SPECS / name / "spec.md"
        text = read(dossier)
        failures += check_spec_link(spec, spec_path, dossier, (SPECS / name / "spec.md").as_posix())
        links = {(dossier.parent / t.split("#", 1)[0]).resolve() for t in LINK.findall(text)}
        if spec_path.resolve() not in links:
            failures.append(f"{label}: dossier does not link back to its spec")
        failures += check_mermaid_views(text, label)
        failures += check_traceability(root, spec, text, label)
        failures += check_links(dossier, text, label)
    return failures, len(dossiers), len(specs)


def report(root: Path) -> str:
    specs = load_specs(root)
    rows = ["| Capacité | Exigences | Scénarios | Exigences à un seul scénario | Dossier durable |",
            "|---|---|---|---|---|"]
    for name, spec in specs.items():
        single = sum(1 for count in spec.requirements.values() if count == 1)
        has_dossier = "oui" if (root / DOSSIERS / f"{name}.md").is_file() else "non"
        rows.append(f"| `{name}` | {len(spec.requirements)} | {sum(spec.requirements.values())} | {single} | {has_dossier} |")
    return "\n".join(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--report", action="store_true", help="print the coverage table of every capability")
    args = parser.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    if args.report:
        print(report(args.root))
        return 0
    failures, dossiers, specs = check(args.root)
    for failure in failures:
        print(f"CAPABILITY DOSSIERS FAILED: {failure}", file=sys.stderr)
    if failures:
        return 1
    print(f"Capability dossiers consistent: {dossiers} dossier(s) for {specs} capability spec(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())

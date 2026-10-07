#!/usr/bin/env python3
"""Summarise the reports of the audit profiles (docs/quality/code-audit.md).

    python scripts/quality/audit-report-summary.py spotbugs
    python scripts/quality/audit-report-summary.py pit --module minos-engine

``spotbugs`` lists, per module, the findings of ``spotbugsXml.xml`` by priority and pattern. It exits 1 when no
module has a report: a run that produced nothing is not a clean run.

``pit`` reads ``target/pit-reports/mutations.xml`` of one module. It exits 1 when the report is missing, holds no
mutation, or no mutation was ever confronted with a test (every mutant has no coverage). PIT itself only fails on
that when ``failWhenNoMutations`` is true, which the reactor command has to switch off for the upstream modules
brought by ``-am``; this check is what makes an empty analysis visible again. It never judges the score: PIT has no
global threshold in MINOS before the first scope has been measured.
"""
from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRIORITY = {"1": "High", "2": "Medium", "3": "Low"}


def module_dirs(root: Path) -> list[Path]:
    """The reactor modules, minos-app writing into the root target/ (its <directory> is shared)."""
    modules = sorted(path.parent for path in root.glob("minos-*/pom.xml"))
    return modules


def report_for(module: Path, root: Path, name: str) -> Path:
    own = module / "target" / name
    # minos-app builds into <root>/target (see its pom.xml), so its reports are not under minos-app/.
    return own if own.is_file() or module.name != "minos-app" else root / "target" / name


def spotbugs(root: Path, top: int) -> int:
    total: Counter[tuple[str, str]] = Counter()
    found = 0
    for module in module_dirs(root):
        report = report_for(module, root, "spotbugsXml.xml")
        if not report.is_file():
            continue
        found += 1
        bugs = ET.parse(report).getroot().findall("BugInstance")
        by_priority = Counter(PRIORITY.get(bug.get("priority", ""), "?") for bug in bugs)
        print(f"{module.name:28} {len(bugs):4}  " + ", ".join(f"{k}={v}" for k, v in sorted(by_priority.items())))
        total.update((bug.get("type", "?"), PRIORITY.get(bug.get("priority", ""), "?")) for bug in bugs)
    if not found:
        print("No spotbugsXml.xml found: run the audit-spotbugs profile first.", file=sys.stderr)
        return 1
    print(f"{'TOTAL':28} {sum(total.values()):4}")
    for (pattern, priority), count in total.most_common(top):
        print(f"  {count:4}  {priority:6} {pattern}")
    return 0


def percent(part: int, whole: int) -> int:
    """Rounded like PIT's own console summary."""
    return (part * 200 + whole) // (whole * 2)


def pit(root: Path, module_name: str) -> int:
    module = root / module_name
    report = report_for(module, root, "pit-reports/mutations.xml")
    if not report.is_file():
        print(f"{report}: missing. PIT did not run on {module_name}.", file=sys.stderr)
        return 1
    mutations = ET.parse(report).getroot().findall("mutation")
    status = Counter(mutation.get("status", "?") for mutation in mutations)
    if not mutations:
        print(f"{report}: no mutation generated. Check targetClasses.", file=sys.stderr)
        return 1
    covered = len(mutations) - status["NO_COVERAGE"]
    if covered == 0:
        print(f"{report}: {len(mutations)} mutations but none covered by a test. Check targetTests.", file=sys.stderr)
        return 1
    killed = status["KILLED"] + status["TIMED_OUT"] + status["MEMORY_ERROR"] + status["RUN_ERROR"]
    print(f"{module_name}: {len(mutations)} mutations, " + ", ".join(f"{k}={v}" for k, v in sorted(status.items())))
    print(f"mutation score {percent(killed, len(mutations))}%, test strength {percent(killed, covered)}% "
          f"(killed or timed out / covered)")
    per_class: Counter[tuple[str, str]] = Counter()
    for mutation in mutations:
        name = (mutation.findtext("mutatedClass") or "?").rsplit(".", 1)[-1]
        per_class[(name, mutation.get("status", "?"))] += 1
    for (name, state), count in sorted(per_class.items()):
        print(f"  {name:36} {state:12} {count}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    sb = commands.add_parser("spotbugs")
    sb.add_argument("--top", type=int, default=15, help="patterns listed (default 15)")
    pit_command = commands.add_parser("pit")
    pit_command.add_argument("--module", required=True, help="module whose target/pit-reports is read, e.g. minos-engine")
    args = parser.parse_args()
    if args.command == "spotbugs":
        return spotbugs(args.root, args.top)
    return pit(args.root, args.module)


if __name__ == "__main__":
    sys.exit(main())

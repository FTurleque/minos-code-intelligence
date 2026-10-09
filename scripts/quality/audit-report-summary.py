#!/usr/bin/env python3
"""Summarise the reports of the audit profiles (docs/quality/code-audit.md).

    python scripts/quality/audit-report-summary.py spotbugs
    python scripts/quality/audit-report-summary.py pit --module minos-engine
    python scripts/quality/audit-report-summary.py pit --all
    python scripts/quality/audit-report-summary.py dependency-check target/dependency-check/dependency-check-report.json

``spotbugs`` lists, per module, the findings of ``spotbugsXml.xml`` by priority and pattern, and the number of
classes SpotBugs actually analysed (``ClassStats`` entries) against the ``.class`` files of the module's output
directory. It exits 1 when a module that has compiled classes has no report, or when a report analysed fewer
classes than the module compiled: a run that skipped a module, or analysed part of it, is not a clean run.

``pit`` reads ``target/pit-reports/mutations.xml`` of one module (``--module``) or of every module (``--all``). It
exits 1 when a report is missing, holds no mutation, or no mutation was ever confronted with a test (every mutant
has no coverage). PIT itself only fails on that when ``failWhenNoMutations`` is true, which the reactor command has
to switch off for modules without mutable code; this check is what makes an empty analysis visible again. It never
judges the score. Timeouts, memory and run errors are listed apart from kills: PIT counts them as detected, but they
prove that a mutant broke the test JVM, not that an assertion caught it.

``dependency-check`` reads a Dependency-Check JSON report and prints the data-source timestamps (freshness), the
analysis exceptions, the number of dependencies and the vulnerabilities. It exits 1 on any analysis exception: an
error is never read as "no vulnerability".
"""
from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRIORITY = {"1": "High", "2": "Medium", "3": "Low"}
DETECTED_BY_ASSERTION = ("KILLED",)
DETECTED_BY_CRASH = ("TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR")


def module_dirs(root: Path) -> list[Path]:
    """The reactor modules, minos-app writing into the root target/ (its <directory> is shared)."""
    modules = sorted(path.parent for path in root.glob("minos-*/pom.xml"))
    return modules


def target_of(module: Path, root: Path) -> Path:
    # minos-app builds into <root>/target (see its pom.xml), so its outputs are not under minos-app/.
    return root / "target" if module.name == "minos-app" else module / "target"


def report_for(module: Path, root: Path, name: str) -> Path:
    return target_of(module, root) / name


def compiled_classes(module: Path, root: Path) -> int:
    classes = target_of(module, root) / "classes"
    return sum(1 for _ in classes.rglob("*.class")) if classes.is_dir() else 0


def spotbugs(root: Path, top: int, reports_dir: Path | None, strict: bool) -> int:
    """``reports_dir`` reads ``<module>.xml`` written by the SpotBugs command line instead of the Maven reports.

    The XML of spotbugs-maven-plugin 4.10.4.1 carries no class statistics (``total_classes="0"``, no
    ``ClassStats``): it cannot prove which classes were analysed. Such a module is reported UNPROVEN; ``--strict``
    turns UNPROVEN into a failure. The command-line XML (``-xml:withMessages``) carries one ``ClassStats`` per class.
    """
    total: Counter[tuple[str, str]] = Counter()
    problems: list[str] = []
    unproven: list[str] = []
    print(f"{'module':28} {'classes':>7} {'analysed':>8} {'bugs':>5}  priorities")
    for module in module_dirs(root):
        compiled = compiled_classes(module, root)
        report = (reports_dir / f"{module.name}.xml") if reports_dir else report_for(module, root, "spotbugsXml.xml")
        if not report.is_file():
            print(f"{module.name:28} {compiled:7} {'-':>8} {'-':>5}  NO REPORT")
            if compiled:
                problems.append(f"{module.name}: {compiled} compiled classes but no report at {report}")
            continue
        tree = ET.parse(report).getroot()
        bugs = tree.findall("BugInstance")
        analysed = len(tree.findall(".//ClassStats"))
        errors = tree.find("Errors")
        by_priority = Counter(PRIORITY.get(bug.get("priority", ""), "?") for bug in bugs)
        shown = f"{analysed:8}" if analysed else f"{'n/a':>8}"
        print(f"{module.name:28} {compiled:7} {shown} {len(bugs):5}  "
              + ", ".join(f"{k}={v}" for k, v in sorted(by_priority.items())))
        if analysed == 0 and compiled:
            unproven.append(module.name)
        elif analysed < compiled:
            problems.append(f"{module.name}: {analysed} classes analysed for {compiled} compiled")
        if errors is not None and (errors.get("errors", "0") != "0" or errors.get("missingClasses", "0") != "0"):
            problems.append(f"{module.name}: {errors.get('errors')} analysis errors, "
                            f"{errors.get('missingClasses')} missing classes")
        total.update((bug.get("type", "?"), PRIORITY.get(bug.get("priority", ""), "?")) for bug in bugs)
    print(f"{'TOTAL':28} {'':7} {'':8} {sum(total.values()):5}")
    for (pattern, priority), count in total.most_common(top):
        print(f"  {count:4}  {priority:6} {pattern}")
    if unproven:
        print(f"UNPROVEN: no class statistics in the report of {', '.join(unproven)}; the analysed scope is not "
              "shown by these reports (replay with the SpotBugs command line, docs/quality/code-audit.md)",
              file=sys.stderr)
        if strict:
            problems.append("analysed scope unproven")
    for problem in problems:
        print(f"INCOMPLETE: {problem}", file=sys.stderr)
    return 1 if problems else 0


def percent(part: int, whole: int) -> int:
    """Rounded like PIT's own console summary."""
    return (part * 200 + whole) // (whole * 2) if whole else 0


def unusable_pit_report(module_name: str, mutations: list, status: Counter[str]) -> str | None:
    """Why a PIT report proves nothing about the tests, or None when it does."""
    if not mutations:
        return f"{module_name}: no mutation generated. Check targetClasses."
    if len(mutations) == status["NO_COVERAGE"]:
        return f"{module_name}: {len(mutations)} mutations but none covered by a test. Check targetTests."
    return None


def print_pit_per_class(mutations: list) -> None:
    per_class: Counter[tuple[str, str]] = Counter()
    for mutation in mutations:
        name = (mutation.findtext("mutatedClass") or "?").rsplit(".", 1)[-1]
        per_class[(name, mutation.get("status", "?"))] += 1
    for (name, state), count in sorted(per_class.items()):
        print(f"  {name:36} {state:12} {count}")


def pit_module(root: Path, module_name: str, quiet: bool) -> tuple[int, Counter[str]]:
    report = report_for(root / module_name, root, "pit-reports/mutations.xml")
    if not report.is_file():
        print(f"{module_name}: {report} missing. PIT did not run on {module_name}.", file=sys.stderr)
        return 1, Counter()
    mutations = ET.parse(report).getroot().findall("mutation")
    status = Counter(mutation.get("status", "?") for mutation in mutations)
    problem = unusable_pit_report(module_name, mutations, status)
    if problem:
        print(problem, file=sys.stderr)
        return 1, status
    covered = len(mutations) - status["NO_COVERAGE"]
    killed = sum(status[s] for s in DETECTED_BY_ASSERTION)
    crashed = sum(status[s] for s in DETECTED_BY_CRASH)
    classes = {mutation.findtext("mutatedClass") for mutation in mutations}
    print(f"{module_name}: {len(mutations)} mutations in {len(classes)} classes, "
          + ", ".join(f"{k}={v}" for k, v in sorted(status.items())))
    print(f"  killed by an assertion {percent(killed, len(mutations))}% of all, {percent(killed, covered)}% of covered;"
          f" timeouts and errors {crashed} (not counted as kills here)")
    if not quiet:
        print_pit_per_class(mutations)
    return 0, status


def pit(root: Path, module_name: str | None, every: bool) -> int:
    if not every:
        return pit_module(root, module_name, quiet=False)[0]
    failures = 0
    total: Counter[str] = Counter()
    for module in module_dirs(root):
        if compiled_classes(module, root) == 0:
            print(f"{module.name}: no compiled production class, nothing to mutate")
            continue
        rc, status = pit_module(root, module.name, quiet=True)
        failures += rc
        total.update(status)
    print("TOTAL " + ", ".join(f"{k}={v}" for k, v in sorted(total.items())))
    return 1 if failures else 0


def vulnerability_rows(dependencies: list[dict]) -> list[tuple[str, str, str]]:
    """(package, vulnerability, severity) for every vulnerability, with absent JSON fields as '?'."""
    rows = []
    for dependency in dependencies:
        packages = [p.get("id") for p in dependency.get("packages", []) if p.get("id")]
        package = packages[0] if packages else dependency.get("fileName") or "?"
        for vulnerability in dependency.get("vulnerabilities", []):
            rows.append((package, vulnerability.get("name") or "?", (vulnerability.get("severity") or "?").upper()))
    return rows


def dependency_check(report: Path) -> int:
    if not report.is_file():
        print(f"{report}: missing. Dependency-Check did not write a report.", file=sys.stderr)
        return 1
    scan_report = json.loads(report.read_text(encoding="utf-8"))
    scan = scan_report["scanInfo"]
    print(f"engine {scan.get('engineVersion')}")
    for source in scan.get("dataSource", []):
        print(f"  {source.get('name')}: {source.get('timestamp')}")
    exceptions = scan.get("analysisExceptions") or []
    dependencies = scan_report.get("dependencies", [])
    print(f"dependencies {len(dependencies)}, analysis exceptions {len(exceptions)}")
    rows = vulnerability_rows(dependencies)
    severities = Counter(severity for _, _, severity in rows)
    print("vulnerabilities " + (", ".join(f"{k}={v}" for k, v in sorted(severities.items())) or "none"))
    for package, name, severity in sorted(rows):
        print(f"  {severity:9} {name:22} {package}")
    for exception in exceptions:
        print(f"ANALYSIS EXCEPTION: {exception.get('exception', {}).get('message', exception)}", file=sys.stderr)
    return 1 if exceptions else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    sb = commands.add_parser("spotbugs")
    sb.add_argument("--top", type=int, default=15, help="patterns listed (default 15)")
    sb.add_argument("--reports-dir", type=Path, help="directory of <module>.xml from the SpotBugs command line")
    sb.add_argument("--strict", action="store_true", help="fail when a report cannot prove the analysed scope")
    pit_command = commands.add_parser("pit")
    target = pit_command.add_mutually_exclusive_group(required=True)
    target.add_argument("--module", help="module whose target/pit-reports is read, e.g. minos-engine")
    target.add_argument("--all", action="store_true", help="every module with compiled production classes")
    dependency_check_command = commands.add_parser("dependency-check")
    dependency_check_command.add_argument("report", type=Path, help="dependency-check-report.json")
    args = parser.parse_args()
    if args.command == "spotbugs":
        return spotbugs(args.root, args.top, args.reports_dir, args.strict)
    if args.command == "pit":
        return pit(args.root, args.module, args.all)
    return dependency_check(args.report)


if __name__ == "__main__":
    sys.exit(main())

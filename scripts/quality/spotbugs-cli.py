#!/usr/bin/env python3
"""Replay SpotBugs from the command line on every reactor module (MINOS-AUD-H01).

    python scripts/quality/spotbugs-cli.py --output target/spotbugs-cli
    python scripts/quality/audit-report-summary.py spotbugs --reports-dir target/spotbugs-cli --strict

The Maven plugin's ``spotbugsXml.xml`` records no ``ClassStats``, so it cannot prove which classes were analysed.
The command line with ``-xml:withMessages`` records one ``ClassStats`` per analysed class. This script runs it with
the settings of the ``audit-spotbugs`` profile (SpotBugs ``spotbugs.version``, effort max, threshold medium,
``quality/spotbugs-exclude.xml``) on each module's ``target/classes``, with the module's dependencies as auxiliary
classpath, and writes ``<output>/<module>.xml``. It compiles the reactor itself and exits 1 if any run fails.
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUX_CLASSPATH = "spotbugs-auxclasspath.txt"


def summary_helpers():
    spec = importlib.util.spec_from_file_location("audit_report_summary", ROOT / "scripts/quality/audit-report-summary.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def mvnw() -> list[str]:
    return [str(ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")), "--batch-mode", "--no-transfer-progress", "--quiet"]


def spotbugs_version() -> str:
    match = re.search(r"<spotbugs\.version>([^<]+)</spotbugs\.version>", (ROOT / "pom.xml").read_text(encoding="utf-8"))
    if not match:
        raise SystemExit("spotbugs.version is not declared in pom.xml")
    return match.group(1)


def spotbugs_classpath(version: str, work: Path) -> str:
    """Resolves SpotBugs and its dependencies through Maven, with the repository's own Maven settings."""
    pom = work / "pom.xml"
    pom.write_text(f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>local.audit</groupId><artifactId>spotbugs-cli</artifactId><version>1</version>
  <dependencies>
    <dependency><groupId>com.github.spotbugs</groupId><artifactId>spotbugs</artifactId><version>{version}</version></dependency>
  </dependencies>
</project>
""", encoding="utf-8")
    output = work / "spotbugs.classpath"
    subprocess.run(mvnw() + ["-f", str(pom), "dependency:build-classpath", f"-Dmdep.outputFile={output}"],
                   cwd=ROOT, check=True)
    return output.read_text(encoding="utf-8").strip()


def write_auxiliary_classpaths() -> None:
    """One ``target/spotbugs-auxclasspath.txt`` per module, written by a reactor build that also compiles.

    ``compile`` in the same invocation makes Maven resolve sibling modules to their fresh ``target/classes``; a
    separate run would resolve them to the jars installed in the local repository, possibly stale.
    """
    subprocess.run(mvnw() + ["compile", "dependency:build-classpath", "-Dmdep.includeScope=compile",
                             f"-Dmdep.outputFile=target/{AUX_CLASSPATH}"], cwd=ROOT, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=Path, default=ROOT / "target/spotbugs-cli")
    args = parser.parse_args()
    helpers = summary_helpers()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    write_auxiliary_classpaths()
    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="minos-spotbugs-") as temporary:
        work = Path(temporary)
        classpath = spotbugs_classpath(spotbugs_version(), work)
        for module in helpers.module_dirs(ROOT):
            target = helpers.target_of(module, ROOT)
            classes = target / "classes"
            if not classes.is_dir():
                continue
            # minos-app writes into the root target/, where the reactor build writes the parent's file too.
            module_classpath_file = (module / "target" / AUX_CLASSPATH) if module.name == "minos-app" else target / AUX_CLASSPATH
            aux_classpath = work / f"{module.name}.auxclasspath"
            entries = module_classpath_file.read_text(encoding="utf-8").strip().split(os.pathsep) if module_classpath_file.is_file() else []
            aux_classpath.write_text("\n".join(entry for entry in entries if entry) + "\n", encoding="utf-8")
            result = subprocess.run(
                ["java", "-Xmx2g", "-cp", classpath, "edu.umd.cs.findbugs.FindBugs2",
                 "-effort:max", "-medium", "-xml:withMessages",
                 "-output", str(output / f"{module.name}.xml"),
                 "-auxclasspathFromFile", str(aux_classpath),
                 "-exclude", str(ROOT / "quality/spotbugs-exclude.xml"),
                 str(classes)],
                capture_output=True, text=True)
            print(f"{module.name}: exit {result.returncode}")
            if result.returncode != 0:
                failures.append(module.name)
                print(result.stdout[-2000:] + result.stderr[-2000:], file=sys.stderr)
    if failures:
        print(f"SpotBugs command line failed for: {', '.join(failures)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

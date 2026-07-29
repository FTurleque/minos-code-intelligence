#!/usr/bin/env python3
"""Cross-platform shaded-CLI proof for explicitly partial M26 runtime intelligence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "target" / "minos-code-intelligence-0.2.0-SNAPSHOT-all.jar"
FIXTURE = ROOT / "scripts" / "m26" / "M26RuntimeFixture.java"
SHA1 = re.compile(r"[0-9a-f]{40}")


def run(command: list[str], env: dict[str, str], *, expect_success: bool = True) -> subprocess.CompletedProcess[str]:
    print("+ " + " ".join(command), flush=True)
    completed = subprocess.run(
        command, cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, timeout=600, check=False,
    )
    if completed.stdout:
        print(completed.stdout, end="" if completed.stdout.endswith("\n") else "\n", flush=True)
    if completed.stderr:
        print(completed.stderr, end="" if completed.stderr.endswith("\n") else "\n", file=sys.stderr, flush=True)
    if expect_success and completed.returncode != 0:
        raise RuntimeError(f"command failed with exit {completed.returncode}: {' '.join(command)}")
    if not expect_success and completed.returncode == 0:
        raise RuntimeError(f"command unexpectedly succeeded: {' '.join(command)}")
    return completed


def cli(env: dict[str, str], *arguments: str) -> dict[str, object]:
    completed = run(["java", "-jar", str(JAR), *arguments], env)
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as exception:
        raise RuntimeError(f"invalid CLI JSON for {' '.join(arguments)}: {exception}") from exception
    if not isinstance(value, dict):
        raise RuntimeError(f"CLI returned non-object JSON for {' '.join(arguments)}")
    return value


def rejected_cli(env: dict[str, str], *arguments: str) -> str:
    completed = run(["java", "-jar", str(JAR), *arguments], env, expect_success=False)
    return (completed.stderr or completed.stdout).strip()


def require(actual: object, expected: object, label: str) -> None:
    if actual != expected:
        raise RuntimeError(f"{label}: expected={expected!r} actual={actual!r}")


def envelope(project_id: str, *, completeness: str = "PARTIAL", hits: int = 5) -> str:
    return "\n".join((
        "minos-runtime-observation-v1",
        "session\trun-m26-e2e",
        f"project\t{project_id}",
        "snapshot\tsnapshot-m26-e2e",
        "started\t2026-07-29T06:00:00Z",
        "ended\t2026-07-29T06:05:00Z",
        "collector\tm26-fixture\t1.0.0",
        "environment\texact-head-local",
        f"completeness\t{completeness}",
        f"symbol\tkey:service\tcom.acme.Service\tsrc/Service.java\t1\t{hits}\t500",
        "call\tkey:service\tcom.acme.Service\tsrc/Service.java\t1\tkey:helper\tcom.acme.Helper\tsrc/Helper.java\t1\t3\t200",
        "line\tsrc/Service.java\t1\t4",
        "symbol\t\tcom.acme.Duplicate\t\t\t2\t10",
        "symbol\t\tcom.acme.Missing\t\t\t1\t0",
        "",
    ))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        expected = args.expected_head.lower()
        if not SHA1.fullmatch(expected):
            raise RuntimeError("--expected-head must be a lowercase complete Git SHA-1")
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        require(actual, expected, "local exact HEAD")
        if not JAR.is_file():
            raise RuntimeError(f"shaded CLI jar is missing: {JAR}")

        with tempfile.TemporaryDirectory(prefix="minos-m26-runtime-e2e-") as raw:
            temporary = Path(raw)
            home = temporary / "home"
            project = temporary / "project"
            classes = temporary / "classes"
            classes.mkdir(parents=True)
            env = os.environ.copy()
            env["MINOS_HOME"] = str(home)
            env["MINOS_SEMANTIC_PROVIDER"] = "disabled"
            for name in ("MINOS_SEMANTIC_MODEL", "MINOS_SEMANTIC_DIMENSIONS",
                         "MINOS_SEMANTIC_ENDPOINT", "MINOS_SEMANTIC_TIMEOUT_SECONDS"):
                env.pop(name, None)

            run(["javac", "-cp", str(JAR), "-d", str(classes), str(FIXTURE)], env)
            classpath = str(JAR) + os.pathsep + str(classes)
            setup = run(["java", "-cp", classpath, "M26RuntimeFixture", str(home), str(project)], env)
            project_id = setup.stdout.strip().splitlines()[-1]
            if not re.fullmatch(r"[0-9a-f-]{36}", project_id):
                raise RuntimeError("fixture did not produce a project UUID")

            source = temporary / "runtime.tsv"
            source.write_text(envelope(project_id), encoding="utf-8", newline="")
            imported = cli(env, "runtime", "import", "m26-runtime-e2e", "--file", str(source), "--format", "json")
            idempotent = cli(env, "runtime", "import", "m26-runtime-e2e", "--file", str(source), "--format", "json")
            sessions = cli(env, "runtime", "sessions", "m26-runtime-e2e", "--format", "json")
            report = cli(env, "runtime", "report", "m26-runtime-e2e", "--session", "run-m26-e2e", "--format", "json")
            symbol = cli(env, "runtime", "symbol", "m26-runtime-e2e", "--symbol", "service",
                         "--session", "run-m26-e2e", "--format", "json")

            require(imported.get("nature"), "OBSERVED_PARTIAL", "import nature")
            require(imported.get("exhaustive"), False, "import exhaustiveness")
            require(imported.get("resolvedReferences"), 4, "resolved correlation count")
            require(imported.get("ambiguousReferences"), 1, "ambiguous correlation count")
            require(imported.get("unresolvedReferences"), 1, "unresolved correlation count")
            require(idempotent.get("alreadyPresent"), True, "idempotent import")
            session_values = sessions.get("sessions")
            if not isinstance(session_values, list) or len(session_values) != 1:
                raise RuntimeError("runtime sessions must contain exactly the imported session")
            require(session_values[0].get("activeSnapshotAligned"), True, "session snapshot alignment")
            require(session_values[0].get("completeness"), "PARTIAL", "session completeness")
            require(report.get("snapshotId"), "snapshot-m26-e2e", "authoritative snapshot")
            require(report.get("staticSymbolCount"), 4, "static symbol count")
            require(report.get("observedSymbolCount"), 2, "observed symbol count")
            require(report.get("observedSymbolRatio"), 0.5, "observed correlation ratio")
            require(report.get("coveredLineCount"), 1, "covered line count")
            require(report.get("exhaustive"), False, "report exhaustiveness")
            require(symbol.get("executionHits"), 5, "symbol execution hits")
            require(symbol.get("coveredLineHits"), 4, "symbol line hits")
            require(symbol.get("absenceMeaning"), "NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS", "absence semantics")

            complete = temporary / "complete.tsv"
            complete.write_text(envelope(project_id, completeness="COMPLETE"), encoding="utf-8", newline="")
            complete_error = rejected_cli(
                env, "runtime", "import", "m26-runtime-e2e", "--file", str(complete), "--format", "json")
            if "completeness must be PARTIAL" not in complete_error:
                raise RuntimeError("non-partial completeness did not fail closed with the expected reason")
            mutation = temporary / "mutation.tsv"
            mutation.write_text(envelope(project_id, hits=6), encoding="utf-8", newline="")
            mutation_error = rejected_cli(
                env, "runtime", "import", "m26-runtime-e2e", "--file", str(mutation), "--format", "json")
            if "immutable" not in mutation_error:
                raise RuntimeError("session identity mutation did not fail closed")

            evidence = {
                "status": "PASS",
                "platform": f"{platform.system()}-{platform.machine()}",
                "commit": expected,
                "format": "minos-runtime-observation-v1",
                "nature": "OBSERVED_PARTIAL",
                "exhaustive": False,
                "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "session": {"id": "run-m26-e2e", "completeness": "PARTIAL", "activeSnapshotAligned": True},
                "correlation": {"resolved": 4, "ambiguous": 1, "unresolved": 1},
                "staticSnapshot": {"id": "snapshot-m26-e2e", "symbols": 4, "observedSymbols": 2},
                "observed": {"lines": 1, "executionHits": 5, "coveredLineHits": 4},
                "failClosed": {"completeRejected": True, "sessionMutationRejected": True},
            }
            output = ROOT / args.output
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
            print("M26 RUNTIME E2E EVIDENCE: " + json.dumps(evidence, sort_keys=True))
            print("M26 RUNTIME DYNAMIC END-TO-END SUCCESS")
            return 0
    except Exception as exception:
        print(f"M26 RUNTIME DYNAMIC END-TO-END FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

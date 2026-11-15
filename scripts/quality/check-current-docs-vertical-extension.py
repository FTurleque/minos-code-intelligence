#!/usr/bin/env python3
"""Run the current MINOS documentation/release contract gate plus M28 invariants."""

from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import literal_gate  # noqa: E402 - after the script directory is added to the path

ROOT = Path(__file__).resolve().parents[2]
CURRENT = ROOT / "scripts/docs/check-current-docs.py"
FAILURE_PREFIX = "M28 CURRENT DOCUMENTATION CONSISTENCY FAILED"


def load_current():
    spec = importlib.util.spec_from_file_location("minos_current_docs", CURRENT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load current documentation gate: {CURRENT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require(path: str, expected: str) -> None:
    text = literal_gate.read(path, "missing M28 evidence: {path}")
    literal_gate.require(path, text, expected, f"{path}: missing M28 evidence: {expected}", str.casefold)


def evaluate_with(current) -> str:
    """The facts of the current documentation gate (its evaluation, not its command line), then the M28 ones."""
    try:
        current_message = current.evaluate()
    except Exception as exception:
        raise literal_gate.ReportedFailure(f"{current.FAILURE_PREFIX}: {exception}") from exception

    require("docs/project/roadmap/M28_EXECUTION.md", "Statut final")
    require("docs/project/roadmap/M28_EXECUTION.md", "#93 CLOSED / completed")
    require("docs/project/roadmap/M28_EXECUTION.md", "PR #102 MERGED")
    require("docs/project/roadmap/M28_EXECUTION.md", "#98 OPEN")
    require("docs/developer/remote-worker-sandbox-disposition.md", "WORKER_SANDBOX_CLAIM_PROHIBITED")
    require("docs/developer/remote-worker-sandbox-disposition.md", "BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND")
    require("docs/developer/hosted-production-boundaries.md", "HOSTED_SAAS_OPERATION_NOT_CLAIMED")
    require("docs/developer/hosted-production-boundaries.md", "HostedTransportSecurityPort")
    return f"{current_message}\nM28 CURRENT DOCUMENTATION CONSISTENCY SUCCESS"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="refuse one in-memory mutation per asserted fact (the loaded gate's ones included)")
    self_test = parser.parse_args(argv).self_test
    try:
        current = load_current()
    except Exception as exception:
        print(f"{FAILURE_PREFIX}: {exception}", file=sys.stderr)
        return 1

    def evaluate() -> str:
        return evaluate_with(current)

    if self_test:
        return literal_gate.self_test(ROOT, evaluate, current.STRUCTURED_MUTATIONS)
    return literal_gate.run(ROOT, evaluate, FAILURE_PREFIX)


if __name__ == "__main__":
    raise SystemExit(main())

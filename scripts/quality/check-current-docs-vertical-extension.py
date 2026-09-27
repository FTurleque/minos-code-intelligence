#!/usr/bin/env python3
"""Run the current MINOS documentation/release contract gate plus M28 invariants."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
CURRENT = ROOT / "scripts/docs/check-current-docs.py"


def load_current():
    spec = importlib.util.spec_from_file_location("minos_current_docs", CURRENT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load current documentation gate: {CURRENT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require(path: str, expected: str) -> None:
    file = ROOT / path
    if not file.is_file():
        raise RuntimeError(f"missing M28 evidence: {path}")
    text = file.read_text(encoding="utf-8")
    if expected.casefold() not in text.casefold():
        raise RuntimeError(f"{path}: missing M28 evidence: {expected}")


def main() -> int:
    try:
        current = load_current()
        result = current.main()
        if result != 0:
            return result

        require("docs/roadmap/M28_EXECUTION.md", "Statut final")
        require("docs/roadmap/M28_EXECUTION.md", "#93 CLOSED / completed")
        require("docs/roadmap/M28_EXECUTION.md", "PR #102 MERGED")
        require("docs/roadmap/M28_EXECUTION.md", "#98 OPEN")
        require("docs/developer/remote-worker-sandbox-disposition.md", "WORKER_SANDBOX_CLAIM_PROHIBITED")
        require("docs/developer/remote-worker-sandbox-disposition.md", "BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND")
        require("docs/developer/hosted-production-boundaries.md", "HOSTED_SAAS_OPERATION_NOT_CLAIMED")
        require("docs/developer/hosted-production-boundaries.md", "HostedTransportSecurityPort")

        print("M28 CURRENT DOCUMENTATION CONSISTENCY SUCCESS")
        return 0
    except Exception as exc:
        print(f"M28 CURRENT DOCUMENTATION CONSISTENCY FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

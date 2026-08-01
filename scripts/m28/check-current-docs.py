#!/usr/bin/env python3
"""Run the complete M27 documentation gate with the narrow M28 semantic deltas."""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

# This gate imports the legacy checker dynamically. Qualification must not dirty the worktree
# with scripts/docs/__pycache__ artifacts, so bytecode generation is disabled for this process.
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
LEGACY = ROOT / "scripts/docs/check-current-docs.py"


def load_legacy():
    spec = importlib.util.spec_from_file_location("minos_current_docs_m27", LEGACY)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load legacy documentation gate: {LEGACY}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def require_text(path: Path, expected: str) -> None:
    if not path.is_file():
        raise RuntimeError(f"missing required M28 document: {path.relative_to(ROOT)}")
    text = path.read_text(encoding="utf-8")
    normalized = " ".join(text.replace("`", "").replace("*", "").split()).casefold()
    if " ".join(expected.split()).casefold() not in normalized:
        raise RuntimeError(f"{path.relative_to(ROOT)}: missing expected M28 fact: {expected}")


def main() -> int:
    try:
        legacy = load_legacy()
        original_require_text = legacy.require_text

        def m28_require_text(relative: str, text: str, expected: str) -> None:
            if relative == "docs/STATUS.md" and expected == "Aucun M28 n’est défini":
                original_require_text(relative, text, "M28 — Production Convergence")
                original_require_text(relative, text, "Issue          : #93 OPEN")
                original_require_text(relative, text, "PR             : #96 MERGED")
                original_require_text(relative, text, "Merge develop  : 53d6faa41579d3d01e7900c5c4b65fdcc42c5868")
                original_require_text(relative, text, "S9")
                legacy.forbid_text(relative, text, "Aucun M28 n’est défini")
                legacy.forbid_text(relative, text, "PR             : #96 OPEN / DRAFT")
                return
            if relative == "docs/STATUS.md" and expected == (
                    "rust-analyzer scip 2026-07-27 / v0.3.2989 / commit 12c3381"):
                for token in ("rust-analyzer", "scip 2026-07-27", "v0.3.2989", "commit 12c3381"):
                    original_require_text(relative, text, token)
                return
            if relative == "docs/ROADMAP.md" and expected == "Aucun M28 n’est défini":
                original_require_text(relative, text, "M28 — Production Convergence & Architectural Hardening")
                original_require_text(relative, text, "issue #93")
                original_require_text(relative, text, "roadmap/M28_EXECUTION.md")
                return
            if relative == "JavaSourceProgramGraphProvider.java" and expected == "OriginType.DERIVED_BY_MINOS":
                context = ROOT / (
                    "minos-application/src/main/java/com/minos/program/analysis/"
                    "JavaProgramGraphContext.java")
                original_require_text(
                    "JavaProgramGraphContext.java",
                    context.read_text(encoding="utf-8"),
                    expected,
                )
                return
            original_require_text(relative, text, expected)

        legacy.require_text = m28_require_text
        result = legacy.main()
        if result != 0:
            return result

        require_text(ROOT / "docs/roadmap/M28_EXECUTION.md", "PR             : #96 — MERGED")
        require_text(ROOT / "docs/roadmap/M28_EXECUTION.md", "M28-S9")
        require_text(ROOT / "docs/roadmap/M28_EXECUTION.md", "1er août 2026")
        require_text(ROOT / "docs/roadmap/M28_EXECUTION.md", "CI DEFERRED")
        require_text(ROOT / "docs/developer/remote-worker-sandbox-disposition.md",
                     "WORKER_SANDBOX_CLAIM_PROHIBITED")
        require_text(ROOT / "docs/developer/remote-worker-sandbox-disposition.md",
                     "BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND")
        require_text(ROOT / "docs/developer/hosted-production-boundaries.md",
                     "HOSTED_SAAS_OPERATION_NOT_CLAIMED")
        require_text(ROOT / "docs/developer/hosted-production-boundaries.md",
                     "HostedTransportSecurityPort")
        print("M28 CURRENT DOCUMENTATION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"M28 CURRENT DOCUMENTATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

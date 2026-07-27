#!/usr/bin/env python3
"""Check current MINOS documentation against authoritative source facts."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(pattern: str, text: str, label: str, flags: int = 0) -> str:
    match = re.search(pattern, text, flags)
    if not match:
        raise RuntimeError(f"cannot derive {label}")
    return match.group(1)


def require_text(relative: str, text: str, expected: str) -> None:
    if expected not in text:
        raise RuntimeError(f"{relative}: missing expected text: {expected}")


def forbid_text(relative: str, text: str, forbidden: str) -> None:
    if forbidden in text:
        raise RuntimeError(f"{relative}: stale text is forbidden: {forbidden}")


def main() -> int:
    try:
        mcp_source = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java")
        tool_count = int(require(r"TOOL_COUNT\s*=\s*(\d+)", mcp_source, "MCP tool count"))

        readme = read("README.md")
        user_readme = read("docs/user/README.md")
        cli = read("docs/user/cli.md")
        roadmap = read("docs/ROADMAP.md")
        status = read("docs/STATUS.md")
        execution = read("docs/roadmap/M21_EXECUTION.md")
        supply_chain = read("docs/developer/supply-chain.md")
        root_pom = read("pom.xml")
        app_pom = read("minos-app/pom.xml")

        require_text("README.md", readme, "C0 à M20 sont terminés, validés et livrés.")
        require_text("README.md", readme, f"MCP STDIO — {tool_count} tools read-only")
        forbid_text("README.md", readme, "C0 à M14 sont terminés et livrés.")
        forbid_text("README.md", readme, "MCP STDIO — 16 tools read-only")

        require_text("docs/user/README.md", user_readme, f"Le MCP expose **{tool_count} tools read-only**")
        forbid_text("docs/user/README.md", user_readme, "Le MCP expose **16 tools read-only**")

        require_text("docs/user/cli.md", cli, f"Le catalogue courant contient **{tool_count} tools read-only**")
        forbid_text("docs/user/cli.md", cli, "catalogue historique de **16 tools**")

        require_text("docs/ROADMAP.md", roadmap, "M21 — Production Integrity & Surface Convergence")
        forbid_text("docs/ROADMAP.md", roadmap, "Aucun M21 n'est déclaré")
        for milestone in range(22, 28):
            require_text("docs/ROADMAP.md", roadmap, f"M{milestone} —")

        require_text("docs/STATUS.md", status, "M21 — Production Integrity")
        require_text("docs/STATUS.md", status, "S1   governance + docs + runner local                 VALIDÉ")
        require_text("docs/STATUS.md", status, "S2   CI recovery + readiness branch protection        EN PAUSE jusqu’en août 2026")
        require_text("docs/STATUS.md", status, "S3   quality gates M19/M20                            VALIDÉ")
        require_text("docs/STATUS.md", status, "S4   Maven module-boundary hardening                  VALIDÉ")
        require_text("docs/STATUS.md", status, "S5   supply-chain + release hardening                 EN COURS")
        forbid_text("docs/STATUS.md", status, "Aucun M21 n'est actuellement déclaré")

        require_text("docs/roadmap/M21_EXECUTION.md", execution, "Issue : **#73")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S1 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S2 EN PAUSE jusqu’en août 2026")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S3 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S4 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S5 EN COURS")

        require_text("docs/developer/supply-chain.md", supply_chain, "CycloneDX JSON")
        require_text("docs/developer/supply-chain.md", supply_chain, "RELEASE-MANIFEST.json")
        require_text("docs/developer/supply-chain.md", supply_chain, "MINOS_REQUIRE_SIGNED_RELEASE")
        require_text("pom.xml", root_pom, "<cyclonedx.maven.plugin.version>2.9.2</cyclonedx.maven.plugin.version>")
        require_text("minos-app/pom.xml", app_pom, "<goal>makeAggregateBom</goal>")
        require_text("minos-app/pom.xml", app_pom, "<schemaVersion>1.6</schemaVersion>")
        require_text("minos-app/pom.xml", app_pom, "<includeTestScope>false</includeTestScope>")

        print(f"M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools={tool_count})")
        return 0
    except Exception as exception:
        print(f"M21 CURRENT DOCUMENTATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

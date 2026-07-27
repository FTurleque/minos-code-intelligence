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
        intellij = read("docs/user/intellij-plugin.md")
        developer_readme = read("docs/developer/README.md")
        public_surfaces = read("docs/developer/public-surfaces.md")
        advanced_provider = read("docs/developer/advanced-program-provider.md")
        semantic_scale = read("docs/developer/semantic-scale-qualification.md")
        roadmap = read("docs/ROADMAP.md")
        status = read("docs/STATUS.md")
        execution = read("docs/roadmap/M21_EXECUTION.md")
        supply_chain = read("docs/developer/supply-chain.md")
        root_pom = read("pom.xml")
        app_pom = read("minos-app/pom.xml")
        release_build = read("scripts/release/build-windows-distribution.ps1")
        ide_command = read("minos-cli/src/main/java/com/minos/cli/IdeCommand.java")
        parity_gate = read("scripts/intellij/check-m21-parity.py")
        s6_runner = read("scripts/m21/run-s6.ps1")
        s7_gate = read("scripts/m21/check-s7-provider.py")
        s7_runner = read("scripts/m21/run-s7.ps1")
        s8_probe = read("scripts/m21/M21SemanticScaleProbe.java")
        s8_benchmark = read("scripts/m21/run-s8-benchmark.ps1")
        s8_gate = read("scripts/m21/check-s8-results.py")
        s8_runner = read("scripts/m21/run-s8.ps1")
        graph_service = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java")
        quality_gate = read("scripts/quality/check-jacoco.py")

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
        require_text("docs/STATUS.md", status, "S5   supply-chain + release hardening                 VALIDÉ")
        require_text("docs/STATUS.md", status, "S6   IntelliJ parity M19/M20                          VALIDÉ")
        require_text("docs/STATUS.md", status, "S7   advanced provider productionization              EN COURS")
        require_text("docs/STATUS.md", status, "M21-S5 SUPPLY-CHAIN RELEASE VALIDATION SUCCESS")
        require_text("docs/STATUS.md", status, "M21-S6 INTELLIJ PARITY VALIDATION SUCCESS")
        require_text("docs/STATUS.md", status, "8dff78af7cfbdab1c1d056e3b46b0fd9e5c75ee6")
        require_text("docs/STATUS.md", status, "FileProgramGraphProvider")
        forbid_text("docs/STATUS.md", status, "Aucun M21 n'est actuellement déclaré")

        require_text("docs/roadmap/M21_EXECUTION.md", execution, "Issue : **#73")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S1 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S2 EN PAUSE jusqu’en août 2026")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S3 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S4 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S5 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S6 VALIDÉ")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S7 EN COURS")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "M21 INTELLIJ PARITY CONSISTENCY SUCCESS")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT")

        require_text("docs/developer/supply-chain.md", supply_chain, "CycloneDX JSON")
        require_text("docs/developer/supply-chain.md", supply_chain, "racine d'exécution Maven")
        require_text("docs/developer/supply-chain.md", supply_chain, "RELEASE-MANIFEST.json")
        require_text("docs/developer/supply-chain.md", supply_chain, "MINOS_REQUIRE_SIGNED_RELEASE")

        require_text("pom.xml", root_pom, "<cyclonedx.maven.plugin.version>2.9.2</cyclonedx.maven.plugin.version>")
        forbid_text("minos-app/pom.xml", app_pom, "<artifactId>cyclonedx-maven-plugin</artifactId>")
        require_text(
            "scripts/release/build-windows-distribution.ps1",
            release_build,
            '"org.cyclonedx:cyclonedx-maven-plugin:${CycloneDxVersion}:makeAggregateBom"',
        )
        require_text("scripts/release/build-windows-distribution.ps1", release_build, "'-DschemaVersion=1.6'")
        require_text("scripts/release/build-windows-distribution.ps1", release_build, "'-DoutputFormat=json'")
        require_text("scripts/release/build-windows-distribution.ps1", release_build, "'-DincludeTestScope=false'")
        require_text("scripts/release/build-windows-distribution.ps1", release_build, "target\\sbom")
        require_text("scripts/release/build-windows-distribution.ps1", release_build, "minos-cyclonedx.json")

        for capability in (
            "program-graph",
            "impact-v2",
            "security-paths",
            "semantic-index-status",
            "semantic-index-sync",
            "semantic-search",
            "hybrid-search",
            "hybrid-context",
        ):
            require_text("IdeCommand.java", ide_command, f'"{capability}"')
            require_text("docs/user/intellij-plugin.md", intellij, capability)
            require_text("docs/developer/public-surfaces.md", public_surfaces, capability)

        require_text("docs/user/intellij-plugin.md", intellij, "provider d'embeddings est **désactivé par défaut**")
        require_text("docs/user/intellij-plugin.md", intellij, "pas un language model")
        require_text("docs/user/intellij-plugin.md", intellij, "absence de chemin observé ≠ preuve de sûreté")
        require_text("docs/user/intellij-plugin.md", intellij, "IntelliJ Platform 2026.1")
        require_text("docs/developer/public-surfaces.md", public_surfaces, "M21-S6 étend le handshake v1 **sans rupture**")
        require_text("docs/developer/public-surfaces.md", public_surfaces, "Plugin Verifier")
        forbid_text("docs/developer/public-surfaces.md", public_surfaces, "Une future UX sémantique IDE")
        require_text("scripts/intellij/check-m21-parity.py", parity_gate, "ideBranch=261")
        require_text("scripts/m21/run-s6.ps1", s6_runner, "M21-S6 INTELLIJ PARITY VALIDATION SUCCESS")

        require_text("docs/developer/advanced-program-provider.md", advanced_provider, ".minos/program-graph-v1")
        require_text("docs/developer/advanced-program-provider.md", advanced_provider, "CALL_GRAPH + LOCAL_DATA_FLOW")
        require_text("docs/developer/advanced-program-provider.md", advanced_provider, "ARGUMENT_FLOW")
        require_text("docs/developer/advanced-program-provider.md", advanced_provider, "ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT")
        require_text("scripts/m21/check-s7-provider.py", s7_gate, "M21 ADVANCED PROVIDER CONSISTENCY SUCCESS")
        require_text("scripts/m21/run-s7.ps1", s7_runner, "M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS")
        require_text("ProgramGraphService.java", graph_service, "new FileProgramGraphProvider()")
        forbid_text("ProgramGraphService.java", graph_service, "capabilities.add(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW)")
        require_text("scripts/quality/check-jacoco.py", quality_gate, "FileProgramGraphProvider")

        require_text("docs/developer/README.md", developer_readme, "semantic-scale-qualification.md")
        require_text("docs/developer/semantic-scale-qualification.md", semantic_scale, "210 000 documents")
        require_text("docs/developer/semantic-scale-qualification.md", semantic_scale, "384 dimensions")
        require_text("docs/developer/semantic-scale-qualification.md", semantic_scale, "OPTIMIZE_MEASURED_BOTTLENECK")
        require_text("docs/developer/semantic-scale-qualification.md", semantic_scale, "KEEP_CURRENT_M20_BACKEND")
        for cardinality in ("10_000, 100_000, 500_000, 250_000",):
            require_text("scripts/m21/M21SemanticScaleProbe.java", s8_probe, cardinality)
        require_text("scripts/m21/M21SemanticScaleProbe.java", s8_probe, "LocalHashEmbeddingProvider.DEFAULT_DIMENSIONS")
        require_text("scripts/m21/M21SemanticScaleProbe.java", s8_probe, '"vector-store-load"')
        require_text("scripts/m21/M21SemanticScaleProbe.java", s8_probe, '"semantic-search"')
        require_text("scripts/m21/M21SemanticScaleProbe.java", s8_probe, '"hybrid-search"')
        require_text("scripts/m21/M21SemanticScaleProbe.java", s8_probe, '"hybrid-context"')
        require_text("scripts/m21/run-s8-benchmark.ps1", s8_benchmark, "process_rss_bytes")
        require_text("scripts/m21/run-s8-benchmark.ps1", s8_benchmark, "WorkingSet64")
        require_text("scripts/m21/check-s8-results.py", s8_gate, '"semantic-search"')
        require_text("scripts/m21/check-s8-results.py", s8_gate, "OPTIMIZE_MEASURED_BOTTLENECK")
        require_text("scripts/m21/check-s8-results.py", s8_gate, "KEEP_CURRENT_M20_BACKEND")
        require_text("scripts/m21/run-s8.ps1", s8_runner, "M21-S8 SEMANTIC SCALE VALIDATION SUCCESS")
        require_text("scripts/m21/run-s8.ps1", s8_runner, "Assert-NoUnratifiedSemanticBackend")

        print(f"M21 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools={tool_count})")
        return 0
    except Exception as exception:
        print(f"M21 CURRENT DOCUMENTATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

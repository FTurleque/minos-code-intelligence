#!/usr/bin/env python3
"""Check current MINOS documentation against authoritative source facts through M24."""

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
        java_advanced_provider = read("docs/developer/java-advanced-provider.md")
        semantic_scale = read("docs/developer/semantic-scale-qualification.md")
        semantic_retrieval_2 = read("docs/developer/semantic-retrieval-2.md")
        polyglot_user = read("docs/user/polyglot-providers.md")
        polyglot_developer = read("docs/developer/polyglot-providers.md")
        roadmap = read("docs/ROADMAP.md")
        status = read("docs/STATUS.md")
        execution = read("docs/roadmap/M21_EXECUTION.md")
        m22_execution = read("docs/roadmap/M22_EXECUTION.md")
        m23_execution = read("docs/roadmap/M23_EXECUTION.md")
        m24_execution = read("docs/roadmap/M24_EXECUTION.md")
        m22_adr = read("docs/adr/0030-java-ast-reference-provider-with-explicit-capability-limits.md")
        m23_adr = read("docs/adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md")
        m24_adr = read("docs/adr/0032-evidence-gated-polyglot-scip-providers.md")
        adr_index = read("docs/adr/README.md")
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
        s9_runner = read("scripts/m21/run-s9.ps1")
        m22_runner = read("scripts/m22/run-final.ps1")
        m22_gate = read("scripts/m22/check-provider.py")
        m23_runner = read("scripts/m23/run-final.ps1")
        m23_gate = read("scripts/m23/check-semantic.py")
        m23_quality = read("scripts/m23/evaluate-learned-quality.py")
        m24_windows = read("scripts/m24/run-final.ps1")
        m24_linux = read("scripts/m24/run-final.sh")
        m24_gate = read("scripts/m24/check-polyglot.py")
        m24_e2e = read("scripts/m24/run-provider-e2e.py")
        graph_service = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java")
        java_provider = read("minos-application/src/main/java/com/minos/program/analysis/JavaSourceProgramGraphProvider.java")
        ollama_provider = read("minos-application/src/main/java/com/minos/semantic/OllamaEmbeddingProvider.java")
        semantic_search = read("minos-application/src/main/java/com/minos/semantic/SemanticSearchService.java")
        semantic_store = read("minos-storage-local/src/main/java/com/minos/store/FileSemanticVectorStore.java")
        quality_gate = read("scripts/quality/check-jacoco.py")

        # Public/current overview.
        require_text("README.md", readme, "C0 à M20 sont terminés, validés et livrés sur `main`.")
        require_text("README.md", readme, "M22 — Advanced Provider Intelligence est terminé, validé exact-head et fusionné dans `develop` via PR #77")
        require_text("README.md", readme, "M23 — Semantic Retrieval 2.0 est terminé, validé exact-head et fusionné dans `develop` via PR #79")
        require_text("README.md", readme, "M24 — Polyglot Expansion est en cours")
        require_text("README.md", readme, "Draft PR #82")
        require_text("README.md", readme, "MINOS_SEMANTIC_PROVIDER='ollama'")
        require_text("README.md", readme, "MCP STDIO — 23 tools read-only")
        require_text("README.md", readme, "docs/roadmap/M24_EXECUTION.md")
        require_text("README.md", readme, "docs/user/polyglot-providers.md")
        forbid_text("README.md", readme, "M23 — Semantic Retrieval 2.0 est le jalon fonctionnel actif")
        forbid_text("README.md", readme, "M22 — Advanced Provider Intelligence est le jalon fonctionnel actif")
        forbid_text("README.md", readme, "C0 à M14 sont terminés et livrés.")

        require_text("docs/user/README.md", user_readme, f"Le MCP expose **{tool_count} tools read-only**")
        forbid_text("docs/user/README.md", user_readme, "Le MCP expose **16 tools read-only**")
        require_text("docs/user/cli.md", cli, f"Le catalogue courant contient **{tool_count} tools read-only**")
        forbid_text("docs/user/cli.md", cli, "catalogue historique de **16 tools**")

        # Roadmap/status authority: M23 is merged, M24 is the only active functional milestone.
        require_text("docs/ROADMAP.md", roadmap, "M22  Advanced Provider Intelligence               ✅ VALIDÉ / MERGÉ develop")
        require_text("docs/ROADMAP.md", roadmap, "M23  Semantic Retrieval 2.0                       ✅ VALIDÉ / MERGÉ develop")
        require_text("docs/ROADMAP.md", roadmap, "M24  Polyglot Expansion")
        require_text("docs/ROADMAP.md", roadmap, "EN COURS — issue #81 / PR #82 DRAFT")
        require_text("docs/ROADMAP.md", roadmap, "roadmap/M24_EXECUTION.md")
        require_text("docs/ROADMAP.md", roadmap, "ADR-0032")
        require_text("docs/ROADMAP.md", roadmap, "issue : #81")
        forbid_text("docs/ROADMAP.md", roadmap, "M23  Semantic Retrieval 2.0                       🚧 EN COURS")
        forbid_text("docs/ROADMAP.md", roadmap, "M24  Polyglot Expansion                           ⏳ PLANIFIÉ")
        for milestone in range(24, 28):
            require_text("docs/ROADMAP.md", roadmap, f"M{milestone} —")

        require_text("docs/STATUS.md", status, "M21 — Production Integrity")
        require_text("docs/STATUS.md", status, "S2   CI recovery + readiness branch protection        EN PAUSE jusqu’en août 2026")
        require_text("docs/STATUS.md", status, "M22 — Advanced Provider Intelligence TERMINÉ, VALIDÉ, MERGÉ develop")
        require_text("docs/STATUS.md", status, "75d6169be6d46d4e60ca19e781ff61704ca1613c")
        require_text("docs/STATUS.md", status, "37a3c904fd92c25b343344a26991531c75ebc4b6")
        require_text("docs/STATUS.md", status, "M23 — Semantic Retrieval 2.0         TERMINÉ, VALIDÉ, MERGÉ develop")
        require_text("docs/STATUS.md", status, "7a5fe2b96480a21e063b8ffa537009e5bdf99bc0")
        require_text("docs/STATUS.md", status, "ffe12d95ac46c25026661dca51949fb0d39626b4")
        require_text("docs/STATUS.md", status, "KEEP_CURRENT_M20_BACKEND")
        require_text("docs/STATUS.md", status, "semantic-learned-provider")
        require_text("docs/STATUS.md", status, "M24 — Polyglot Expansion             EN COURS — PR #82 DRAFT")
        require_text("docs/STATUS.md", status, "8dbe34cb9e524acb62becda4faa263d74b90b9a9")
        require_text("docs/STATUS.md", status, "rust-analyzer scip 2026-07-27 / v0.3.2989 / commit 12c3381")
        require_text("docs/STATUS.md", status, "M25 — Remote & Distributed Indexing  PLANIFIÉ")

        # M21 retained integrity facts.
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "Issue : **#73")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "S2 EN PAUSE jusqu’en août 2026")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "M21 INTELLIJ PARITY CONSISTENCY SUCCESS")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "KEEP_CURRENT_M20_BACKEND")
        require_text("docs/roadmap/M21_EXECUTION.md", execution, "M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS")

        # M22 stays qualified and regression-protected.
        require_text("docs/roadmap/M22_EXECUTION.md", m22_execution, "Issue : **#76")
        require_text("docs/roadmap/M22_EXECUTION.md", m22_execution, "JavaSourceProgramGraphProvider")
        for slice_name in range(1, 10):
            require_text("docs/roadmap/M22_EXECUTION.md", m22_execution, f"M22-S{slice_name}")
        require_text("docs/roadmap/M22_EXECUTION.md", m22_execution, "precision=1.0 recall=1.0")
        require_text("docs/roadmap/M22_EXECUTION.md", m22_execution, "M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS")
        require_text("docs/developer/java-advanced-provider.md", java_advanced_provider, "minos-java-source-v1")
        require_text("docs/developer/java-advanced-provider.md", java_advanced_provider, "JAVA_AST_PARSE_ONLY_TYPE_ATTRIBUTION_NOT_PROVEN")
        require_text("docs/adr/0030-java-ast-reference-provider-with-explicit-capability-limits.md", m22_adr, "Status: **Accepted**")
        require_text("JavaSourceProgramGraphProvider.java", java_provider, 'PROVIDER_ID = "minos-java-source-v1"')
        require_text("JavaSourceProgramGraphProvider.java", java_provider, "OriginType.DERIVED_BY_MINOS")
        forbid_text("JavaSourceProgramGraphProvider.java", java_provider, "task.analyze(")
        require_text("scripts/m22/check-provider.py", m22_gate, "M22 ADVANCED PROVIDER CONSISTENCY SUCCESS")
        require_text("scripts/m22/run-final.ps1", m22_runner, "M22 PACKAGED JDK.COMPILER RUNTIME SUCCESS")
        require_text("scripts/m22/run-final.ps1", m22_runner, "M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS")

        # M23 learned semantic contract remains qualified and authoritative.
        require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, "Issue : **#78")
        require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, "9/9 IMPLÉMENTÉS")
        for slice_name in range(1, 10):
            require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, f"M23-S{slice_name}")
        require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, "Recall@3 >= 0.75")
        require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, "MRR      >= 0.70")
        require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, "nDCG@3   >= 0.72")
        require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, "M21-S2/CI reste en pause jusqu’en août 2026")
        require_text("docs/roadmap/M23_EXECUTION.md", m23_execution, "M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS")
        require_text("docs/adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md", m23_adr, "loopback")
        require_text("docs/adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md", m23_adr, "float32")
        require_text("docs/adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md", m23_adr, "KEEP_CURRENT_M20_BACKEND")
        require_text("docs/adr/README.md", adr_index, "0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md")
        require_text("docs/developer/README.md", developer_readme, "semantic-retrieval-2.md")
        require_text("docs/developer/semantic-retrieval-2.md", semantic_retrieval_2, "MINOS_SEMANTIC_PROVIDER='ollama'")
        require_text("docs/developer/semantic-retrieval-2.md", semantic_retrieval_2, "index-v2.bin")
        require_text("docs/developer/semantic-retrieval-2.md", semantic_retrieval_2, "Recall@3 >= 0.75")
        require_text("OllamaEmbeddingProvider.java", ollama_provider, 'return "minos-local-ollama"')
        require_text("OllamaEmbeddingProvider.java", ollama_provider, "Ollama endpoint must be loopback-only")
        require_text("SemanticSearchService.java", semantic_search, "MAX_QUERY_CACHE_ENTRIES = 256")
        require_text("SemanticSearchService.java", semantic_search, "ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND")
        require_text("FileSemanticVectorStore.java", semantic_store, "FORMAT_VERSION = 2")
        require_text("FileSemanticVectorStore.java", semantic_store, "output.writeFloat")
        require_text("scripts/m23/check-semantic.py", m23_gate, "M23 SEMANTIC RETRIEVAL CONSISTENCY SUCCESS")
        require_text("scripts/m23/evaluate-learned-quality.py", m23_quality, "M23 LEARNED SEMANTIC QUALITY SUCCESS")
        require_text("scripts/m23/run-final.ps1", m23_runner, "0.2.0-m23")
        require_text("scripts/m23/run-final.ps1", m23_runner, "M23 FINAL SEMANTIC RETRIEVAL 2.0 VALIDATION SUCCESS")
        forbid_text("scripts/m23/run-final.ps1", m23_runner, "workflow_dispatch")
        forbid_text("scripts/m23/run-final.ps1", m23_runner, "gh workflow")
        forbid_text("scripts/m23/run-final.ps1", m23_runner, "gh run")

        # M24 active polyglot contract: implementation may be present, but docs must not invent final PASS.
        require_text("docs/roadmap/M24_EXECUTION.md", m24_execution, "Issue   : #81 — OPEN")
        require_text("docs/roadmap/M24_EXECUTION.md", m24_execution, "PR      : #82 — DRAFT")
        for slice_name in range(1, 10):
            require_text("docs/roadmap/M24_EXECUTION.md", m24_execution, f"M24-S{slice_name}")
        for token in ("scip-clang", "scip-dotnet", "scip-go", "rust-analyzer", "v0.3.2989", "12c3381"):
            require_text("docs/roadmap/M24_EXECUTION.md", m24_execution, token)
        require_text("docs/roadmap/M24_EXECUTION.md", m24_execution, "M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**")
        require_text("docs/adr/0032-evidence-gated-polyglot-scip-providers.md", m24_adr, "Evidence-gated polyglot SCIP providers")
        require_text("docs/adr/0032-evidence-gated-polyglot-scip-providers.md", m24_adr, "2026-07-27")
        require_text("docs/adr/0032-evidence-gated-polyglot-scip-providers.md", m24_adr, "v0.3.2989")
        require_text("docs/adr/0032-evidence-gated-polyglot-scip-providers.md", m24_adr, "KEEP_CURRENT_M20_BACKEND")
        require_text("docs/adr/README.md", adr_index, "0032-evidence-gated-polyglot-scip-providers.md")
        require_text("docs/user/polyglot-providers.md", polyglot_user, "Discovery versus indexation")
        require_text("docs/user/polyglot-providers.md", polyglot_user, "scip-clang")
        require_text("docs/user/polyglot-providers.md", polyglot_user, "scip-dotnet")
        require_text("docs/user/polyglot-providers.md", polyglot_user, "scip-go")
        require_text("docs/user/polyglot-providers.md", polyglot_user, "rust-analyzer")
        require_text("docs/user/polyglot-providers.md", polyglot_user, "CFG")
        require_text("docs/developer/polyglot-providers.md", polyglot_developer, "ProviderOperationalProfile")
        require_text("docs/developer/polyglot-providers.md", polyglot_developer, "STRUCTURAL_FALLBACK")
        require_text("docs/developer/polyglot-providers.md", polyglot_developer, "PROVIDER_SCOPED_FALLBACK")
        require_text("scripts/m24/check-polyglot.py", m24_gate, "M24 POLYGLOT CONSISTENCY SUCCESS")
        require_text("scripts/m24/run-provider-e2e.py", m24_e2e, "M24 PROVIDER END-TO-END EVALUATION SUCCESS")
        require_text("scripts/m24/run-final.ps1", m24_windows, "M24 FINAL POLYGLOT EXPANSION VALIDATION SUCCESS")
        require_text("scripts/m24/run-final.sh", m24_linux, "M24 LINUX POLYGLOT EXPANSION VALIDATION SUCCESS")
        require_text("scripts/m24/run-final.ps1", m24_windows, "ExpectedHead")
        require_text("scripts/m24/run-final.sh", m24_linux, "EXPECTED_HEAD")
        forbid_text("scripts/m24/run-final.ps1", m24_windows, "gh workflow")
        forbid_text("scripts/m24/run-final.ps1", m24_windows, "gh run")
        forbid_text("scripts/m24/run-final.sh", m24_linux, "gh workflow")
        forbid_text("scripts/m24/run-final.sh", m24_linux, "gh run")

        # Existing public surfaces and production gates remain aligned.
        require_text("docs/developer/supply-chain.md", supply_chain, "CycloneDX JSON")
        require_text("docs/developer/supply-chain.md", supply_chain, "RELEASE-MANIFEST.json")
        require_text("docs/developer/supply-chain.md", supply_chain, "MINOS_REQUIRE_SIGNED_RELEASE")
        require_text("pom.xml", root_pom, "<cyclonedx.maven.plugin.version>2.9.2</cyclonedx.maven.plugin.version>")
        forbid_text("minos-app/pom.xml", app_pom, "<artifactId>cyclonedx-maven-plugin</artifactId>")
        require_text("scripts/release/build-windows-distribution.ps1", release_build, "'--add-modules', 'jdk.compiler'")

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
        require_text("docs/developer/public-surfaces.md", public_surfaces, "Plugin Verifier")
        require_text("scripts/intellij/check-m21-parity.py", parity_gate, "ideBranch=261")
        require_text("scripts/m21/run-s6.ps1", s6_runner, "M21-S6 INTELLIJ PARITY VALIDATION SUCCESS")

        require_text("docs/developer/advanced-program-provider.md", advanced_provider, ".minos/program-graph-v1")
        require_text("scripts/m21/check-s7-provider.py", s7_gate, "M21 ADVANCED PROVIDER CONSISTENCY SUCCESS")
        require_text("scripts/m21/run-s7.ps1", s7_runner, "M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS")
        require_text("ProgramGraphService.java", graph_service, "new FileProgramGraphProvider()")
        require_text("ProgramGraphService.java", graph_service, "new JavaSourceProgramGraphProvider()")
        forbid_text("ProgramGraphService.java", graph_service, "capabilities.add(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW)")

        require_text("scripts/quality/check-jacoco.py", quality_gate, '"java-advanced-provider"')
        require_text("scripts/quality/check-jacoco.py", quality_gate, '"semantic-learned-provider"')
        require_text("scripts/quality/check-jacoco.py", quality_gate, '"m24-polyglot-provider-platform"')
        require_text("scripts/quality/check-jacoco.py", quality_gate, "OllamaEmbeddingProvider")

        require_text("docs/developer/semantic-scale-qualification.md", semantic_scale, "KEEP_CURRENT_M20_BACKEND")
        require_text("scripts/m21/M21SemanticScaleProbe.java", s8_probe, "10_000, 100_000, 500_000, 250_000")
        require_text("scripts/m21/run-s8-benchmark.ps1", s8_benchmark, "benchmark_jar_isolated")
        require_text("scripts/m21/check-s8-results.py", s8_gate, "KEEP_CURRENT_M20_BACKEND")
        require_text("scripts/m21/run-s8.ps1", s8_runner, "M21-S8 SEMANTIC SCALE VALIDATION SUCCESS")
        require_text("scripts/m21/run-s9.ps1", s9_runner, "M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS")

        print(f"M24 CURRENT DOCUMENTATION CONSISTENCY SUCCESS (MCP tools={tool_count})")
        return 0
    except Exception as exception:
        print(f"M24 CURRENT DOCUMENTATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

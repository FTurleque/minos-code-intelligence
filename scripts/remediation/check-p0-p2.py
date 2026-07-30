#!/usr/bin/env python3
"""Static consistency gate for the pre-M28 P0-P2 audit remediation."""

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


def require(relative: str, text: str, value: str) -> None:
    if value not in text:
        raise RuntimeError(f"{relative}: missing required evidence: {value}")


def forbid(relative: str, text: str, value: str) -> None:
    if value in text:
        raise RuntimeError(f"{relative}: forbidden stale construction: {value}")


def section(text: str, heading: str, next_heading: str | None = None) -> str:
    pattern = rf"(?ms)^### {re.escape(heading)}\s*$\n(.*?)"
    if next_heading is not None:
        pattern += rf"(?=^### {re.escape(next_heading)}\s*$)"
    else:
        pattern += r"(?=^## |\Z)"
    match = re.search(pattern, text)
    if not match:
        raise RuntimeError(f"cannot locate documentation section: {heading}")
    return match.group(1)


def main() -> int:
    try:
        application = read("minos-application/src/main/java/com/minos/application/MinosApplication.java")
        graph_service = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java")
        fingerprint_provider = read(
            "minos-application/src/main/java/com/minos/program/analysis/"
            "FingerprintConstrainedJavaProgramGraphProvider.java"
        )
        application_test = read(
            "minos-application/src/test/java/com/minos/application/MinosApplicationTest.java"
        )
        product_facts = read("scripts/docs/product-facts.py")
        generated_facts = read("docs/generated/product-facts.md")
        architecture = read("scripts/architecture/check-module-boundaries.py")
        public_surfaces = read("docs/developer/public-surfaces.md")
        worker = read("minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java")
        sandbox = read("minos-runtime-local/src/main/java/com/minos/runtime/WorkerSandboxBackend.java")
        worker_test = read(
            "minos-runtime-local/src/test/java/com/minos/runtime/LocalIsolatedIndexWorkerTest.java"
        )
        hosted = read("minos-application/src/main/java/com/minos/hosted/HostedControlPlaneService.java")
        hosted_auth = read("minos-application/src/main/java/com/minos/hosted/HostedAuthorizationService.java")
        hosted_audit = read("minos-application/src/main/java/com/minos/hosted/HostedAuditChain.java")
        hosted_test = read(
            "minos-application/src/test/java/com/minos/hosted/HostedControlPlaneServiceTest.java"
        )
        c0_research = read("docs/research/code-intelligence-architecture-analysis.md")

        require("MinosApplication.java", application,
                "ProgramGraphService.productionProviders(effectiveFingerprints)")
        require("ProgramGraphService.java", graph_service,
                "new FingerprintConstrainedJavaProgramGraphProvider(fingerprints)")
        require("ProgramGraphService.java", graph_service, "public CacheStats cacheStats()")
        require("FingerprintConstrainedJavaProgramGraphProvider.java", fingerprint_provider,
                "SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT")
        require("MinosApplicationTest.java", application_test,
                "productionCompositionExposesM22CapabilitiesFromOpen")
        require("MinosApplicationTest.java", application_test, "MinosApplication.open")
        require("MinosApplicationTest.java", application_test,
                "ProgramGraphCapability.CONTROL_FLOW")

        require("product-facts.py", product_facts, "qualified_descriptor_methods")
        require("product-facts.py", product_facts,
                'qualified M24 catalog must expose 7 providers')
        forbid("product-facts.py", product_facts,
               '("scipJava", "scipTypeScript", "scipPython")')
        provider_headings = re.findall(r"^### `([^`]+)`", generated_facts, re.MULTILINE)
        if provider_headings != [
            "scip-java", "scip-typescript", "scip-python", "scip-clang",
            "scip-dotnet", "scip-go", "rust-analyzer-scip"
        ]:
            raise RuntimeError(f"generated facts expose unexpected provider catalog: {provider_headings}")

        require("check-module-boundaries.py", architecture, "ALLOWED_DEPENDENCIES")
        require("check-module-boundaries.py", architecture, "internal Maven dependency cycle")
        require("check-module-boundaries.py", architecture, "dependencyPolicy=explicit-v1")

        m26 = section(public_surfaces, "M26 — runtime et dynamique",
                      "M27 — team / hosted control plane")
        m27 = section(public_surfaces, "M27 — team / hosted control plane")
        require("public-surfaces.md M26", m26, "minos_runtime_sessions")
        forbid("public-surfaces.md M26", m26, "minos_team_")
        require("public-surfaces.md M27", m27, "minos_team_tenant")
        require("public-surfaces.md M27", m27, "MINOS_TEAM_TOKEN")

        require("WorkerSandboxBackend.java", sandbox, "NetworkGuarantee.OS_ENFORCED")
        require("WorkerSandboxBackend.java", sandbox,
                "native worker cannot prove OS-level network denial")
        require("LocalIsolatedIndexWorker.java", worker, "WorkerSandboxBackend sandboxBackend")
        forbid("LocalIsolatedIndexWorker.java", worker, "boolean networkDenyEnforced")
        require("LocalIsolatedIndexWorkerTest.java", worker_test,
                "denyIsAcceptedOnlyThroughAnOsEnforcedBackend")

        if len(hosted.splitlines()) >= 500:
            raise RuntimeError("HostedControlPlaneService remains an oversized >=500-line hotspot")
        require("HostedAuthorizationService.java", hosted_auth, "authorizeMutation")
        require("HostedAuditChain.java", hosted_audit, "hosted audit event authentication failed")
        require("HostedControlPlaneServiceTest.java", hosted_test,
                "rejectsPersistedAuditEventWithInvalidHmac")

        for disposition in ("ADOPTER", "ADAPTER", "REJETER", "DIFFÉRER"):
            require("code-intelligence-architecture-analysis.md", c0_research, disposition)
        require("code-intelligence-architecture-analysis.md", c0_research, "issue #2")

        print("P0-P2 AUDIT REMEDIATION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"P0-P2 AUDIT REMEDIATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

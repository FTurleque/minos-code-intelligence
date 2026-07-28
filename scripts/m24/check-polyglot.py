#!/usr/bin/env python3
"""Static fail-closed consistency gate for M24 Polyglot Expansion."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required M24 file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, expected: str) -> None:
    if expected not in text:
        raise RuntimeError(f"{relative}: missing expected text: {expected}")


def forbid(relative: str, text: str, forbidden: str) -> None:
    if forbidden in text:
        raise RuntimeError(f"{relative}: forbidden text present: {forbidden}")


def main() -> int:
    try:
        discovery_model_path = "minos-engine/src/main/java/com/minos/discovery/ProjectDiscovery.java"
        discovery_plugins_path = "minos-application/src/main/java/com/minos/discovery/DefaultDiscoveryPlugins.java"
        discovery_service_path = "minos-application/src/main/java/com/minos/discovery/ProjectDiscoveryService.java"
        catalog_path = "minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIndexerCatalog.java"
        runtime_path = "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManager.java"
        conformance_path = "minos-engine/src/main/java/com/minos/orchestration/ProviderConformanceKit.java"
        operational_path = "minos-engine/src/main/java/com/minos/orchestration/ProviderOperationalProfile.java"
        indexing_path = "minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java"
        quality_path = "scripts/quality/check-jacoco.py"
        roadmap_path = "docs/roadmap/M24_EXECUTION.md"
        adr_path = "docs/adr/0032-evidence-gated-polyglot-scip-providers.md"
        windows_prereq_path = "scripts/m24/check-windows-prerequisites.ps1"
        windows_final_path = "scripts/m24/run-final.ps1"

        discovery_model = read(discovery_model_path)
        plugins = read(discovery_plugins_path)
        service = read(discovery_service_path)
        catalog = read(catalog_path)
        runtime = read(runtime_path)
        conformance = read(conformance_path)
        operational = read(operational_path)
        indexing = read(indexing_path)
        quality = read(quality_path)
        roadmap = read(roadmap_path)
        adr = read(adr_path)
        windows_prereq = read(windows_prereq_path)
        windows_final = read(windows_final_path)

        for token in ("C,", "CPP,", "CSHARP,", "GO,", "RUST"):
            require(discovery_model_path, discovery_model, token)
        for token in ("CMAKE,", "DOTNET,", "GO_MODULE,", "CARGO"):
            require(discovery_model_path, discovery_model, token)

        for token in (
            'markerProject("CMakeLists.txt")',
            'extensionMarkerProject(".csproj", ".sln")',
            'markerProject("go.mod", "go.work")',
            'markerProject("Cargo.toml")',
            'extensionLanguage(Language.CSHARP, ".cs")',
            'extensionLanguage(Language.GO, ".go")',
            'extensionLanguage(Language.RUST, ".rs")',
        ):
            require(discovery_plugins_path, plugins, token)

        # M17 architectural boundary: orchestration stays data/SPI driven.
        for forbidden in ("Language.CSHARP", "Language.GO", "Language.RUST", "Language.CPP", "Language.C"):
            forbid(discovery_service_path, service, forbidden)

        for token in (
            'SCIP_CLANG_VERSION = "0.4.0"',
            'SCIP_DOTNET_VERSION = "0.2.14"',
            'SCIP_GO_VERSION = "0.2.7"',
            'RUST_ANALYZER_SCIP_VERSION = "0.3.2989"',
            'RUST_ANALYZER_SCIP_RELEASE = "2026-07-27"',
            'RUST_ANALYZER_SCIP_COMMIT = "12c3381"',
            'SCIP_CLANG_ID = "scip-clang"',
            'SCIP_DOTNET_ID = "scip-dotnet"',
            'SCIP_GO_ID = "scip-go"',
            'RUST_ANALYZER_SCIP_ID = "rust-analyzer-scip"',
            "IndexerQualification.EXPERIMENTAL",
            "IndexerCapability.CALL_RELATIONS, CapabilitySupportLevel.UNSUPPORTED",
            "IndexerCapability.INCREMENTAL_INDEXING, CapabilitySupportLevel.UNSUPPORTED",
        ):
            require(catalog_path, catalog, token)

        require(conformance_path, conformance, "operationalProfileExplicit")
        require(conformance_path, conformance, "qualificationPlatforms")
        require(conformance_path, conformance, "stableIdentityBehavior")
        require(conformance_path, conformance, "provenanceBehavior")
        require(operational_path, operational, "qualificationPlatforms")
        require(operational_path, operational, "runtimeRequirements")

        for token in (
            "upstream publishes no Windows binary",
            '"dotnet", "tool", "install"',
            '"GOBIN"',
            "MINOS never mutates rustup/toolchains implicitly",
            "RUST_ANALYZER_SCIP_RELEASE",
        ):
            require(runtime_path, runtime, token)
        forbid(runtime_path, runtime, "dotnet tool install -g")
        forbid(runtime_path, runtime, "rustup update")

        # Explicit provider override is the only M24 path allowed to exercise EXPERIMENTAL providers.
        require(indexing_path, indexing, "new IndexingRequirements(baselineRequirements.requiredCapabilities(), true)")
        require(indexing_path, indexing, "IndexingRequirements.baseline()")

        for fixture in (
            "fixtures/m24/clang/CMakeLists.txt",
            "fixtures/m24/clang/src/main.cpp",
            "fixtures/m24/csharp/Minos.M24.CSharp.csproj",
            "fixtures/m24/csharp/src/Program.cs",
            "fixtures/m24/go/go.mod",
            "fixtures/m24/go/main.go",
            "fixtures/m24/rust/Cargo.toml",
            "fixtures/m24/rust/src/main.rs",
        ):
            read(fixture)

        for test in (
            "minos-application/src/test/java/com/minos/discovery/M24PolyglotDiscoveryTest.java",
            "minos-app/src/test/java/com/minos/adapter/scip/M24PolyglotProviderTest.java",
            "minos-provider-scip/src/test/java/com/minos/adapter/scip/M24PolyglotIdentityProvenanceTest.java",
            "minos-provider-scip/src/test/java/com/minos/adapter/scip/runtime/M24PolyglotProcessPlanFactoryTest.java",
            "minos-provider-scip/src/test/java/com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManagerTest.java",
        ):
            read(test)

        require(quality_path, quality, '"m24-polyglot-provider-platform"')
        for token in ("M24-S1", "M24-S9", "M21-S2", "exact-head", "scip-clang", "scip-dotnet", "scip-go", "rust-analyzer"):
            require(roadmap_path, roadmap, token)
        require(adr_path, adr, "Evidence-gated polyglot SCIP providers")
        require(adr_path, adr, "structured snapshots authoritative")

        # Windows qualification must fail before expensive gates if required toolchains are absent.
        for token in (
            "M24 WINDOWS PREREQUISITES SUCCESS",
            "MINOS_SEMANTIC_PROVIDER",
            "dotnet.exe",
            "go.exe",
            "cargo.exe",
            "rustc.exe",
            "rust-analyzer.exe",
            "2026-07-27",
            "12c3381",
        ):
            require(windows_prereq_path, windows_prereq, token)
        require(windows_final_path, windows_final, "check-windows-prerequisites.ps1")

        # Final runners/docs are part of S8/S9 and must exist before this gate can pass.
        for required in (
            "scripts/m24/check-windows-prerequisites.ps1",
            "scripts/m24/run-final.ps1",
            "scripts/m24/run-final.sh",
            "docs/user/polyglot-providers.md",
            "docs/developer/polyglot-providers.md",
        ):
            read(required)

        print("M24 POLYGLOT CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"M24 POLYGLOT CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

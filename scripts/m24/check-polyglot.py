#!/usr/bin/env python3
"""Static fail-closed consistency gate for M24 Polyglot Expansion."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required M24 file: {relative}")
    return path.read_text(encoding="utf-8")


def normalize_presentation(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip().casefold()


def require(relative: str, text: str, expected: str) -> None:
    if normalize_presentation(expected) not in normalize_presentation(text):
        raise RuntimeError(f"{relative}: missing expected text: {expected}")


def forbid(relative: str, text: str, forbidden: str) -> None:
    if normalize_presentation(forbidden) in normalize_presentation(text):
        raise RuntimeError(f"{relative}: forbidden text present: {forbidden}")


def require_pattern(relative: str, text: str, pattern: str, label: str, flags: int = 0) -> None:
    if not re.search(pattern, text, flags):
        raise RuntimeError(f"{relative}: missing contract: {label}")


def forbid_pattern(relative: str, text: str, pattern: str, label: str, flags: int = 0) -> None:
    if re.search(pattern, text, flags):
        raise RuntimeError(f"{relative}: forbidden contract present: {label}")


def require_assignment(relative: str, text: str, name: str, value: str) -> None:
    require_pattern(
        relative,
        text,
        rf"(?m)^\s*(?:public\s+static\s+final\s+String\s+|\$){re.escape(name)}\s*=\s*['\"]{re.escape(value)}['\"]\s*;?",
        f"{name}={value}",
    )


def require_enum_members(relative: str, text: str, enum_name: str, expected: set[str]) -> None:
    match = re.search(rf"\benum\s+{re.escape(enum_name)}\s*\{{(?P<body>[^}}]+)}}", text, re.DOTALL)
    if not match:
        raise RuntimeError(f"{relative}: cannot parse enum {enum_name}")
    members = set(re.findall(r"(?m)^\s*([A-Z][A-Z0-9_]*)\s*(?:,|$)", match.group("body")))
    missing = expected - members
    if missing:
        raise RuntimeError(f"{relative}: enum {enum_name} is missing: {', '.join(sorted(missing))}")


def require_java_method_contains(relative: str, text: str, method_name: str, pattern: str, label: str) -> None:
    signature = re.search(rf"\b{re.escape(method_name)}\s*\([^)]*\)\s*(?:throws\s+[^{{]+)?\{{", text)
    if not signature:
        raise RuntimeError(f"{relative}: cannot find Java method {method_name}")
    depth = 1
    cursor = signature.end()
    while cursor < len(text) and depth:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
        cursor += 1
    if depth:
        raise RuntimeError(f"{relative}: cannot parse Java method {method_name}")
    require_pattern(relative, text[signature.end():cursor - 1], pattern, label, re.DOTALL)


def require_e2e_set(relative: str, text: str, expected: set[str]) -> None:
    match = re.search(r"--require-e2e\s+['\"]([^'\"]+)['\"]", text)
    if not match:
        raise RuntimeError(f"{relative}: missing --require-e2e provider set")
    actual = {value.strip() for value in match.group(1).split(",") if value.strip()}
    if actual != expected:
        raise RuntimeError(
            f"{relative}: --require-e2e mismatch: expected={sorted(expected)} actual={sorted(actual)}"
        )


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
        bootstrap_path = "scripts/m24/bootstrap-windows-toolchains.ps1"
        windows_prereq_path = "scripts/m24/check-windows-prerequisites.ps1"
        windows_final_path = "scripts/m24/run-final.ps1"
        linux_final_path = "scripts/m24/run-final.sh"
        e2e_path = "scripts/m24/run-provider-e2e.py"

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
        bootstrap = read(bootstrap_path)
        windows_prereq = read(windows_prereq_path)
        windows_final = read(windows_final_path)
        linux_final = read(linux_final_path)
        e2e = read(e2e_path)

        require_enum_members(discovery_model_path, discovery_model, "Language", {"C", "CPP", "CSHARP", "GO", "RUST"})
        require_enum_members(discovery_model_path, discovery_model, "BuildSystem", {"CMAKE", "DOTNET", "GO_MODULE", "CARGO"})

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
            forbid_pattern(
                discovery_service_path,
                service,
                rf"\b{re.escape(forbidden)}\b",
                f"language-specific orchestration branch {forbidden}",
            )

        for name, value in (
            ("SCIP_CLANG_VERSION", "0.4.0"),
            ("SCIP_DOTNET_VERSION", "0.2.14"),
            ("SCIP_GO_VERSION", "0.2.7"),
            ("RUST_ANALYZER_SCIP_VERSION", "0.3.2989"),
            ("RUST_ANALYZER_SCIP_RELEASE", "2026-07-27"),
            ("RUST_ANALYZER_SCIP_COMMIT", "12c3381"),
            ("SCIP_CLANG_ID", "scip-clang"),
            ("SCIP_DOTNET_ID", "scip-dotnet"),
            ("SCIP_GO_ID", "scip-go"),
            ("RUST_ANALYZER_SCIP_ID", "rust-analyzer-scip"),
        ):
            require_assignment(catalog_path, catalog, name, value)
        for method in ("scipClang", "scipDotnet", "scipGo", "rustAnalyzerScip"):
            require_java_method_contains(
                catalog_path,
                catalog,
                method,
                r"IndexerQualification\.EXPERIMENTAL",
                f"{method} remains EXPERIMENTAL",
            )
        require_pattern(
            catalog_path,
            catalog,
            r"entry\s*\(\s*IndexerCapability\.CALL_RELATIONS\s*,\s*CapabilitySupportLevel\.UNSUPPORTED\s*\)",
            "polyglot call relations remain unsupported",
        )
        require_pattern(
            catalog_path,
            catalog,
            r"entry\s*\(\s*IndexerCapability\.INCREMENTAL_INDEXING\s*,\s*CapabilitySupportLevel\.UNSUPPORTED\s*\)",
            "polyglot incremental indexing remains unsupported",
        )

        require(conformance_path, conformance, "operationalProfileExplicit")
        require(conformance_path, conformance, "qualificationPlatforms")
        require(conformance_path, conformance, "stableIdentityBehavior")
        require(conformance_path, conformance, "provenanceBehavior")
        require(operational_path, operational, "qualificationPlatforms")
        require(operational_path, operational, "runtimeRequirements")

        # Validate executable installation semantics rather than diagnostics or formatting.
        require_pattern(
            runtime_path,
            runtime,
            r"case\s+ScipIndexerCatalog\.SCIP_CLANG_ID\s*->\s*throw\s+new\s+IllegalStateException",
            "scip-clang installation remains operator-managed",
        )
        require_pattern(
            runtime_path,
            runtime,
            r"case\s+ScipIndexerCatalog\.RUST_ANALYZER_SCIP_ID\s*->\s*throw\s+new\s+IllegalStateException",
            "rust-analyzer installation remains operator-managed",
        )
        require_pattern(
            runtime_path,
            runtime,
            r"(?s)CommandLocator\.invocation\(\s*dotnet\s*,\s*\"tool\"\s*,\s*\"install\"\s*,\s*\"--tool-path\"\s*,\s*partial\.toString\(\)\s*,\s*\"scip-dotnet\"\s*,\s*\"--version\"\s*,\s*ScipIndexerCatalog\.SCIP_DOTNET_VERSION",
            "scip-dotnet local pinned tool-path install",
        )
        require_pattern(
            runtime_path,
            runtime,
            r"(?s)CommandLocator\.invocation\(\s*go\s*,\s*\"install\"\s*,\s*\"github\.com/scip-code/scip-go/cmd/scip-go@v\"\s*\+\s*ScipIndexerCatalog\.SCIP_GO_VERSION\s*\).*?\"GOBIN\"\s*,\s*partial\.toString\(\)",
            "scip-go pinned isolated GOBIN install",
        )
        forbid_pattern(runtime_path, runtime, r"CommandLocator\.find\(\s*\"rustup\"", "implicit rustup execution")
        forbid_pattern(runtime_path, runtime, r"(?s)CommandLocator\.invocation\([^)]*\"(?:-g|--global)\"", "global tool installation")

        # Explicit provider override is the only M24 path allowed to exercise EXPERIMENTAL providers.
        require_pattern(indexing_path, indexing, r"IndexingRequirements\s+baselineRequirements\s*=\s*IndexingRequirements\.baseline\s*\(\s*\)", "baseline indexing requirements")
        require_pattern(indexing_path, indexing, r"new\s+IndexingRequirements\s*\(\s*baselineRequirements\.requiredCapabilities\s*\(\s*\)\s*,\s*true\s*\)", "explicit override allows EXPERIMENTAL provider negotiation")

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

        # Reproducible Windows bootstrap: no package manager/admin assumption and
        # no persistent PATH mutation. Downloads are pinned/verified upstream.
        for name, value in (
            ("GoVersion", "1.26.4"),
            ("GoSha256", "3ca8fb4630b07c419cbdd51f754e31363cfcfb83b3a5354d9e895c90be2cc345"),
            ("RustVersion", "1.97.1"),
            ("RustHost", "x86_64-pc-windows-gnu"),
            ("RustAnalyzerRelease", "2026-07-27"),
            ("RustAnalyzerVersion", "0.3.2989"),
            ("RustAnalyzerCommit", "12c3381"),
        ):
            require_assignment(bootstrap_path, bootstrap, name, value)
        require_pattern(bootstrap_path, bootstrap, r"\$RustupShaUrl\s*=\s*\"\$RustupUrl\.sha256\"", "official rustup SHA sidecar URL")
        require_pattern(bootstrap_path, bootstrap, r"Assert-Sha256\s+\$RustupInit\s+\$RustupExpectedSha", "rustup installer SHA verification")
        require_pattern(bootstrap_path, bootstrap, r"Get-FileHash\s+-Algorithm\s+SHA256", "SHA-256 implementation")
        require_pattern(bootstrap_path, bootstrap, r"api\.github\.com/repos/rust-lang/rust-analyzer/releases/tags/\$RustAnalyzerRelease", "pinned rust-analyzer release lookup")
        require_pattern(
            bootstrap_path,
            bootstrap,
            r"(?s)\$RustAnalyzerReady\s*=.*?\$RustAnalyzerVersion.*?-and.*?\$RustAnalyzerRelease.*?-and.*?\$RustAnalyzerCommit",
            "existing rust-analyzer requires version, release and commit",
        )
        for token in ("M24 WINDOWS TOOLCHAIN BOOTSTRAP SUCCESS", "No administrator rights, WinGet, MSI installation, user PATH mutation"):
            require(bootstrap_path, bootstrap, token)
        forbid_pattern(bootstrap_path, bootstrap, r"(?i)\bwinget(?:\.exe)?\s+install\b", "WinGet installation")
        forbid_pattern(bootstrap_path, bootstrap, r"(?i)\bsetx(?:\.exe)?\s+PATH\b", "persistent PATH mutation")

        # Windows preflight is support-matrix aware: never force .NET 10 onto an
        # unsupported Windows 10 host, but keep Go/Rust qualification mandatory.
        for name, value in (
            ("RequiredRustAnalyzerVersion", "0.3.2989"),
            ("RequiredRustAnalyzerRelease", "2026-07-27"),
            ("RequiredRustAnalyzerCommit", "12c3381"),
        ):
            require_assignment(windows_prereq_path, windows_prereq, name, value)
        for token in (
            "M24 WINDOWS PREREQUISITES SUCCESS",
            "MINOS_SEMANTIC_PROVIDER",
            "Test-Dotnet10SupportedWindowsHost",
            "dotnet.exe",
            "go.exe",
            "cargo.exe",
            "rustc.exe",
            "rust-analyzer.exe",
        ):
            require(windows_prereq_path, windows_prereq, token)
        require_pattern(windows_prereq_path, windows_prereq, r"\$Build\s+-ge\s+22000", "Windows 11 .NET 10 support branch")
        require_pattern(windows_prereq_path, windows_prereq, r"\$EnterpriseLike\s+-and\s+\$SupportedWindows10Build", "supported Windows 10 Enterprise/IoT matrix")
        require_pattern(
            windows_prereq_path,
            windows_prereq,
            r"(?s)\$RustAnalyzerVersion\s+-notmatch.*?\$RequiredRustAnalyzerVersion.*?-or.*?\$RequiredRustAnalyzerRelease.*?-or.*?\$RequiredRustAnalyzerCommit",
            "Windows rust-analyzer requires version, release and commit",
        )
        require(windows_final_path, windows_final, "check-windows-prerequisites.ps1")
        require_e2e_set(windows_final_path, windows_final, {"scip-go", "rust-analyzer-scip"})

        # Shared evaluator and Linux runner must fail closed on required provider evidence.
        for token in (
            "windows_dotnet10_supported",
            '"--require-e2e"',
            "required provider e2e did not pass",
            "Windows 10 Pro 22H2",
        ):
            require(e2e_path, e2e, token)
        for name, value in (
            ("RUST_ANALYZER_VERSION", "0.3.2989"),
            ("RUST_ANALYZER_RELEASE", "2026-07-27"),
            ("RUST_ANALYZER_COMMIT", "12c3381"),
        ):
            require_pattern(
                linux_final_path,
                linux_final,
                rf"(?m)^\s*{name}\s*=\s*['\"]{re.escape(value)}['\"]",
                f"{name}={value}",
            )
        require_pattern(linux_final_path, linux_final, r"\[\[\s*\"\$clang_version\"\s*==\s*\*\"0\.4\.0\"\*\s*\]\]", "scip-clang 0.4.0 runtime pin")
        require_pattern(linux_final_path, linux_final, r"\(\(\s*dotnet_major\s*>=\s*10\s*\)\)", ".NET SDK 10+ requirement")
        require_pattern(
            linux_final_path,
            linux_final,
            r"(?s)\[\[\s*\"\$rust_analyzer_version\".*?\$RUST_ANALYZER_VERSION.*?&&.*?\$RUST_ANALYZER_RELEASE.*?&&.*?\$RUST_ANALYZER_COMMIT.*?\]\]",
            "Linux rust-analyzer requires version, release and commit",
        )
        require_e2e_set(linux_final_path, linux_final, {"scip-clang", "scip-dotnet", "scip-go", "rust-analyzer-scip"})

        # Final runners/docs are part of S8/S9 and must exist before this gate can pass.
        for required in (
            "scripts/m24/bootstrap-windows-toolchains.ps1",
            "scripts/m24/check-windows-prerequisites.ps1",
            "scripts/m24/run-final.ps1",
            "scripts/m24/run-final.sh",
            "scripts/m24/run-provider-e2e.py",
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

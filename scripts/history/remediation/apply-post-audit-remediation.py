#!/usr/bin/env python3
"""Apply immutable post-audit supply-chain provenance to product/runtime files only."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVENANCE = ROOT / "scripts/remediation/post-audit-provenance.env"


def provenance() -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in PROVENANCE.read_text(encoding="utf-8").splitlines():
        if raw and not raw.startswith("ACTION_PIN::"):
            key, value = raw.split("=", 1)
            values[key] = value
    required = {
        "COURSIER_LAUNCHERS_COMMIT", "COURSIER_WINDOWS_SHA256", "COURSIER_LINUX_SHA256",
        "SCIP_CLANG_LINUX_SHA256", "RUST_ANALYZER_LINUX_GZ_SHA256",
        "RUST_BASE_IMAGE", "GO_BASE_IMAGE", "DOTNET_BASE_IMAGE", "TEMURIN_BASE_IMAGE",
    }
    missing = required - values.keys()
    if missing:
        raise RuntimeError(f"missing provenance: {sorted(missing)}")
    for key in ("COURSIER_WINDOWS_SHA256", "COURSIER_LINUX_SHA256", "SCIP_CLANG_LINUX_SHA256", "RUST_ANALYZER_LINUX_GZ_SHA256"):
        if not re.fullmatch(r"[0-9a-f]{64}", values[key]):
            raise RuntimeError(f"invalid digest: {key}")
    if not re.fullmatch(r"[0-9a-f]{40}", values["COURSIER_LAUNCHERS_COMMIT"]):
        raise RuntimeError("invalid coursier launcher commit")
    return values


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, text: str) -> None:
    (ROOT / relative).write_text(text, encoding="utf-8", newline="\n")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if text.count(old) != 1:
        raise RuntimeError(f"{label}: source drift")
    return text.replace(old, new, 1)


def managed_coursier(v: dict[str, str]) -> None:
    relative = "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java"
    text = read(relative)
    if "import java.security.MessageDigest;" not in text:
        text = text.replace("import java.nio.file.StandardCopyOption;\n", "import java.nio.file.StandardCopyOption;\nimport java.security.MessageDigest;\nimport java.security.NoSuchAlgorithmException;\n", 1)
    if "import java.util.HexFormat;" not in text:
        text = text.replace("import java.util.Comparator;\n", "import java.util.Comparator;\nimport java.util.HexFormat;\n", 1)

    old = '''    private static final String COURSIER_LAUNCHER_ID = "windows-x64-official-launcher";\n    private static final URI COURSIER_WINDOWS_URI = URI.create(\n            "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-win32.zip");'''
    new = f'''    private static final String COURSIER_LAUNCHERS_COMMIT = "{v['COURSIER_LAUNCHERS_COMMIT']}";\n    private static final String COURSIER_LAUNCHER_ID = "windows-x64-" + COURSIER_LAUNCHERS_COMMIT.substring(0, 12);\n    private static final String COURSIER_WINDOWS_SHA256 = "{v['COURSIER_WINDOWS_SHA256']}";\n    private static final URI COURSIER_WINDOWS_URI = URI.create(\n            "https://raw.githubusercontent.com/coursier/launchers/" + COURSIER_LAUNCHERS_COMMIT\n                    + "/cs-x86_64-pc-win32.zip");'''
    text = replace_once(text, old, new, "Coursier immutable source")

    old = '''        if (response.statusCode() < 200 || response.statusCode() >= 300 || Files.size(archivePartial) == 0L) {\n            Files.deleteIfExists(archivePartial);\n            throw new IllegalStateException("Coursier launcher download failed with HTTP " + response.statusCode());\n        }\n        move(archivePartial, archive);'''
    new = '''        if (response.statusCode() < 200 || response.statusCode() >= 300 || Files.size(archivePartial) == 0L) {\n            Files.deleteIfExists(archivePartial);\n            throw new IllegalStateException("Coursier launcher download failed with HTTP " + response.statusCode());\n        }\n        String actualDigest = sha256(archivePartial);\n        if (!COURSIER_WINDOWS_SHA256.equals(actualDigest)) {\n            Files.deleteIfExists(archivePartial);\n            throw new IllegalStateException("Coursier launcher checksum mismatch: expected="\n                    + COURSIER_WINDOWS_SHA256 + " actual=" + actualDigest);\n        }\n        move(archivePartial, archive);'''
    text = replace_once(text, old, new, "Coursier digest gate")

    old = '''        boolean extracted = false;\n        try (InputStream input = Files.newInputStream(archive);\n             ZipInputStream zip = new ZipInputStream(input)) {\n            ZipEntry entry;\n            while ((entry = zip.getNextEntry()) != null) {\n                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".exe")) {\n                    Files.copy(zip, executablePartial, StandardCopyOption.REPLACE_EXISTING);\n                    extracted = true;\n                    break;\n                }\n            }\n        }\n        if (!extracted || !Files.isRegularFile(executablePartial) || Files.size(executablePartial) == 0L) {'''
    new = '''        int executableEntries = 0;\n        try (InputStream input = Files.newInputStream(archive);\n             ZipInputStream zip = new ZipInputStream(input)) {\n            ZipEntry entry;\n            while ((entry = zip.getNextEntry()) != null) {\n                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".exe")) {\n                    executableEntries++;\n                    if (executableEntries == 1) {\n                        Files.copy(zip, executablePartial, StandardCopyOption.REPLACE_EXISTING);\n                    }\n                }\n            }\n        }\n        if (executableEntries != 1 || !Files.isRegularFile(executablePartial) || Files.size(executablePartial) == 0L) {'''
    text = replace_once(text, old, new, "Coursier archive gate")

    helper = '''    private static String sha256(Path file) throws IOException {\n        try {\n            MessageDigest digest = MessageDigest.getInstance("SHA-256");\n            try (InputStream input = Files.newInputStream(file)) {\n                byte[] buffer = new byte[8192];\n                int read;\n                while ((read = input.read(buffer)) >= 0) {\n                    if (read > 0) digest.update(buffer, 0, read);\n                }\n            }\n            return HexFormat.of().formatHex(digest.digest());\n        } catch (NoSuchAlgorithmException exception) {\n            throw new IllegalStateException("SHA-256 is unavailable", exception);\n        }\n    }\n\n'''
    if helper not in text:
        marker = "    private Optional<Path> coursierExecutable() {\n"
        if text.count(marker) != 1:
            raise RuntimeError("Coursier helper insertion point drift")
        text = text.replace(marker, helper + marker, 1)
    write(relative, text)


def docker(v: dict[str, str]) -> None:
    relative = "docker/Dockerfile.mcp.release"
    text = read(relative)
    for old, new in {
        "FROM rust:1.97.1-bookworm AS rust-toolchain": f"FROM {v['RUST_BASE_IMAGE']} AS rust-toolchain # rust:1.97.1-bookworm",
        "FROM golang:1.26.5-bookworm AS go-toolchain": f"FROM {v['GO_BASE_IMAGE']} AS go-toolchain # golang:1.26.5-bookworm",
        "FROM mcr.microsoft.com/dotnet/sdk:10.0.302-noble AS dotnet-toolchain": f"FROM {v['DOTNET_BASE_IMAGE']} AS dotnet-toolchain # 10.0.302-noble",
        "FROM eclipse-temurin:24-jdk": f"FROM {v['TEMURIN_BASE_IMAGE']} # eclipse-temurin:24-jdk",
    }.items():
        text = replace_once(text, old, new, old)

    text = replace_once(text, "ARG COURSIER_VERSION=2.1.25-M26\n",
        f"ARG COURSIER_LAUNCHERS_COMMIT={v['COURSIER_LAUNCHERS_COMMIT']}\n"
        f"ARG COURSIER_LINUX_SHA256={v['COURSIER_LINUX_SHA256']}\n"
        f"ARG SCIP_CLANG_LINUX_SHA256={v['SCIP_CLANG_LINUX_SHA256']}\n"
        f"ARG RUST_ANALYZER_LINUX_GZ_SHA256={v['RUST_ANALYZER_LINUX_GZ_SHA256']}\n",
        "Docker provenance args")
    text = replace_once(text,
        '    curl -fsSLo /tmp/cs.gz "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux.gz"; \\\n    gunzip /tmp/cs.gz;',
        '    curl -fsSLo /tmp/cs.gz "https://raw.githubusercontent.com/coursier/launchers/${COURSIER_LAUNCHERS_COMMIT}/cs-x86_64-pc-linux.gz"; \\\n    printf \'%s  %s\\n\' "${COURSIER_LINUX_SHA256}" /tmp/cs.gz | sha256sum -c -; \\\n    gunzip /tmp/cs.gz;', "Docker Coursier")
    text = replace_once(text,
        '    curl -fsSLo /tmp/scip-clang "https://github.com/sourcegraph/scip-clang/releases/download/v${SCIP_CLANG_VERSION}/scip-clang-x86_64-linux"; \\\n    install -m 0755 /tmp/scip-clang /usr/local/bin/scip-clang;',
        '    curl -fsSLo /tmp/scip-clang "https://github.com/sourcegraph/scip-clang/releases/download/v${SCIP_CLANG_VERSION}/scip-clang-x86_64-linux"; \\\n    printf \'%s  %s\\n\' "${SCIP_CLANG_LINUX_SHA256}" /tmp/scip-clang | sha256sum -c -; \\\n    install -m 0755 /tmp/scip-clang /usr/local/bin/scip-clang;', "Docker scip-clang")
    text = replace_once(text,
        '    curl -fsSLo /tmp/rust-analyzer.gz "https://github.com/rust-lang/rust-analyzer/releases/download/${RUST_ANALYZER_RELEASE}/rust-analyzer-x86_64-unknown-linux-gnu.gz"; \\\n    gunzip /tmp/rust-analyzer.gz;',
        '    curl -fsSLo /tmp/rust-analyzer.gz "https://github.com/rust-lang/rust-analyzer/releases/download/${RUST_ANALYZER_RELEASE}/rust-analyzer-x86_64-unknown-linux-gnu.gz"; \\\n    printf \'%s  %s\\n\' "${RUST_ANALYZER_LINUX_GZ_SHA256}" /tmp/rust-analyzer.gz | sha256sum -c -; \\\n    gunzip /tmp/rust-analyzer.gz;', "Docker rust-analyzer")
    write(relative, text)


def main() -> int:
    values = provenance()
    managed_coursier(values)
    docker(values)
    if "coursier/launchers/raw/master" in read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java"):
        raise RuntimeError("mutable Coursier launcher remains")
    print("POST-AUDIT PRODUCT SUPPLY-CHAIN HARDENING SUCCESS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

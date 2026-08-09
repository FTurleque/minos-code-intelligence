#!/usr/bin/env python3
"""Apply the immutable supply-chain provenance captured for post-audit remediation.

The transformation is idempotent and fail-closed: mutable upstream references or
unexpected source drift abort the job rather than weakening a security boundary.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVENANCE = ROOT / "scripts/remediation/post-audit-provenance.env"


def load_provenance() -> tuple[dict[str, str], dict[tuple[str, str], str]]:
    values: dict[str, str] = {}
    actions: dict[tuple[str, str], str] = {}
    for raw in PROVENANCE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("ACTION_PIN::"):
            left, sha = line.split("=", 1)
            repository, ref = left.removeprefix("ACTION_PIN::").rsplit("@", 1)
            if not re.fullmatch(r"[0-9a-f]{40}", sha):
                raise RuntimeError(f"invalid Action SHA for {repository}@{ref}")
            actions[(repository, ref)] = sha
            continue
        key, value = line.split("=", 1)
        values[key] = value
    required = {
        "COURSIER_LAUNCHERS_COMMIT", "COURSIER_WINDOWS_SHA256", "COURSIER_LINUX_SHA256",
        "SCIP_CLANG_LINUX_SHA256", "RUST_ANALYZER_LINUX_GZ_SHA256",
        "RUST_BASE_IMAGE", "GO_BASE_IMAGE", "DOTNET_BASE_IMAGE", "TEMURIN_BASE_IMAGE",
        "INNOSETUP_CHOCO_VERSION",
    }
    missing = sorted(required - values.keys())
    if missing:
        raise RuntimeError(f"missing provenance keys: {missing}")
    for key in ("COURSIER_WINDOWS_SHA256", "COURSIER_LINUX_SHA256", "SCIP_CLANG_LINUX_SHA256", "RUST_ANALYZER_LINUX_GZ_SHA256"):
        if not re.fullmatch(r"[0-9a-f]{64}", values[key]):
            raise RuntimeError(f"invalid SHA-256 for {key}")
    if not re.fullmatch(r"[0-9a-f]{40}", values["COURSIER_LAUNCHERS_COMMIT"]):
        raise RuntimeError("invalid coursier/launchers commit")
    return values, actions


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, content: str) -> None:
    (ROOT / relative).write_text(content, encoding="utf-8", newline="\n")


def replace_required(content: str, old: str, new: str, label: str) -> str:
    if new in content:
        return content
    if content.count(old) != 1:
        raise RuntimeError(f"{label}: expected exactly one source marker")
    return content.replace(old, new, 1)


def harden_managed_coursier(values: dict[str, str]) -> None:
    relative = "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java"
    source = read(relative)
    commit = values["COURSIER_LAUNCHERS_COMMIT"]
    digest = values["COURSIER_WINDOWS_SHA256"]

    if "import java.security.MessageDigest;" not in source:
        marker = "import java.nio.file.StandardCopyOption;\n"
        if marker not in source:
            raise RuntimeError("ManagedScipProviderRuntimeManager imports drifted")
        source = source.replace(marker, marker + "import java.security.MessageDigest;\nimport java.security.NoSuchAlgorithmException;\n", 1)
    if "import java.util.HexFormat;" not in source:
        marker = "import java.util.Comparator;\n"
        if marker not in source:
            raise RuntimeError("ManagedScipProviderRuntimeManager util imports drifted")
        source = source.replace(marker, marker + "import java.util.HexFormat;\n", 1)

    old_constants = '''    private static final String COURSIER_LAUNCHER_ID = "windows-x64-official-launcher";\n    private static final URI COURSIER_WINDOWS_URI = URI.create(\n            "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-win32.zip");'''
    new_constants = f'''    private static final String COURSIER_LAUNCHERS_COMMIT = "{commit}";\n    private static final String COURSIER_LAUNCHER_ID = "windows-x64-" + COURSIER_LAUNCHERS_COMMIT.substring(0, 12);\n    private static final String COURSIER_WINDOWS_SHA256 = "{digest}";\n    private static final URI COURSIER_WINDOWS_URI = URI.create(\n            "https://raw.githubusercontent.com/coursier/launchers/" + COURSIER_LAUNCHERS_COMMIT\n                    + "/cs-x86_64-pc-win32.zip");'''
    source = replace_required(source, old_constants, new_constants, "Coursier immutable reference")

    old_download = '''        if (response.statusCode() < 200 || response.statusCode() >= 300 || Files.size(archivePartial) == 0L) {\n            Files.deleteIfExists(archivePartial);\n            throw new IllegalStateException("Coursier launcher download failed with HTTP " + response.statusCode());\n        }\n        move(archivePartial, archive);'''
    new_download = '''        if (response.statusCode() < 200 || response.statusCode() >= 300 || Files.size(archivePartial) == 0L) {\n            Files.deleteIfExists(archivePartial);\n            throw new IllegalStateException("Coursier launcher download failed with HTTP " + response.statusCode());\n        }\n        String actualDigest = sha256(archivePartial);\n        if (!COURSIER_WINDOWS_SHA256.equals(actualDigest)) {\n            Files.deleteIfExists(archivePartial);\n            throw new IllegalStateException("Coursier launcher checksum mismatch: expected="\n                    + COURSIER_WINDOWS_SHA256 + " actual=" + actualDigest);\n        }\n        move(archivePartial, archive);'''
    source = replace_required(source, old_download, new_download, "Coursier digest verification")

    old_extract = '''        boolean extracted = false;\n        try (InputStream input = Files.newInputStream(archive);\n             ZipInputStream zip = new ZipInputStream(input)) {\n            ZipEntry entry;\n            while ((entry = zip.getNextEntry()) != null) {\n                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".exe")) {\n                    Files.copy(zip, executablePartial, StandardCopyOption.REPLACE_EXISTING);\n                    extracted = true;\n                    break;\n                }\n            }\n        }\n        if (!extracted || !Files.isRegularFile(executablePartial) || Files.size(executablePartial) == 0L) {'''
    new_extract = '''        int executableEntries = 0;\n        try (InputStream input = Files.newInputStream(archive);\n             ZipInputStream zip = new ZipInputStream(input)) {\n            ZipEntry entry;\n            while ((entry = zip.getNextEntry()) != null) {\n                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".exe")) {\n                    executableEntries++;\n                    if (executableEntries == 1) {\n                        Files.copy(zip, executablePartial, StandardCopyOption.REPLACE_EXISTING);\n                    }\n                }\n            }\n        }\n        if (executableEntries != 1 || !Files.isRegularFile(executablePartial) || Files.size(executablePartial) == 0L) {'''
    source = replace_required(source, old_extract, new_extract, "Coursier ZIP executable uniqueness")

    helper = '''    private static String sha256(Path file) throws IOException {\n        try {\n            MessageDigest digest = MessageDigest.getInstance("SHA-256");\n            try (InputStream input = Files.newInputStream(file)) {\n                byte[] buffer = new byte[8192];\n                int read;\n                while ((read = input.read(buffer)) >= 0) {\n                    if (read > 0) digest.update(buffer, 0, read);\n                }\n            }\n            return HexFormat.of().formatHex(digest.digest());\n        } catch (NoSuchAlgorithmException exception) {\n            throw new IllegalStateException("SHA-256 is unavailable", exception);\n        }\n    }\n\n'''
    if helper not in source:
        marker = "    private Optional<Path> coursierExecutable() {\n"
        if source.count(marker) != 1:
            raise RuntimeError("ManagedScipProviderRuntimeManager helper insertion point drifted")
        source = source.replace(marker, helper + marker, 1)
    write(relative, source)


def harden_docker(values: dict[str, str]) -> None:
    relative = "docker/Dockerfile.mcp.release"
    source = read(relative)
    replacements = {
        "FROM rust:1.97.1-bookworm AS rust-toolchain": f"FROM {values['RUST_BASE_IMAGE']} AS rust-toolchain # rust:1.97.1-bookworm",
        "FROM golang:1.26.5-bookworm AS go-toolchain": f"FROM {values['GO_BASE_IMAGE']} AS go-toolchain # golang:1.26.5-bookworm",
        "FROM mcr.microsoft.com/dotnet/sdk:10.0.302-noble AS dotnet-toolchain": f"FROM {values['DOTNET_BASE_IMAGE']} AS dotnet-toolchain # 10.0.302-noble",
        "FROM eclipse-temurin:24-jdk": f"FROM {values['TEMURIN_BASE_IMAGE']} # eclipse-temurin:24-jdk",
    }
    for old, new in replacements.items():
        if new not in source:
            if source.count(old) != 1:
                raise RuntimeError(f"Docker base image marker drifted: {old}")
            source = source.replace(old, new, 1)

    args_old = "ARG COURSIER_VERSION=2.1.25-M26\n"
    args_new = (f"ARG COURSIER_LAUNCHERS_COMMIT={values['COURSIER_LAUNCHERS_COMMIT']}\n"
                f"ARG COURSIER_LINUX_SHA256={values['COURSIER_LINUX_SHA256']}\n"
                f"ARG SCIP_CLANG_LINUX_SHA256={values['SCIP_CLANG_LINUX_SHA256']}\n"
                f"ARG RUST_ANALYZER_LINUX_GZ_SHA256={values['RUST_ANALYZER_LINUX_GZ_SHA256']}\n")
    source = replace_required(source, args_old, args_new, "Docker digest ARGs")

    source = replace_required(
        source,
        '    curl -fsSLo /tmp/cs.gz "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux.gz"; \\\n    gunzip /tmp/cs.gz;',
        '    curl -fsSLo /tmp/cs.gz "https://raw.githubusercontent.com/coursier/launchers/${COURSIER_LAUNCHERS_COMMIT}/cs-x86_64-pc-linux.gz"; \\\n    printf \'%s  %s\\n\' "${COURSIER_LINUX_SHA256}" /tmp/cs.gz | sha256sum -c -; \\\n    gunzip /tmp/cs.gz;',
        "Docker Coursier verification",
    )
    source = replace_required(
        source,
        '    curl -fsSLo /tmp/scip-clang "https://github.com/sourcegraph/scip-clang/releases/download/v${SCIP_CLANG_VERSION}/scip-clang-x86_64-linux"; \\\n    install -m 0755 /tmp/scip-clang /usr/local/bin/scip-clang;',
        '    curl -fsSLo /tmp/scip-clang "https://github.com/sourcegraph/scip-clang/releases/download/v${SCIP_CLANG_VERSION}/scip-clang-x86_64-linux"; \\\n    printf \'%s  %s\\n\' "${SCIP_CLANG_LINUX_SHA256}" /tmp/scip-clang | sha256sum -c -; \\\n    install -m 0755 /tmp/scip-clang /usr/local/bin/scip-clang;',
        "Docker scip-clang verification",
    )
    source = replace_required(
        source,
        '    curl -fsSLo /tmp/rust-analyzer.gz "https://github.com/rust-lang/rust-analyzer/releases/download/${RUST_ANALYZER_RELEASE}/rust-analyzer-x86_64-unknown-linux-gnu.gz"; \\\n    gunzip /tmp/rust-analyzer.gz;',
        '    curl -fsSLo /tmp/rust-analyzer.gz "https://github.com/rust-lang/rust-analyzer/releases/download/${RUST_ANALYZER_RELEASE}/rust-analyzer-x86_64-unknown-linux-gnu.gz"; \\\n    printf \'%s  %s\\n\' "${RUST_ANALYZER_LINUX_GZ_SHA256}" /tmp/rust-analyzer.gz | sha256sum -c -; \\\n    gunzip /tmp/rust-analyzer.gz;',
        "Docker rust-analyzer verification",
    )
    write(relative, source)


def pin_workflows(values: dict[str, str], actions: dict[tuple[str, str], str]) -> None:
    pattern = re.compile(r"(?P<prefix>\buses:\s*)(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@(?P<ref>[^\s#]+)(?P<comment>\s*#.*)?$")
    inno_version = values["INNOSETUP_CHOCO_VERSION"]
    for workflow in sorted((ROOT / ".github/workflows").glob("*.y*ml")):
        lines = workflow.read_text(encoding="utf-8").splitlines()
        rendered: list[str] = []
        changed = False
        for line in lines:
            match = pattern.search(line)
            if match and not re.fullmatch(r"[0-9a-f]{40}", match.group("ref")):
                key = (match.group("repo"), match.group("ref"))
                sha = actions.get(key)
                if sha is None:
                    raise RuntimeError(f"un-pinned Action missing from provenance: {key[0]}@{key[1]}")
                line = pattern.sub(lambda m: f"{m.group('prefix')}{key[0]}@{sha} # {key[1]}", line)
                changed = True
            old_inno = "choco install innosetup --yes --no-progress"
            new_inno = f"choco install innosetup --version={inno_version} --yes --no-progress"
            if old_inno in line:
                line = line.replace(old_inno, new_inno)
                changed = True
            rendered.append(line)
        if changed:
            workflow.write_text("\n".join(rendered) + "\n", encoding="utf-8", newline="\n")


def verify_no_mutable_supply_chain_refs() -> None:
    java = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java")
    docker = read("docker/Dockerfile.mcp.release")
    if "coursier/launchers/raw/master" in java or "coursier/coursier/releases/download/v${COURSIER_VERSION}" in docker:
        raise RuntimeError("mutable/obsolete Coursier source remains")
    for workflow in (ROOT / ".github/workflows").glob("*.y*ml"):
        for match in re.finditer(r"\buses:\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)@([^\s#]+)", workflow.read_text(encoding="utf-8")):
            if not re.fullmatch(r"[0-9a-f]{40}", match.group(2)):
                raise RuntimeError(f"mutable Action remains in {workflow}: {match.group(0)}")


def main() -> int:
    values, actions = load_provenance()
    harden_managed_coursier(values)
    harden_docker(values)
    pin_workflows(values, actions)
    verify_no_mutable_supply_chain_refs()
    print("POST-AUDIT SUPPLY-CHAIN HARDENING SUCCESS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

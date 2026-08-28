#!/usr/bin/env python3
"""Fail closed if the 2026-08-28 audit-v2 remediations drift out of the repository."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        raise RuntimeError(f"missing audit-v2 artifact: {path}")
    return target.read_text(encoding="utf-8")


def require(path: str, *tokens: str) -> str:
    text = read(path)
    folded = text.casefold()
    for token in tokens:
        if token.casefold() not in folded:
            raise RuntimeError(f"{path}: missing audit-v2 invariant: {token}")
    return text


def forbid(path: str, *tokens: str) -> None:
    text = read(path).casefold()
    for token in tokens:
        if token.casefold() in text:
            raise RuntimeError(f"{path}: stale/unsafe audit-v2 text remains: {token}")


def main() -> int:
    try:
        require(
            ".github/dependabot.yml",
            'package-ecosystem: "maven"',
            'package-ecosystem: "gradle"',
            'directory: "/minos-intellij"',
            'package-ecosystem: "github-actions"',
        )
        require(
            "docs/TOOLCHAIN_POLICY.md",
            "Java: **24**",
            "Java: **21**",
            "Gradle: **9.6.1**",
            "IntelliJ Platform: **2026.1**",
            "Dependabot covers Maven, the `minos-intellij` Gradle build, and GitHub Actions",
        )

        require(
            "docs/STATUS.md",
            "28 août 2026",
            "PR Validation",
            "gate statique ciblé Ubuntu",
            "Docker MCP A → B",
            "Dependabot couvre Maven, GitHub Actions **et le build Gradle `minos-intellij`**",
            "main` doit être ancêtre",
        )
        require(
            "docs/ROADMAP.md",
            "28 août 2026",
            "Post-228 — invariants statiques ciblés",
            "Docker release et upgrade réel",
            "deux commits/JAR distincts",
            "main ⊆ develop",
        )
        forbid("docs/ROADMAP.md", "Vérification manuelle encore ouverte")

        post228 = require(
            ".github/workflows/post-228-hardening.yml",
            "Verify post-228 static hardening",
            "runs-on: ubuntu-24.04",
            "check-current-docs.py",
            "check-post228-hardening.py",
        )
        for stale in ("mvnw", "check-jacoco.py", "windows-2022", "matrix:"):
            if stale.casefold() in post228.casefold():
                raise RuntimeError(f"post-228 workflow duplicated current PR validation responsibility: {stale}")

        require(
            ".github/workflows/pr-ci.yml",
            "Require main ancestry in every candidate",
            "git merge-base --is-ancestor origin/main HEAD",
            "Audit remediation v2 invariants",
            "check-audit-remediation-v2.py",
            "Maven clean verify (Unix, PostgreSQL required)",
            "Maven clean verify (Windows)",
            "Targeted JaCoCo gate (full)",
        )

        dockerfile = require("docker/Dockerfile.mcp", "FROM eclipse-temurin@sha256:")
        if "FROM eclipse-temurin:24-jre" in dockerfile:
            raise RuntimeError("docker/Dockerfile.mcp still uses a floating 24-jre tag")

        require(
            "minos-engine/src/main/java/com/minos/io/BoundedOutputStream.java",
            "refuses the write which would cross a hard byte ceiling",
            "requireCapacity",
        )
        require(
            "minos-storage-local/src/main/java/com/minos/store/SnapshotBinaryCodecSupport.java",
            "BoundedInputStream",
            "BoundedOutputStream",
            "MAX_PERSISTED_SNAPSHOT_BYTES",
            "Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)",
        )
        require(
            "minos-engine/src/main/java/com/minos/io/BoundedProperties.java",
            "CodingErrorAction.REPORT",
            "public static String readUtf8(InputStream source",
        )
        require(
            "minos-application/src/main/java/com/minos/storage/MinosRuntimeSettings.java",
            "BoundedProperties.readUtf8(stream, MAX_SECRET_BYTES",
            "ConfinedFileOpener.openConfinedRegularFile",
        )
        forbid(
            "minos-application/src/main/java/com/minos/storage/MinosRuntimeSettings.java",
            "new String(input.readAllBytes(), StandardCharsets.UTF_8)",
        )
        require(
            "minos-storage-local/src/main/java/com/minos/store/EnvironmentHostedTenantKeyProvider.java",
            "if (derived != null) Arrays.fill(derived, (byte) 0)",
            "Arrays.fill(master, (byte) 0)",
        )

        require(
            ".github/workflows/docker-upgrade-qualification.yml",
            "workflow_dispatch",
            "self-hosted",
            "minos-docker",
            "qualify-docker-upgrade.ps1",
        )
        require(
            "scripts/ci/qualify-docker-upgrade.ps1",
            "Previous and candidate commits must differ",
            "prod-mcp-release.ps1",
            "project', 'add'",
            "'index', 'upgrade-fixture'",
            "MinosDockerMcpSmoke.java",
            "Persistent MINOS data sentinel was not preserved",
            "Deliberately broken next Docker candidate unexpectedly installed",
            "failedNextCandidatePreservedB",
        )

        print("MINOS AUDIT REMEDIATION V2 INVARIANTS SUCCESS")
        return 0
    except Exception as exc:
        print(f"MINOS AUDIT REMEDIATION V2 INVARIANTS FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

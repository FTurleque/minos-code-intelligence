#!/usr/bin/env python3
"""Validate the authoritative MINOS current-state documentation and release contracts."""

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


def normalized(value: str) -> str:
    value = value.replace("\ufeff", "").replace("\u00a0", " ")
    value = re.sub(r"[`*_]+", "", value)
    return re.sub(r"\s+", " ", value).strip().casefold()


def require(relative: str, text: str, expected: str) -> None:
    if normalized(expected) not in normalized(text):
        raise RuntimeError(f"{relative}: missing current fact: {expected}")


def forbid(relative: str, text: str, stale: str) -> None:
    if normalized(stale) in normalized(text):
        raise RuntimeError(f"{relative}: stale current-state text is forbidden: {stale}")


def require_regex(relative: str, text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.IGNORECASE | re.MULTILINE):
        raise RuntimeError(f"{relative}: missing {label}")


def main() -> int:
    try:
        current_files = {
            "README.md": read("README.md"),
            "docs/STATUS.md": read("docs/STATUS.md"),
            "docs/ROADMAP.md": read("docs/ROADMAP.md"),
            "docs/roadmap/M21_EXECUTION.md": read("docs/roadmap/M21_EXECUTION.md"),
            "docs/roadmap/M21_S2_AUGUST_RECOVERY.md": read("docs/roadmap/M21_S2_AUGUST_RECOVERY.md"),
            "docs/roadmap/M28_EXECUTION.md": read("docs/roadmap/M28_EXECUTION.md"),
            "docs/user/production-installation.md": read("docs/user/production-installation.md"),
            "docs/releases/1.0.0.md": read("docs/releases/1.0.0.md"),
            "docs/releases/1.0.1.md": read("docs/releases/1.0.1.md"),
        }

        readme = current_files["README.md"]
        status = current_files["docs/STATUS.md"]
        roadmap = current_files["docs/ROADMAP.md"]
        m21 = current_files["docs/roadmap/M21_EXECUTION.md"]
        m21_recovery = current_files["docs/roadmap/M21_S2_AUGUST_RECOVERY.md"]
        m28 = current_files["docs/roadmap/M28_EXECUTION.md"]
        install = current_files["docs/user/production-installation.md"]
        release_100 = current_files["docs/releases/1.0.0.md"]
        release_101 = current_files["docs/releases/1.0.1.md"]

        # Authoritative product state.
        for relative, text in (("README.md", readme), ("docs/STATUS.md", status), ("docs/ROADMAP.md", roadmap)):
            require(relative, text, "C0 → M28")
            require(relative, text, "1.0.0")
            require(relative, text, "1.0.1")
            require(relative, text, "#98")

        require("README.md", readme, "NON PUBLIÉ")
        require("docs/STATUS.md", status, "#73 CLOSED / completed")
        require("docs/STATUS.md", status, "#93 CLOSED / completed")
        require("docs/STATUS.md", status, "PR de promotion #102")
        require("docs/STATUS.md", status, "1adbc45339efe37cd26d1937025bfa69d7b57811")
        require("docs/ROADMAP.md", roadmap, "PR promotion")
        require("docs/ROADMAP.md", roadmap, "#93 CLOSED / completed")

        # M21/M28 final dispositions must not regress to pre-promotion status.
        require("docs/roadmap/M21_EXECUTION.md", m21, "TERMINÉ")
        require("docs/roadmap/M21_EXECUTION.md", m21, "#73 CLOSED / completed")
        require("docs/roadmap/M21_EXECUTION.md", m21, "PASS_WITH_CONSTRAINTS")
        require("docs/roadmap/M21_S2_AUGUST_RECOVERY.md", m21_recovery, "#102")
        require("docs/roadmap/M21_S2_AUGUST_RECOVERY.md", m21_recovery, "MERGED")
        require("docs/roadmap/M28_EXECUTION.md", m28, "#93 CLOSED / completed")
        require("docs/roadmap/M28_EXECUTION.md", m28, "PR #102 MERGED")
        require("docs/roadmap/M28_EXECUTION.md", m28, "v1.0.0")

        stale_current = (
            "release 0.2.0 candidate",
            "#73 OPEN",
            "PR #102 OPEN",
            "PR #102 candidate",
            "État livré sur `main` : C0→M20",
            "S2 reste le seul sous-incrément ouvert",
            "M28-S9 — PARTIEL / PENDING",
        )
        for relative, text in current_files.items():
            for stale in stale_current:
                forbid(relative, text, stale)

        # Release history is explicit: 1.0.0 immutable, 1.0.1 not yet published.
        require("docs/releases/1.0.0.md", release_100, "Tag target")
        require("docs/releases/1.0.0.md", release_100, "NoClassDefFoundError: org/w3c/dom/Node")
        require("docs/releases/1.0.0.md", release_100, "ne pas modifier ni retagger")
        require("docs/releases/1.0.1.md", release_101, "NON PUBLIÉ")
        require("docs/releases/1.0.1.md", release_101, "jdeps")
        require("docs/releases/1.0.1.md", release_101, "initialize")
        require("docs/releases/1.0.1.md", release_101, "Codex Desktop")

        # Windows user contract.
        for token in (
            "Intégrations MCP natives",
            "clients IA détectés",
            "launcher VS Code détecté",
            "Codex Desktop",
            "RUNTIME-MODULES.txt",
            "java.xml",
            "build-local-windows-candidate.ps1",
        ):
            require("docs/user/production-installation.md", install, token)
        require_regex(
            "docs/user/production-installation.md",
            install,
            r"MINOS-<version>-THIRD-PARTY-NOTICES\.txt",
            "complete Windows release asset list",
        )

        # Source/release contracts must agree with docs.
        pom = read("pom.xml")
        minos_version = read("minos-application/src/main/java/com/minos/runtime/MinosVersion.java")
        build_distribution = read("scripts/release/build-windows-distribution.ps1")
        build_installer = read("scripts/release/build-windows-installer.ps1")
        publish = read("scripts/release/publish-windows-release.ps1")
        local_candidate = read("scripts/release/build-local-windows-candidate.ps1")
        installer = read("packaging/windows/minos-installer.iss.template")
        release_workflow = read(".github/workflows/release-windows.yml")

        require("pom.xml", pom, "<revision>1.0.1-SNAPSHOT</revision>")
        require("pom.xml", pom, "slf4j-nop")
        require("MinosVersion.java", minos_version, 'DEVELOPMENT_VERSION = "1.0.1-SNAPSHOT"')

        for token in ("jdeps.exe", "--print-module-deps", "--list-modules", "java.xml", "RUNTIME-MODULES.txt"):
            require("build-windows-distribution.ps1", build_distribution, token)
        for token in ("[switch] $Smoke", "MINOS-Release-Smoke-", "@@SMOKE_MODE@@"):
            require("build-windows-installer.ps1", build_installer, token)
        for token in ("Invoke-McpHandshake", "MinosNativeMcpSmoke.java", "isolated smoke setup"):
            require("publish-windows-release.ps1", publish, token)
        for token in ("Publication   : NOT PERFORMED", "MINOS LOCAL WINDOWS CANDIDATE SUCCESS"):
            require("build-local-windows-candidate.ps1", local_candidate, token)
        for token in (
            "Intégrations MCP natives",
            "detect-mcp-clients.ps1",
            "ShouldRunGlobalCleanup",
            "CodexMode",
        ):
            require("minos-installer.iss.template", installer, token)
        for stale_task in ("mcp_copilot_jetbrains", "mcp_copilot_cli", "mcp_claude_code", "mcp_claude_desktop", "mcp_codex"):
            forbid("minos-installer.iss.template", installer, stale_task)

        require("release-windows.yml", release_workflow, "default: '1.0.1'")
        require("release-windows.yml", release_workflow, "workflow_dispatch")

        # The completed one-shot publisher must no longer be part of the current branch.
        if (ROOT / ".github/workflows/release-v1.0.0.yml").exists():
            raise RuntimeError("completed one-shot release-v1.0.0.yml must not remain on the maintenance line")

        print("MINOS CURRENT DOCUMENTATION AND RELEASE CONTRACT CONSISTENCY SUCCESS")
        return 0
    except Exception as exc:
        print(f"MINOS CURRENT DOCUMENTATION AND RELEASE CONTRACT CONSISTENCY FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

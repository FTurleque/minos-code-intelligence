#!/usr/bin/env python3
"""Validate durable MINOS current-state, release and installer invariants."""

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
    value = re.sub(r"[`*]+", "", value)
    value = re.sub(r"\s*:\s*", ":", value)
    return re.sub(r"\s+", " ", value).strip().casefold()


def require(relative: str, text: str, expected: str) -> None:
    if normalized(expected) not in normalized(text):
        raise RuntimeError(f"{relative}: missing current invariant: {expected}")


def require_all(relative: str, text: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        require(relative, text, token)


def forbid(relative: str, text: str, stale: str) -> None:
    if normalized(stale) in normalized(text):
        raise RuntimeError(f"{relative}: stale/unsafe current-state text is forbidden: {stale}")


def require_regex(relative: str, text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.IGNORECASE | re.MULTILINE):
        raise RuntimeError(f"{relative}: missing {label}")


def main() -> int:
    try:
        readme = read("README.md")
        status = read("docs/STATUS.md")
        roadmap = read("docs/ROADMAP.md")
        release_101 = read("docs/releases/1.0.1.md")
        production_install = read("docs/user/production-installation.md")

        # Current product/release truth.
        require_all("README.md", readme, (
            "C0 → M30", "hardening #113", "merged", "#117", "#112",
            "1.0.1", "NON PUBLI", "v1.0.1", "#98",
        ))
        require_all("docs/STATUS.md", status, (
            "M29 issue #107", "CLOSED", "M29 PR #108", "M30 PR #110", "M30 promotion PR #111",
            "hardening PR #113", "M28 Windows CI PR #117", "promotion develop → main #112",
            "v1.0.1 Windows", "NON PUBLI", "v1.0.1", "2de847bd", "#98",
        ))
        require_all("docs/ROADMAP.md", roadmap, (
            "C0 → M30", "#113", "#117", "#112", "terminé", "1.0.1", "NON PUBLI",
            "Plugin Verifier", "v1.0.1", "2de847bd", "#98",
        ))

        for relative, text in (("README.md", readme), ("docs/STATUS.md", status), ("docs/ROADMAP.md", roadmap)):
            for stale in (
                "hardening release/installer      🚧 PR #113",
                "hardening post-audit en cours sur PR #113",
                "Avant le prochain candidat 1.0.1, la branche audit/release-installer-hardening doit converger",
                "La priorité immédiate est de terminer PR #113",
                "M30 non livré",
            ):
                forbid(relative, text, stale)

        # Release/user-facing contract remains unpublished until explicit approval.
        require_all("docs/releases/1.0.1.md", release_101, (
            "PRÉ-PUBLICATION", "NON PUBLI", "Standard", "Avancée", "PostgreSQL", "pgvector", "Ollama",
            "Claude", "Codex CLI", "Codex Desktop", "OSV", "Jackson", "Plugin Verifier",
            "v1.0.1", "2de847bd", "autorisation explicite",
        ))
        require_all("docs/user/production-installation.md", production_install, (
            "Standard", "Avancée", "MCP natif Windows", "MCP Docker", "PostgreSQL", "pgvector",
            "Ollama", "Claude CLI", "Claude Desktop", "Codex CLI", "Codex Desktop",
            "Résumé", "%LOCALAPPDATA%\\MINOS", "Non / conserver",
        ))

        # Security/dependency contract.
        pom = read("pom.xml")
        require_all("pom.xml", pom, (
            "<jackson2.version>2.22.1</jackson2.version>",
            "<jackson3.version>3.1.5</jackson3.version>",
            "com.fasterxml.jackson", "tools.jackson", "jackson-bom",
        ))
        forbid("pom.xml", pom, "<jackson2.version>2.22.0</jackson2.version>")
        forbid("pom.xml", pom, "<jackson3.version>3.0.3</jackson3.version>")

        postgres_pom = read("minos-storage-postgresql/pom.xml")
        require_all("minos-storage-postgresql/pom.xml", postgres_pom, (
            "windows-docker-desktop-testcontainers", "<family>Windows</family>",
            "dockerDesktopLinuxEngine", "jackson-databind",
        ))
        postgres_support = read("minos-storage-postgresql/src/test/java/com/minos/storage/postgresql/PostgresTestSupport.java")
        require_all("PostgresTestSupport.java", postgres_support, (
            "minos.postgresql.tests.required", "Boolean.getBoolean(REQUIRED_PROPERTY)",
            "PostgreSQL integration tests are required", "assumeTrue(dockerAvailable",
        ))

        # Main CI contract.
        workflow = read(".github/workflows/pr-ci.yml")
        require_regex(".github/workflows/pr-ci.yml", workflow, r"(?m)^\s*push:\s*$", "push qualification trigger")
        require_all(".github/workflows/pr-ci.yml", workflow, (
            "branches: [main, develop]", "Dependency vulnerability gate",
            "google/osv-scanner-action", "fail-on-vuln: true",
            "-Dminos.postgresql.tests.required=true",
            "--skip-scope m30-postgresql-pgvector",
        ))

        jacoco = read("scripts/quality/check-jacoco.py")
        require_all("scripts/quality/check-jacoco.py", jacoco, (
            "through M30", "m29-backend-routing", "m30-storage-backend-selection",
            "m30-postgresql-pgvector",
        ))

        # IntelliJ must be qualified on relevant PR/push heads and again at publication.
        intellij_workflow = read(".github/workflows/intellij-plugin.yml")
        require_regex(".github/workflows/intellij-plugin.yml", intellij_workflow, r"(?m)^\s*push:\s*$", "IntelliJ push trigger")
        require_all(".github/workflows/intellij-plugin.yml", intellij_workflow, (
            "branches: [main, develop]", "Checkout exact candidate", "buildPlugin",
            "verifyPluginProjectConfiguration", "verifyPluginStructure", "verifyPlugin",
        ))

        # Installer contract.
        installer = read("packaging/windows/minos-installer.iss.template")
        detector = read("scripts/install/detect-mcp-clients.ps1")
        require_all("packaging/windows/minos-installer.iss.template", installer, (
            "Standard — recommandé", "Avancée", "MCP natif Windows — recommandé",
            "MCP Docker — isolation renforcée", "Ne pas configurer maintenant",
            "GitHub Copilot — JetBrains / IntelliJ", "GitHub Copilot CLI",
            "Claude CLI / Claude Code", "Claude Desktop", "OpenAI Codex CLI", "OpenAI Codex Desktop",
            "Résumé de l''installation", "Composants gérés par MINOS",
            "PostgreSQL/pgvector Docker", "Ollama Docker", "aucun fallback silencieux",
        ))
        require_all("scripts/install/detect-mcp-clients.ps1", detector, (
            "CopilotJetBrains", "CopilotCli", "ClaudeCode", "ClaudeDesktop",
            "CodexCli", "CodexDesktop", "Test-VsCodeCopilotShim",
        ))

        # Publication is always explicit, exact-head, immutable and includes IntelliJ.
        release_workflow = read(".github/workflows/release-windows.yml")
        require(".github/workflows/release-windows.yml", release_workflow, "workflow_dispatch")
        forbid(".github/workflows/release-windows.yml", release_workflow, "pull_request:")
        require_all(".github/workflows/release-windows.yml", release_workflow, (
            "Preflight exact ref and immutable tag", "git ls-remote --tags", "already exists on origin",
            "IntelliJ Plugin Verifier", "verifyPlugin", "Set up Java 24 for MINOS release",
            "publish-windows-release.ps1", "TargetCommit '${{ github.sha }}'",
        ))

        print("MINOS CURRENT DOCUMENTATION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"MINOS CURRENT DOCUMENTATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

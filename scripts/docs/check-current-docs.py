#!/usr/bin/env python3
"""Validate durable MINOS current-state, release and installer invariants.

This checker intentionally avoids pinning current documentation to historical branch
names or exact qualification SHAs. Historical proof belongs in execution records;
current-state documents must describe what is delivered now and the source contracts
must independently prove the claims that matter to users and release engineering.
"""

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

        # Current product truth. Exact SHAs belong in historical proof records, not
        # in the invariant checker.
        require_all("README.md", readme, ("C0 → M30", "1.0.0", "1.0.1", "NON PUBLI", "#98"))
        require_all("docs/STATUS.md", status, (
            "M29 issue #107", "CLOSED", "M29 PR #108", "M30 PR #110", "M30 promotion PR #111",
            "v1.0.1 Windows", "NON PUBLI", "#98", "PR #113",
        ))
        require_all("docs/ROADMAP.md", roadmap, (
            "C0 → M30", "M29", "TERMIN", "M30", "LIVR", "PR #113", "1.0.1", "NON PUBLI", "#98",
        ))

        for relative, text in (("README.md", readme), ("docs/STATUS.md", status), ("docs/ROADMAP.md", roadmap)):
            for stale in (
                "M29 #107                         EN COURS",
                "M29 — Autonomous Docker Runtime & Native Parity | **EN COURS",
                "S8 reste le gate",
                "M30 non livré",
            ):
                forbid(relative, text, stale)

        # Release/user-facing contract remains intentionally unpublished until an
        # exact-head candidate passes all gates and explicit publication occurs.
        require_all("docs/releases/1.0.1.md", release_101, (
            "NON PUBLI", "Standard", "Avancé", "PostgreSQL", "pgvector", "Ollama",
            "Claude", "Codex CLI", "Codex Desktop", "OSV", "Jackson", "exact-head",
        ))
        require_all("docs/user/production-installation.md", production_install, (
            "Standard", "Avancé", "MCP natif Windows", "MCP Docker", "PostgreSQL", "pgvector",
            "Ollama", "Claude CLI", "Claude Desktop", "Codex CLI", "Codex Desktop",
            "Résumé", "%LOCALAPPDATA%\\MINOS", "Non / conserver",
        ))

        # Security/dependency contract: both Jackson generations are centrally
        # pinned because PostgreSQL uses Jackson 2 while MCP SDK uses Jackson 3.
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
        forbid("minos-storage-postgresql/pom.xml", postgres_pom, "<version>2.22.0</version>")

        postgres_support = read("minos-storage-postgresql/src/test/java/com/minos/storage/postgresql/PostgresTestSupport.java")
        require_all("PostgresTestSupport.java", postgres_support, (
            "minos.postgresql.tests.required", "Boolean.getBoolean(REQUIRED_PROPERTY)",
            "PostgreSQL integration tests are required", "assumeTrue(dockerAvailable",
        ))

        # CI must qualify PRs and the resulting develop/main heads, require real
        # PostgreSQL/pgvector and fail on known dependency vulnerabilities.
        workflow = read(".github/workflows/pr-ci.yml")
        require_regex(".github/workflows/pr-ci.yml", workflow, r"(?m)^\s*push:\s*$", "push qualification trigger")
        require_all(".github/workflows/pr-ci.yml", workflow, (
            "branches: [main, develop]", "Dependency vulnerability gate",
            "google/osv-scanner-action", "fail-on-vuln: true",
            "-Dminos.postgresql.tests.required=true",
        ))

        jacoco = read("scripts/quality/check-jacoco.py")
        require_all("scripts/quality/check-jacoco.py", jacoco, (
            "through M30", "m29-backend-routing", "m30-storage-backend-selection",
            "m30-postgresql-pgvector",
        ))

        # Installer contract adapted from the useful NEXUS deployment-wizard UX,
        # while retaining MINOS' stronger backend-agnostic/fail-closed model.
        installer = read("packaging/windows/minos-installer.iss.template")
        detector = read("scripts/install/detect-mcp-clients.ps1")
        require_all("packaging/windows/minos-installer.iss.template", installer, (
            "Standard — recommandé", "Avancée", "MCP natif Windows — recommandé",
            "MCP Docker — isolation renforcée", "Ne pas configurer maintenant",
            "GitHub Copilot — JetBrains / IntelliJ", "GitHub Copilot CLI",
            "Claude CLI / Claude Code", "Claude Desktop", "OpenAI Codex CLI", "OpenAI Codex Desktop",
            "Résumé de l'installation", "Composants gérés par MINOS",
            "PostgreSQL/pgvector Docker", "Ollama Docker", "aucun fallback silencieux",
        ))
        require_all("scripts/install/detect-mcp-clients.ps1", detector, (
            "CopilotJetBrains", "CopilotCli", "ClaudeCode", "ClaudeDesktop",
            "CodexCli", "CodexDesktop", "Test-VsCodeCopilotShim",
        ))

        # Release tooling still protects immutable 1.0.0 and does not auto-publish
        # 1.0.1 from a PR/push.
        release_workflow = read(".github/workflows/release-windows.yml")
        require(".github/workflows/release-windows.yml", release_workflow, "workflow_dispatch")
        forbid(".github/workflows/release-windows.yml", release_workflow, "pull_request:")

        print("MINOS CURRENT DOCUMENTATION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"MINOS CURRENT DOCUMENTATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

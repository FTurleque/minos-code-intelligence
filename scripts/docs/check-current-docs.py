#!/usr/bin/env python3
"""Validate durable MINOS current-state, release and installer invariants."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

README = "README.md"
STATUS = "docs/STATUS.md"
ROADMAP = "docs/ROADMAP.md"
RELEASE_101 = "docs/releases/1.0.1.md"
PRODUCTION_INSTALL = "docs/user/production-installation.md"
POM = "pom.xml"
POSTGRES_POM = "minos-storage-postgresql/pom.xml"
POSTGRES_SUPPORT = "minos-storage-postgresql/src/test/java/com/minos/storage/postgresql/PostgresTestSupport.java"
PR_WORKFLOW = ".github/workflows/pr-ci.yml"
INTELLIJ_WORKFLOW = ".github/workflows/intellij-plugin.yml"
RELEASE_WORKFLOW = ".github/workflows/release-windows.yml"
JACOCO_CHECKER = "scripts/quality/check-jacoco.py"
INSTALLER = "packaging/windows/minos-installer.iss.template"
CLIENT_DETECTOR = "scripts/install/detect-mcp-clients.ps1"

PUBLISHED_101_COMMIT = "f762025d66e33c40324c811079f1527d122f90f9"
PUBLISHED_101_URL = "https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1"
TEMP_PUBLICATION_WORKFLOWS = (
    ".github/workflows/publish-v1.0.1-one-shot.yml",
    ".github/workflows/publish-v1.0.1-final.yml",
    ".github/workflows/report-v1.0.1-publication-status.yml",
)


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


def require_absent(relative: str) -> None:
    if (ROOT / relative).exists():
        raise RuntimeError(f"temporary publication artifact must be absent after release: {relative}")


def validate_current_state() -> None:
    documents = {
        README: read(README),
        STATUS: read(STATUS),
        ROADMAP: read(ROADMAP),
    }
    require_all(README, documents[README], (
        "C0 → M30", "MINOS 1.0.1", "publiée", "immutable", PUBLISHED_101_COMMIT,
        PUBLISHED_101_URL, "10 assets", "#98",
    ))
    require_all(STATUS, documents[STATUS], (
        "M29 issue #107", "CLOSED", "M29 PR #108", "M30 PR #110", "M30 promotion PR #111",
        "hardening PR #113", "M28 Windows CI PR #117", "v1.0.1", "PUBLIÉE",
        PUBLISHED_101_COMMIT, PUBLISHED_101_URL, "10 assets", "5 paires", "31288322126", "#98",
    ))
    require_all(ROADMAP, documents[ROADMAP], (
        "C0 → M30", "#113", "#117", "terminé", "Release 1.0.1", "publiée",
        PUBLISHED_101_COMMIT, PUBLISHED_101_URL, "10 assets", "Plugin Verifier", "#98",
    ))

    stale_markers = (
        "PRÉ-PUBLICATION",
        "NON PUBLIÉE",
        "NON PUBLI",
        "hardening release/installer      🚧 PR #113",
        "hardening post-audit en cours sur PR #113",
        "Avant le prochain candidat 1.0.1, la branche audit/release-installer-hardening doit converger",
        "La priorité immédiate est de terminer PR #113",
        "M30 non livré",
        "conflit du tag v1.0.1",
        "2de847bdc6bc39e63715f20987a30f07731cc717",
    )
    for relative, text in documents.items():
        for stale in stale_markers:
            forbid(relative, text, stale)


def validate_release_documentation() -> None:
    release_text = read(RELEASE_101)
    require_all(RELEASE_101, release_text, (
        "PUBLIÉE", "IMMUTABLE", PUBLISHED_101_COMMIT, PUBLISHED_101_URL,
        "10 assets", "5 paires", "31288322126",
        "Standard", "Avancée", "PostgreSQL", "pgvector", "Ollama",
        "Claude", "Codex CLI", "Codex Desktop", "OSV", "Jackson", "Plugin Verifier",
    ))
    for stale in ("PRÉ-PUBLICATION", "NON PUBLI", "2de847bdc6bc39e63715f20987a30f07731cc717"):
        forbid(RELEASE_101, release_text, stale)

    install_text = read(PRODUCTION_INSTALL)
    require_all(PRODUCTION_INSTALL, install_text, (
        "Standard", "Avancée", "MCP natif Windows", "MCP Docker", "PostgreSQL", "pgvector",
        "Ollama", "Claude CLI", "Claude Desktop", "Codex CLI", "Codex Desktop",
        "Résumé", "%LOCALAPPDATA%\\MINOS", "Non / conserver",
    ))

    for temporary in TEMP_PUBLICATION_WORKFLOWS:
        require_absent(temporary)


def validate_security_and_storage() -> None:
    pom = read(POM)
    require_all(POM, pom, (
        "<jackson2.version>2.22.1</jackson2.version>",
        "<jackson3.version>3.1.5</jackson3.version>",
        "com.fasterxml.jackson", "tools.jackson", "jackson-bom",
    ))
    forbid(POM, pom, "<jackson2.version>2.22.0</jackson2.version>")
    forbid(POM, pom, "<jackson3.version>3.0.3</jackson3.version>")

    postgres_pom = read(POSTGRES_POM)
    require_all(POSTGRES_POM, postgres_pom, (
        "windows-docker-desktop-testcontainers", "<family>Windows</family>",
        "dockerDesktopLinuxEngine", "jackson-databind",
    ))

    postgres_support = read(POSTGRES_SUPPORT)
    require_all(POSTGRES_SUPPORT, postgres_support, (
        "minos.postgresql.tests.required", "Boolean.getBoolean(REQUIRED_PROPERTY)",
        "PostgreSQL integration tests are required", "assumeTrue(dockerAvailable",
    ))


def validate_ci_contracts() -> None:
    workflow = read(PR_WORKFLOW)
    require_regex(PR_WORKFLOW, workflow, r"(?m)^\s*push:\s*$", "push qualification trigger")
    require_all(PR_WORKFLOW, workflow, (
        "branches: [main, develop]", "Dependency vulnerability gate",
        "google/osv-scanner-action", "fail-on-vuln: true",
        "-Dminos.postgresql.tests.required=true",
        "--skip-scope m30-postgresql-pgvector",
    ))

    jacoco = read(JACOCO_CHECKER)
    require_all(JACOCO_CHECKER, jacoco, (
        "through M30", "m29-backend-routing", "m30-storage-backend-selection",
        "m30-postgresql-pgvector",
    ))

    intellij = read(INTELLIJ_WORKFLOW)
    require_regex(INTELLIJ_WORKFLOW, intellij, r"(?m)^\s*push:\s*$", "IntelliJ push trigger")
    require_all(INTELLIJ_WORKFLOW, intellij, (
        "branches: [main, develop]", "Checkout exact candidate", "buildPlugin",
        "verifyPluginProjectConfiguration", "verifyPluginStructure", "verifyPlugin",
    ))

    release = read(RELEASE_WORKFLOW)
    require(RELEASE_WORKFLOW, release, "workflow_dispatch")
    forbid(RELEASE_WORKFLOW, release, "pull_request:")
    require_all(RELEASE_WORKFLOW, release, (
        "Preflight exact ref and immutable tag", "git ls-remote --tags", "already exists on origin",
        "IntelliJ Plugin Verifier", "verifyPlugin", "Set up Java 24 for MINOS release",
        "publish-windows-release.ps1", "TargetCommit '${{ github.sha }}'",
    ))


def validate_installer_contract() -> None:
    installer = read(INSTALLER)
    require_all(INSTALLER, installer, (
        "Standard — recommandé", "Avancée", "MCP natif Windows — recommandé",
        "MCP Docker — isolation renforcée", "Ne pas configurer maintenant",
        "GitHub Copilot — JetBrains / IntelliJ", "GitHub Copilot CLI",
        "Claude CLI / Claude Code", "Claude Desktop", "OpenAI Codex CLI", "OpenAI Codex Desktop",
        "Résumé de l''installation", "Composants gérés par MINOS",
        "PostgreSQL/pgvector Docker", "Ollama Docker", "aucun fallback silencieux",
    ))

    detector = read(CLIENT_DETECTOR)
    require_all(CLIENT_DETECTOR, detector, (
        "CopilotJetBrains", "CopilotCli", "ClaudeCode", "ClaudeDesktop",
        "CodexCli", "CodexDesktop", "Test-VsCodeCopilotShim",
    ))


def main() -> int:
    validators = (
        validate_current_state,
        validate_release_documentation,
        validate_security_and_storage,
        validate_ci_contracts,
        validate_installer_contract,
    )
    try:
        for validator in validators:
            validator()
        print("MINOS CURRENT DOCUMENTATION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"MINOS CURRENT DOCUMENTATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

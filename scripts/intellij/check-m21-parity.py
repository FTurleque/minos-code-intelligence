#!/usr/bin/env python3
"""Static M21-S6 contract gate for IntelliJ M19/M20 parity."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

CAPABILITIES = (
    "program-graph",
    "impact-v2",
    "security-paths",
    "semantic-index-status",
    "semantic-index-sync",
    "semantic-search",
    "hybrid-search",
    "hybrid-context",
)

ACTIONS = (
    "Minos.ProgramGraph",
    "Minos.ImpactV2",
    "Minos.SecurityPaths",
    "Minos.SemanticIndexStatus",
    "Minos.SemanticIndexSync",
    "Minos.SemanticSearch",
    "Minos.HybridSearch",
    "Minos.HybridContext",
)


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, expected: str) -> None:
    if expected not in text:
        raise RuntimeError(f"{relative}: missing expected text: {expected}")


def forbid(relative: str, text: str, forbidden: str) -> None:
    if forbidden in text:
        raise RuntimeError(f"{relative}: forbidden text present: {forbidden}")


def main() -> int:
    try:
        ide = read("minos-cli/src/main/java/com/minos/cli/IdeCommand.java")
        transport = read("minos-cli/src/main/java/com/minos/cli/IdeIntelligenceCommand.java")
        runner = read("minos-cli/src/main/java/com/minos/cli/MinosCliRunner.java")
        client = read("minos-intellij/src/main/java/com/minos/intellij/protocol/MinosM21Client.java")
        actions = read("minos-intellij/src/main/java/com/minos/intellij/actions/MinosM21Actions.java")
        plugin = read("minos-intellij/src/main/resources/META-INF/plugin.xml")
        build = read("minos-intellij/build.gradle.kts")

        require("IdeCommand.java", ide, 'PROTOCOL_VERSION = "1"')
        for capability in CAPABILITIES:
            require("IdeCommand.java", ide, f'"{capability}"')
            require("IdeIntelligenceCommand.java", transport, f'"{capability}"')
            require("MinosM21Client.java", client, capability)

        for service in (
            "programGraphService()",
            "advancedImpactService()",
            "securityAnalysisService()",
            "semanticIndexService()",
            "semanticSearchService()",
            "hybridSearchService()",
            "hybridContextBuilder()",
        ):
            require("IdeIntelligenceCommand.java", transport, service)

        require("MinosCliRunner.java", runner, "new IdeIntelligenceCommand(app)")
        require("MinosM21Client.java", client, "client.handshake()")
        require("MinosM21Actions.java", actions, "Task.Backgroundable")
        require("MinosM21Actions.java", actions, "Messages.showInputDialog")

        for action in ACTIONS:
            require("plugin.xml", plugin, f'id="{action}"')
        require("plugin.xml", plugin, 'text="Advanced Intelligence"')
        require("plugin.xml", plugin, 'text="Semantic &amp; Hybrid"')
        require("plugin.xml", plugin, 'Minos.Impact')

        forbid("build.gradle.kts", build, 'implementation("com.minos:')
        require("build.gradle.kts", build, "JavaVersion.VERSION_21")
        require("build.gradle.kts", build, "options.release = 21")
        require("build.gradle.kts", build, 'sinceBuild = "261"')
        require("build.gradle.kts", build, "IntelliJPlatformType.IntellijIdea")
        require("build.gradle.kts", build, "ProductRelease.Channel.RELEASE")
        require("build.gradle.kts", build, 'untilBuild = "261.*"')

        print(
            "M21 INTELLIJ PARITY CONSISTENCY SUCCESS "
            f"(capabilities={len(CAPABILITIES)}, actions={len(ACTIONS)}, ideBranch=261)"
        )
        return 0
    except Exception as exception:
        print(f"M21 INTELLIJ PARITY CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

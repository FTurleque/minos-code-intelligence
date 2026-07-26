#!/usr/bin/env python3
"""Generate/check mechanically verifiable MINOS product facts from source code."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "docs" / "generated" / "product-facts.md"


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require(pattern: str, text: str, label: str, flags: int = 0) -> str:
    match = re.search(pattern, text, flags)
    if not match:
        raise RuntimeError(f"cannot derive {label}")
    return match.group(1)


def cli_commands(source: str) -> list[str]:
    usage = require(
        r'private static final String USAGE = """(.*?)"""\.stripTrailing\(\);',
        source,
        "CLI usage",
        re.S,
    )
    commands: list[str] = []
    for raw in usage.splitlines():
        line = raw.strip()
        if not line or line.endswith(":") or line.startswith("Usage:") or line[0].isdigit():
            continue
        match = re.match(r"([a-z][a-z0-9-]*(?:\s+[a-z][a-z0-9-]*)?)\s{2,}", line)
        if match:
            commands.append(match.group(1))
    return commands


def provider_facts(source: str) -> list[tuple[str, str, list[str]]]:
    facts: list[tuple[str, str, list[str]]] = []
    for method in ("scipJava", "scipTypeScript"):
        block = require(
            rf'public static IndexerDescriptor {method}\(\) \{{(.*?)\n    \}}',
            source,
            method,
            re.S,
        )
        descriptor = re.search(
            r'new IndexerDescriptor\(\s*"([^"]+)",\s*"([^"]+)"', block, re.S)
        if not descriptor:
            raise RuntimeError(f"cannot derive provider descriptor for {method}")
        capabilities = sorted(set(re.findall(r"IndexerCapability\.([A-Z0-9_]+)", block)))
        facts.append((descriptor.group(1), descriptor.group(2), capabilities))
    return facts


def render() -> str:
    pom = read("pom.xml")
    api = read("minos-api/src/main/java/com/minos/api/MinosApi.java")
    mcp = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java")
    cli = read("minos-cli/src/main/java/com/minos/cli/MinosCli.java")
    providers = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIndexerCatalog.java")

    version = require(r"<revision>([^<]+)</revision>", pom, "product version")
    api_version = require(r'CONTRACT_VERSION\s*=\s*"([^"]+)"', api, "API contract version")
    declared_tool_count = int(require(r"TOOL_COUNT\s*=\s*(\d+)", mcp, "MCP tool count"))
    tool_names = re.findall(r'tool\("([^"]+)"', mcp)
    if len(tool_names) != declared_tool_count:
        raise RuntimeError(
            f"MCP TOOL_COUNT={declared_tool_count} but {len(tool_names)} tool specifications were derived")

    commands = cli_commands(cli)
    provider_values = provider_facts(providers)

    lines = [
        "# MINOS — Facts produit générés",
        "",
        "> Ce fichier est généré depuis les sources par `scripts/docs/product-facts.py`.",
        "> Ne pas modifier manuellement.",
        "",
        "## Versions",
        "",
        f"- version Maven : `{version}`",
        f"- contrat API Java : `v{api_version}`",
        "",
        "## Catalogue MCP",
        "",
        f"Nombre de tools : **{declared_tool_count}**",
        "",
    ]
    lines.extend(f"- `{name}`" for name in tool_names)
    lines.extend(["", "## Commandes CLI", ""])
    lines.extend(f"- `{command}`" for command in commands)
    lines.extend(["", "## Providers qualifiés", ""])
    for provider_id, provider_version, capabilities in provider_values:
        lines.append(f"### `{provider_id}` `{provider_version}`")
        lines.append("")
        lines.append("Capabilities : " + ", ".join(f"`{value}`" for value in capabilities))
        lines.append("")
    lines.extend([
        "## Formats calculables",
        "",
        "- formats CLI structurants : `text`, `json`",
        "- graphe d'architecture : `json`, `mermaid`, `dot`",
        "",
    ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if generated facts are stale")
    args = parser.parse_args()

    try:
        expected = render()
    except Exception as exception:
        print(f"PRODUCT FACTS ERROR: {exception}", file=sys.stderr)
        return 2

    if args.check:
        if not OUTPUT.is_file():
            print(f"PRODUCT FACTS ERROR: missing {OUTPUT.relative_to(ROOT)}", file=sys.stderr)
            return 1
        actual = OUTPUT.read_text(encoding="utf-8")
        if actual != expected:
            print("PRODUCT FACTS OUT OF DATE", file=sys.stderr)
            print("Run: python scripts/docs/product-facts.py", file=sys.stderr)
            return 1
        print("M15 PRODUCT FACTS CONSISTENCY SUCCESS")
        return 0

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected, encoding="utf-8")
    print(f"Generated {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

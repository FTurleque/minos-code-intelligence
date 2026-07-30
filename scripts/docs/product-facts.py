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


def enum_values(source: str, enum_name: str) -> list[str]:
    body = require(
        rf"public enum {re.escape(enum_name)}\s*\{{(.*?);",
        source,
        f"{enum_name} values",
        re.S,
    )
    values = [value.lower() for value in re.findall(r"\b([A-Z][A-Z0-9_]*)\b", body)]
    if not values:
        raise RuntimeError(f"cannot derive values for {enum_name}")
    return values


def qualified_descriptor_methods(source: str) -> list[str]:
    """Derive the current provider order from the authoritative qualified catalog methods."""
    methods: list[str] = []
    for catalog in ("qualifiedM17Providers", "qualifiedM24Providers"):
        body = require(
            rf"public static List<IndexerProvider> {catalog}\(\) \{{(.*?)\n    \}}",
            source,
            catalog,
            re.S,
        )
        for method in re.findall(r"provider\((\w+)\(\)", body):
            if method not in methods:
                methods.append(method)
    if len(methods) != 7:
        raise RuntimeError(f"qualified M24 catalog must expose 7 providers, derived={methods}")
    return methods


def resolve_string(source: str, raw_value: str, label: str) -> str:
    value = raw_value.strip()
    if value.startswith('"') and value.endswith('"'):
        return value.strip('"')
    return require(
        rf'public static final String {re.escape(value)}\s*=\s*"([^"]+)"',
        source,
        label,
    )


def provider_facts(source: str) -> list[tuple[str, str, str, list[str], list[str]]]:
    facts: list[tuple[str, str, str, list[str], list[str]]] = []
    for method in qualified_descriptor_methods(source):
        block = require(
            rf"public static IndexerDescriptor {method}\(\) \{{(.*?)\n    \}}",
            source,
            method,
            re.S,
        )
        descriptor = re.search(r"new IndexerDescriptor\(\s*([^,]+),\s*([^,]+),", block, re.S)
        if not descriptor:
            raise RuntimeError(f"cannot derive provider descriptor for {method}")
        provider_id = resolve_string(source, descriptor.group(1), f"provider id for {method}")
        provider_version = resolve_string(source, descriptor.group(2), f"provider version for {method}")
        qualification = require(
            r"IndexerQualification\.([A-Z0-9_]+)", block, f"provider qualification for {method}")
        languages = sorted(set(re.findall(r"Language\.([A-Z0-9_]+)", block)))
        capabilities = sorted(set(re.findall(r"IndexerCapability\.([A-Z0-9_]+)", block)))
        facts.append((provider_id, provider_version, qualification, languages, capabilities))
    return facts


def render() -> str:
    pom = read("pom.xml")
    api = read("minos-api/src/main/java/com/minos/api/MinosApi.java")
    mcp = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java")
    cli = read("minos-cli/src/main/java/com/minos/cli/MinosCli.java")
    symbol_formats_source = read("minos-application/src/main/java/com/minos/output/SymbolOutputFormat.java")
    architecture_formats_source = read("minos-cli/src/main/java/com/minos/cli/ArchitectureOutputFormat.java")
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
    symbol_formats = enum_values(symbol_formats_source, "SymbolOutputFormat")
    architecture_formats = enum_values(architecture_formats_source, "ArchitectureOutputFormat")

    lines = [
        "# MINOS — Facts produit générés", "",
        "> Ce fichier est généré depuis les sources par `scripts/docs/product-facts.py`.",
        "> Ne pas modifier manuellement.", "",
        "## Versions", "", f"- version Maven : `{version}`", f"- contrat API Java : `v{api_version}`", "",
        "## Catalogue MCP", "", f"Nombre de tools : **{declared_tool_count}**", "",
    ]
    lines.extend(f"- `{name}`" for name in tool_names)
    lines.extend(["", "## Commandes CLI", ""])
    lines.extend(f"- `{command}`" for command in commands)
    lines.extend(["", "## Providers qualifiés", ""])
    for provider_id, provider_version, qualification, languages, capabilities in provider_values:
        lines.append(f"### `{provider_id}` `{provider_version}`")
        lines.append("")
        lines.append(f"Disposition : `{qualification}`")
        lines.append("")
        lines.append("Langages : " + ", ".join(f"`{value}`" for value in languages))
        lines.append("")
        lines.append("Capabilities : " + ", ".join(f"`{value}`" for value in capabilities))
        lines.append("")
    lines.extend([
        "## Formats calculables", "",
        "- formats symboles : " + ", ".join(f"`{value}`" for value in symbol_formats),
        "- formats architecture : " + ", ".join(f"`{value}`" for value in architecture_formats), "",
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

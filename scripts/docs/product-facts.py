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


def check_authoritative_documentation() -> None:
    """Reject stale mutable prose that contradicts authoritative release/security facts."""
    status = read("docs/STATUS.md")
    roadmap = read("docs/ROADMAP.md")
    risk_register = read("docs/architecture/risks/register.md")
    readme = read("README.md")
    production = read("docs/user/production-installation.md")

    release_date = require(
        r"La release \*\*MINOS v1\.0\.1\*\* a été publiée le \*\*([^*]+)\*\*",
        status,
        "v1.0.1 publication date from STATUS",
    )
    release_sha = require(
        r"v1\.0\.1 → ([0-9a-f]{40})",
        status,
        "v1.0.1 immutable tag SHA from STATUS",
    )
    # Audit 2026-09, constat A1 : les primitives #98 existent mais la qualification « code non
    # fiable » est refusée, et depuis l'ADR 0041 (2026-09-26) elle l'est PAR DÉCISION : le quota
    # d'écriture reste supervisé, jamais présenté comme OS_ENFORCED. STATUS doit exposer ce fait
    # exact et citer la décision ; l'ancienne revendication « QUALIFIÉE » est obsolète.
    if ("#98 sandbox OS réelle" not in status or "primitives **implémentées**" not in status
            or "qualification code non fiable **refusée**" not in status):
        raise RuntimeError("STATUS no longer exposes the authoritative #98 sandbox qualification fact")
    if "IMPLÉMENTÉE + QUALIFIÉE" in status:
        raise RuntimeError("stale #98 qualification claim in docs/STATUS.md (audit 2026-09, A1)")
    if "ADR 0041" not in status:
        raise RuntimeError("STATUS must cite ADR 0041: the #98 refusal is a decision, not a pending defect")
    check_sandbox_claims_match_the_code()

    current_docs = {
        "docs/STATUS.md": status,
        "docs/ROADMAP.md": roadmap,
        "docs/architecture/risks/register.md": risk_register,
    }
    for path, text in current_docs.items():
        integrated_lines = [
            line for line in text.splitlines()
            if "#227" in line and ("intégr" in line.casefold() or "merged" in line.casefold())
        ]
        if not integrated_lines:
            raise RuntimeError(f"{path} must expose PR #227 as integrated")
        for line in text.splitlines():
            lowered = line.casefold()
            if "#227" in line and ("non intégr" in lowered or "en qualification" in lowered):
                raise RuntimeError(f"stale PR #227 integration state in {path}: {line.strip()}")

    stale_markers = {
        "README.md": [
            "#98 sandbox OS worker réelle     🚧 OPEN",
            "L'issue **#98** reste ouverte",
            "qualifiée Linux + Windows",
            "currentOsBackendsFailClosedUntilStorageIsOsEnforced",
        ],
        "docs/user/production-installation.md": [
            "1.0.1` reste **NON PUBLIÉE**",
            "1.0.1 reste **NON PUBLIÉE**",
        ],
    }
    documents = {
        "README.md": readme,
        "docs/user/production-installation.md": production,
    }
    for path, markers in stale_markers.items():
        for marker in markers:
            if marker in documents[path]:
                raise RuntimeError(f"stale product fact in {path}: {marker}")

    if release_date not in readme or release_date not in production:
        raise RuntimeError(
            f"README and production guide must both expose v1.0.1 publication date {release_date}")
    if release_sha not in readme or release_sha not in production:
        raise RuntimeError("README and production guide must both expose the immutable v1.0.1 tag SHA")
    # « qualifiée » n'est plus une formulation acceptée pour #98 : l'issue est fermée sur les
    # primitives, la qualification code non fiable est refusée par décision (ADR 0041).
    if not re.search(r"#98[^\n]*(?:CLOSED|fermée|completed)", readme, re.I):
        raise RuntimeError("README must expose #98 as closed on its primitives")
    if re.search(r"#98[^\n]*qualifiée", readme, re.I):
        raise RuntimeError("README must not present #98 as qualified for untrusted code (ADR 0041, A1)")
    if "ADR 0041" not in readme:
        raise RuntimeError("README sandbox section must cite ADR 0041 (closed by decision)")


def check_sandbox_claims_match_the_code() -> None:
    """The documented untrusted-code requirement must be exactly what the code enforces.

    `WorkerResourceContainment.unmetRequirements()` requires OS_ENFORCED on the write quota
    (bytes and entries); the developer page once said "at least SUPERVISED_HARD_KILL", which was
    false. Both the code and the pages are checked, so neither can drift alone.
    """
    containment = read("minos-runtime-local/src/main/java/com/minos/runtime/WorkerResourceContainment.java")
    unmet = require(
        r"public List<String> unmetRequirements\(\) \{(.*?)\n    \}", containment, "unmetRequirements body", re.S)
    for dimension in ("FILESYSTEM_WRITE_BYTES", "FILESYSTEM_WRITE_ENTRIES"):
        if f'requireOsEnforced(unmet, "{dimension}"' not in unmet:
            raise RuntimeError(f"untrusted-code qualification no longer requires OS_ENFORCED on {dimension}")
    qualification = read("minos-runtime-local/src/main/java/com/minos/runtime/WorkerSandboxQualification.java")
    if '"WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041"' not in qualification:
        raise RuntimeError("WorkerSandboxQualification must carry the ADR 0041 decision limitation")

    disposition = read("docs/developer/remote-worker-sandbox-disposition.md")
    if "au minimum `SUPERVISED_HARD_KILL` sur le wall-clock, le quota d’écriture" in disposition:
        raise RuntimeError(
            "docs/developer/remote-worker-sandbox-disposition.md presents a supervised write quota as "
            "sufficient for untrusted code; the code requires OS_ENFORCED (A1, ADR 0041)")
    if "`OS_ENFORCED` sur le quota d’écriture (octets **et** nombre d’entrées)" not in disposition:
        raise RuntimeError("remote-worker-sandbox-disposition.md must state the OS_ENFORCED write-quota requirement")
    if "## Backends qualifiés" in disposition:
        raise RuntimeError("remote-worker-sandbox-disposition.md must not title the integrated backends as qualified")
    if "ADR 0041" not in disposition:
        raise RuntimeError("remote-worker-sandbox-disposition.md must cite ADR 0041")
    # V30 : les backends intégrés ne sont pas « la » sandbox qualifiée du plan remote worker.
    if "exige une sandbox OS explicitement qualifiée (Bubblewrap+cgroup v2 délégué, ou AppContainer)" in disposition:
        raise RuntimeError(
            "remote-worker-sandbox-disposition.md presents Bubblewrap/AppContainer as the qualified "
            "remote-worker sandbox; both are rejected for untrusted code by decision (ADR 0041, V30)")

    remote_indexing = read("docs/user/remote-indexing.md")
    for stale in ("n’atteignent pas encore cette qualification", "n’est pas encore un quota stockage"):
        if stale in remote_indexing:
            raise RuntimeError(f"docs/user/remote-indexing.md presents the refusal as pending, not decided: {stale}")
    if "ADR 0041" not in remote_indexing:
        raise RuntimeError("docs/user/remote-indexing.md must cite ADR 0041")
    # V30 : les prérequis Linux servent l'indexation locale gérée et ne rouvrent pas remote index ;
    # le message de refus dépend de la cause, il ne cite un backend écarté que par décision.
    for stale in ("Sans elles, MINOS reste fail-closed sur `remote index`",
                  "avec un message qui cite le backend écarté et les codes exacts des dimensions non OS-enforced"):
        if stale in remote_indexing:
            raise RuntimeError(f"docs/user/remote-indexing.md claims more than the refusal causes allow: {stale}")
    if "ne rouvrent pas `remote index`" not in remote_indexing:
        raise RuntimeError(
            "docs/user/remote-indexing.md must say that the Linux operator prerequisites do not reopen "
            "remote index (closed by decision, ADR 0041)")
    cli_doc = read("docs/user/cli.md")
    if "qui cite le backend écarté et les dimensions manquantes, et" in cli_doc:
        raise RuntimeError("docs/user/cli.md presents the decision-only refusal message as the only one (V30)")


def check_architecture_doc_version(maven_version: str) -> None:
    """The architecture README states its own version in prose; keep it from drifting behind the
    Maven reactor version (the source of truth) the way it did until MINOS 1.1.0-SNAPSHOT."""
    architecture_readme = read("docs/architecture/README.md")
    declared = require(
        r"Version : ([0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?)",
        architecture_readme,
        "docs/architecture/README.md declared version",
    )
    if declared != maven_version:
        raise RuntimeError(
            f"docs/architecture/README.md declares version {declared} but pom.xml <revision> is "
            f"{maven_version} -- update the architecture README header")


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
        maven_version = require(r"<revision>([^<]+)</revision>", read("pom.xml"), "product version")
        check_authoritative_documentation()
        check_architecture_doc_version(maven_version)
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

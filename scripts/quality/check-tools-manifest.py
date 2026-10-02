#!/usr/bin/env python3
"""Fail when the tools MINOS ships are described more than once, or described differently.

The single description is the packaged catalogue
``minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json``: for every
tool, its version, its pinned artifact (URL, SHA-256, size, license) and whether the Windows distribution
embeds it. Three consumers read it and one gate checks all of them:

* the Java runtime managers (they read it at run time; no 64-hex SHA-256 or 40-hex commit may be
  written in ``minos-provider-scip/src/main/java``);
* ``docker/Dockerfile.mcp.release`` (its ``ARG`` lines must carry exactly the catalogue values;
  ``scripts/release/sync-tools-manifest.py --write`` rewrites them; no other SHA-256 may appear);
* the Windows distribution build (``scripts/release/build-embedded-tools.py``, called by
  ``build-windows-distribution.ps1``; neither may carry a list or a hash of its own).

``ScipIndexerCatalog`` keeps the provider versions the product advertises: the catalogue must say the same.

With ``--distribution <dir>`` the gate also checks a built distribution: ``tools/TOOLS-MANIFEST.json``
lists exactly the components the catalogue marks as embedded for that platform, every listed file is
present with the recorded size and SHA-256 (a pinned artifact must carry the catalogue's hash), nothing
else is shipped under ``tools/``, and the SBOM and the notices name every embedded component.

Self-test: scripts/quality/test_check_tools_manifest.py.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
CATALOG = Path("minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json")
DOCKERFILE = Path("docker/Dockerfile.mcp.release")
PROVIDER_CATALOG = Path("minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIndexerCatalog.java")
MANAGER = Path("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java")
JAVA_SOURCES = Path("minos-provider-scip/src/main/java")
BUILD_SCRIPT = Path("scripts/release/build-windows-distribution.ps1")
PAYLOAD_BUILDER = Path("scripts/release/build-embedded-tools.py")
SYNC_SCRIPT = Path("scripts/release/sync-tools-manifest.py")
MANIFEST_NAME = "TOOLS-MANIFEST.json"

PLATFORMS = frozenset({"windows-x64", "linux-x64", "any"})
SHA256 = re.compile(r"\b[0-9a-f]{64}\b")
COMMIT = re.compile(r"\b[0-9a-f]{40}\b")
ARG_LINE = re.compile(r"^\s*ARG\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)=(?P<value>\S*)\s*$")

# Provider id -> the constant or literal of ScipIndexerCatalog that carries its version.
PROVIDER_VERSION_CONSTANTS = {
    "scip-python": "SCIP_PYTHON_VERSION",
    "scip-clang": "SCIP_CLANG_VERSION",
    "scip-dotnet": "SCIP_DOTNET_VERSION",
    "scip-go": "SCIP_GO_VERSION",
    "rust-analyzer-scip": "RUST_ANALYZER_SCIP_VERSION",
}
PROVIDER_VERSION_LITERALS = ("scip-java", "scip-typescript")


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_catalog(root: Path, failures: list[str]) -> dict | None:
    path = root / CATALOG
    if not path.is_file():
        failures.append(f"{CATALOG}: the tools catalogue is missing")
        return None
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        failures.append(f"{CATALOG}: not valid JSON ({error})")
        return None
    return catalog


def check_catalog_shape(catalog: dict, failures: list[str]) -> None:
    if catalog.get("formatVersion") != 1:
        failures.append(f"{CATALOG}: formatVersion must be 1")
    seen: set[tuple[str, str]] = set()
    payloads: set[str] = set()
    ids: set[str] = set()
    for artifact in catalog.get("artifacts", []):
        name = f"artifact {artifact.get('id')}@{artifact.get('platform')}"
        for field in ("id", "platform", "version", "format", "url", "sha256", "license"):
            if not isinstance(artifact.get(field), str) or not artifact[field].strip():
                failures.append(f"{CATALOG}: {name} lacks the text field {field}")
        if artifact.get("platform") not in PLATFORMS:
            failures.append(f"{CATALOG}: {name} has an unknown platform")
        if not re.fullmatch(r"[0-9a-f]{64}", str(artifact.get("sha256", ""))):
            failures.append(f"{CATALOG}: {name} needs a lowercase hex SHA-256")
        if not str(artifact.get("url", "")).startswith("https://"):
            failures.append(f"{CATALOG}: {name} must be downloaded over HTTPS")
        key = (str(artifact.get("id")), str(artifact.get("platform")))
        if key in seen:
            failures.append(f"{CATALOG}: {name} is described twice")
        seen.add(key)
        ids.add(str(artifact.get("id")))
        if artifact.get("embedded"):
            payload = artifact.get("payload")
            if not payload:
                failures.append(f"{CATALOG}: embedded {name} names no payload file")
            elif payload in payloads:
                failures.append(f"{CATALOG}: payload {payload} is named twice")
            else:
                payloads.add(payload)
            if int(artifact.get("sizeBytes", 0) or 0) <= 0:
                failures.append(f"{CATALOG}: embedded {name} needs its exact sizeBytes")
    for component in catalog.get("assembled", []):
        name = f"assembled {component.get('id')}@{component.get('platform')}"
        for field in ("id", "platform", "provider", "version", "recipe", "license"):
            if not isinstance(component.get(field), str) or not component[field].strip():
                failures.append(f"{CATALOG}: {name} lacks the text field {field}")
        ids.add(str(component.get("id")))
        if component.get("embedded"):
            payload = component.get("payload")
            if not payload:
                failures.append(f"{CATALOG}: embedded {name} names no payload file")
            elif payload in payloads:
                failures.append(f"{CATALOG}: payload {payload} is named twice")
            else:
                payloads.add(payload)
    for provider in catalog.get("providers", []):
        for component in provider.get("components", []):
            if component not in ids:
                failures.append(f"{CATALOG}: provider {provider.get('id')} needs the undescribed component {component}")


def java_constant(source: str, name: str) -> str | None:
    match = re.search(rf'\b{re.escape(name)}\s*=\s*"([^"]+)"', source)
    return match.group(1) if match else None


def check_provider_versions(root: Path, catalog: dict, failures: list[str]) -> None:
    path = root / PROVIDER_CATALOG
    if not path.is_file():
        failures.append(f"{PROVIDER_CATALOG}: the provider catalogue is missing")
        return
    source = path.read_text(encoding="utf-8")
    expected: dict[str, str | None] = {}
    for provider_id, constant in PROVIDER_VERSION_CONSTANTS.items():
        expected[provider_id] = java_constant(source, constant)
    for provider_id in PROVIDER_VERSION_LITERALS:
        match = re.search(rf'new IndexerDescriptor\(\s*"{re.escape(provider_id)}",\s*"([^"]+)"', source)
        expected[provider_id] = match.group(1) if match else None
    described = {provider["id"]: provider for provider in catalog.get("providers", [])}
    for provider_id, version in expected.items():
        if version is None:
            failures.append(f"{PROVIDER_CATALOG}: cannot read the version of {provider_id}")
        elif provider_id not in described:
            failures.append(f"{CATALOG}: provider {provider_id} (version {version}) is not described")
        elif described[provider_id].get("version") != version:
            failures.append(
                f"{CATALOG}: provider {provider_id} is {described[provider_id].get('version')} "
                f"but ScipIndexerCatalog says {version}")
    for provider_id in described:
        if provider_id not in expected:
            failures.append(f"{CATALOG}: provider {provider_id} is not a provider of ScipIndexerCatalog")
    manager = root / MANAGER
    if manager.is_file():
        text = manager.read_text(encoding="utf-8")
        for provider_id, constant in (("scip-java", "SCIP_JAVA_VERSION"), ("scip-typescript", "SCIP_TYPESCRIPT_VERSION")):
            value = java_constant(text, constant)
            if provider_id in described and value != described[provider_id].get("version"):
                failures.append(f"{MANAGER}: {constant} is {value} but the tools catalogue says "
                                f"{described[provider_id].get('version')}")
    rust = next((a for a in catalog.get("artifacts", []) if a.get("id") == "rust-analyzer"), None)
    release = java_constant(source, "RUST_ANALYZER_SCIP_RELEASE")
    if rust is not None and release is not None and rust.get("version") != release:
        failures.append(f"{CATALOG}: rust-analyzer release is {rust.get('version')} but ScipIndexerCatalog says {release}")


def expected_docker_args(catalog: dict) -> dict[str, tuple[str, str]]:
    """ARG name -> (value, where it comes from) for everything the catalogue says the image carries."""
    expected: dict[str, tuple[str, str]] = {}
    conflicts: list[str] = []

    def put(name: str, value: str, origin: str) -> None:
        if name in expected and expected[name][0] != value:
            conflicts.append(f"{name}: {expected[name][1]} says {expected[name][0]} but {origin} says {value}")
        expected.setdefault(name, (value, origin))

    for provider in catalog.get("providers", []):
        for kind, name in (provider.get("dockerArgs") or {}).items():
            put(name, provider["version" if kind == "version" else kind], f"provider {provider['id']}")
    for artifact in catalog.get("artifacts", []):
        for kind, name in (artifact.get("dockerArgs") or {}).items():
            put(name, artifact["version" if kind == "version" else kind], f"artifact {artifact['id']}@{artifact['platform']}")
    if conflicts:
        raise ValueError("; ".join(conflicts))
    return expected


def check_dockerfile(root: Path, catalog: dict, failures: list[str]) -> None:
    path = root / DOCKERFILE
    if not path.is_file():
        failures.append(f"{DOCKERFILE}: the release Dockerfile is missing")
        return
    try:
        expected = expected_docker_args(catalog)
    except ValueError as error:
        failures.append(f"{CATALOG}: inconsistent Dockerfile ARG mapping ({error})")
        return
    actual: dict[str, tuple[str, int]] = {}
    lines = path.read_text(encoding="utf-8").splitlines()
    for number, line in enumerate(lines, start=1):
        match = ARG_LINE.match(line)
        if match:
            actual[match.group("name")] = (match.group("value"), number)
    for name, (value, origin) in sorted(expected.items()):
        if name not in actual:
            failures.append(f"{DOCKERFILE}: ARG {name} is missing (the catalogue, {origin}, says {value})")
        elif actual[name][0] != value:
            failures.append(f"{DOCKERFILE}:{actual[name][1]}: ARG {name} is {actual[name][0]} "
                            f"but the catalogue ({origin}) says {value}")
    mapped_hashes = {value for value, _ in expected.values() if SHA256.fullmatch(value)}
    for name, (value, number) in sorted(actual.items(), key=lambda item: item[1][1]):
        if name.endswith("_SHA256") and name not in expected:
            failures.append(f"{DOCKERFILE}:{number}: ARG {name} pins a hash the catalogue does not describe")
    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if stripped.startswith("#") or stripped.upper().startswith("FROM ") or ARG_LINE.match(line):
            continue
        for found in SHA256.findall(line):
            if found not in mapped_hashes:
                failures.append(f"{DOCKERFILE}:{number}: a SHA-256 is written here instead of coming from an ARG of the catalogue")


def check_java_sources(root: Path, failures: list[str]) -> None:
    base = root / JAVA_SOURCES
    if not base.is_dir():
        return
    for path in sorted(base.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), start=1):
            if SHA256.search(line) or COMMIT.search(line):
                failures.append(
                    f"{path.relative_to(root).as_posix()}:{number}: a pinned SHA-256 or commit is written in Java; "
                    f"it belongs to {CATALOG.name} only")


def check_consumers(root: Path, failures: list[str]) -> None:
    build = root / BUILD_SCRIPT
    if build.is_file():
        text = build.read_text(encoding="utf-8")
        if "build-embedded-tools.py" not in text:
            failures.append(f"{BUILD_SCRIPT}: the Windows distribution build does not call build-embedded-tools.py")
        if SHA256.search(text):
            failures.append(f"{BUILD_SCRIPT}: carries a SHA-256 of its own")
    else:
        failures.append(f"{BUILD_SCRIPT}: the distribution build script is missing")
    for consumer in (PAYLOAD_BUILDER, SYNC_SCRIPT):
        path = root / consumer
        if not path.is_file():
            failures.append(f"{consumer}: is missing")
            continue
        text = path.read_text(encoding="utf-8")
        if "embedded-tools.json" not in text:
            failures.append(f"{consumer}: does not read embedded-tools.json")
        if SHA256.search(text) or COMMIT.search(text):
            failures.append(f"{consumer}: carries a hash or commit of its own")


def embedded_entries(catalog: dict, platform: str) -> dict[str, dict]:
    entries: dict[str, dict] = {}
    for artifact in catalog.get("artifacts", []):
        if artifact.get("embedded") and artifact.get("platform") in (platform, "any"):
            entries[artifact["id"]] = {"kind": "artifact", **artifact}
    for component in catalog.get("assembled", []):
        if component.get("embedded") and component.get("platform") == platform:
            entries[component["id"]] = {"kind": "assembled", **component}
    return entries


def check_distribution(distribution: Path, catalog: dict, failures: list[str], variant: str = "full") -> None:
    tools = distribution / "tools"
    if variant == "lite":
        if tools.exists():
            failures.append("tools/: the lite distribution must not ship a tools directory")
        sbom = distribution / "supply-chain" / "minos.cdx.json"
        if sbom.is_file():
            names = {component.get("name") for component in json.loads(sbom.read_text(encoding="utf-8")).get("components", [])}
            for source in embedded_entries(catalog, "windows-x64").values():
                if source["id"] in names and source["kind"] == "artifact":
                    failures.append(f"supply-chain/minos.cdx.json: the lite SBOM names the embedded tool {source['id']}")
        return
    manifest_path = tools / MANIFEST_NAME
    if not manifest_path.is_file():
        failures.append(f"tools/{MANIFEST_NAME}: missing from the distribution")
        return
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        failures.append(f"tools/{MANIFEST_NAME}: not valid JSON ({error})")
        return
    platform = manifest.get("platform")
    if manifest.get("formatVersion") != 1 or platform not in PLATFORMS - {"any"}:
        failures.append(f"tools/{MANIFEST_NAME}: unsupported formatVersion or platform")
        return
    expected = embedded_entries(catalog, platform)
    listed = {entry.get("id"): entry for entry in manifest.get("files", [])}
    for component_id, source in sorted(expected.items()):
        entry = listed.get(component_id)
        if entry is None:
            failures.append(f"tools/{MANIFEST_NAME}: the catalogue embeds {component_id} but the manifest does not list it")
            continue
        if entry.get("kind") != source["kind"]:
            failures.append(f"tools/{MANIFEST_NAME}: {component_id} has the kind {entry.get('kind')}, expected {source['kind']}")
        if entry.get("version") != source["version"]:
            failures.append(f"tools/{MANIFEST_NAME}: {component_id} is version {entry.get('version')}, "
                            f"the catalogue says {source['version']}")
        if entry.get("path") != source["payload"]:
            failures.append(f"tools/{MANIFEST_NAME}: {component_id} is shipped as {entry.get('path')}, "
                            f"the catalogue says {source['payload']}")
        if entry.get("license") != source["license"]:
            failures.append(f"tools/{MANIFEST_NAME}: {component_id} license differs from the catalogue")
        file = tools / source["payload"]
        if not file.is_file():
            failures.append(f"tools/{source['payload']}: listed by the catalogue but absent from the distribution")
            continue
        actual_hash = sha256_of(file)
        actual_size = file.stat().st_size
        if source["kind"] == "artifact":
            if actual_hash != source["sha256"]:
                failures.append(f"tools/{source['payload']}: SHA-256 {actual_hash} is not the pinned {source['sha256']}")
            if actual_size != source.get("sizeBytes"):
                failures.append(f"tools/{source['payload']}: size {actual_size} is not the pinned {source.get('sizeBytes')}")
        if entry.get("sha256") != actual_hash or entry.get("sizeBytes") != actual_size:
            failures.append(f"tools/{MANIFEST_NAME}: {component_id} does not match the file it lists")
    for component_id in sorted(set(listed) - set(expected)):
        failures.append(f"tools/{MANIFEST_NAME}: lists {component_id}, which the catalogue does not embed for {platform}")
    shipped = {path.relative_to(tools).as_posix() for path in tools.rglob("*") if path.is_file()}
    declared = {source["payload"] for source in expected.values()} | {MANIFEST_NAME}
    for extra in sorted(shipped - declared):
        failures.append(f"tools/{extra}: shipped but described by neither the catalogue nor the manifest")
    check_distribution_evidence(distribution, expected, failures)


def check_distribution_evidence(distribution: Path, expected: dict[str, dict], failures: list[str]) -> None:
    sbom_path = distribution / "supply-chain" / "minos.cdx.json"
    notices_path = distribution / "supply-chain" / "THIRD-PARTY-NOTICES.txt"
    if sbom_path.is_file():
        sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
        names = {(component.get("name"), component.get("version")) for component in sbom.get("components", [])}
        for component_id, source in sorted(expected.items()):
            if (component_id, source["version"]) not in names:
                failures.append(f"supply-chain/minos.cdx.json: the embedded component {component_id} {source['version']} is not in the SBOM")
    else:
        failures.append("supply-chain/minos.cdx.json: missing from the distribution")
    if notices_path.is_file():
        notices = notices_path.read_text(encoding="utf-8")
        for component_id, source in sorted(expected.items()):
            if f"{component_id}:{source['version']}" not in notices:
                failures.append(f"supply-chain/THIRD-PARTY-NOTICES.txt: the embedded component {component_id} "
                                f"{source['version']} has no notice")
    else:
        failures.append("supply-chain/THIRD-PARTY-NOTICES.txt: missing from the distribution")


def check(root: Path, distribution: Path | None = None, variant: str = "full") -> tuple[list[str], int]:
    failures: list[str] = []
    catalog = load_catalog(root, failures)
    if catalog is None:
        return failures, 0
    check_catalog_shape(catalog, failures)
    check_provider_versions(root, catalog, failures)
    check_dockerfile(root, catalog, failures)
    check_java_sources(root, failures)
    check_consumers(root, failures)
    if distribution is not None:
        check_distribution(distribution, catalog, failures, variant)
    return failures, len(catalog.get("artifacts", [])) + len(catalog.get("assembled", []))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--distribution", type=Path, default=None,
                        help="a built distribution directory whose tools/ payload must match the catalogue")
    parser.add_argument("--variant", choices=("full", "lite"), default="full",
                        help="full ships tools/ and must match the catalogue; lite must ship no tools/")
    args = parser.parse_args(argv)
    failures, components = check(args.root.resolve(), args.distribution.resolve() if args.distribution else None, args.variant)
    if failures:
        for failure in failures:
            print(f"TOOLS MANIFEST FAILURE: {failure}", file=sys.stderr)
        return 1
    scope = "distribution checked" if args.distribution else "sources only"
    print(f"TOOLS MANIFEST GATE SUCCESS (components={components}, {scope})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

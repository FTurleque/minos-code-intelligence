#!/usr/bin/env python3
"""Assemble the tools payload of a MINOS distribution from embedded-tools.json.

Reads the single description of the shipped tools (embedded-tools.json) and, for one platform:

* fetches every pinned artifact the catalogue marks as embedded, refuses any file whose SHA-256 or size
  differs from the catalogue, and copies it under ``<output>/artifacts``;
* builds every assembled component from its repository-owned recipe (``npm-ci`` from the committed lockfile with
  scripts disabled, ``coursier-fetch`` of the pinned coordinate) with the pinned Node and Coursier, and stores
  it as a deterministic zip under ``<output>/assembled`` (fixed timestamps, sorted entries);
* writes ``<output>/TOOLS-MANIFEST.json`` (id, kind, version, path, SHA-256, size, license, origin);
* optionally extends the CycloneDX SBOM with every embedded component (the npm packages from the lockfile, the
  jars of the resolved scip-java classpath with the licenses of their POMs), so that the third-party notices
  generated from it cover the payload.

This script owns no list and no hash of its own: scripts/quality/check-tools-manifest.py checks that.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Callable
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json"
RESOURCES = CATALOG.parent
MANIFEST_NAME = "TOOLS-MANIFEST.json"
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
USER_AGENT = "MINOS-Code-Intelligence-build"

Fetch = Callable[[str, Path, int], None]


class BuildError(RuntimeError):
    pass


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path, limit: int) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    written = 0
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            written += len(chunk)
            if written > limit:
                raise BuildError(f"{url} exceeds the pinned size ({limit} bytes)")
            output.write(chunk)


def fetch_pinned(artifact: dict, cache: Path, fetch: Fetch = download) -> Path:
    """The verified file of a pinned artifact: from the cache when it still matches, else downloaded."""
    cache.mkdir(parents=True, exist_ok=True)
    cached = cache / f"{artifact['sha256']}-{artifact['id']}.{artifact['format']}"
    if cached.is_file() and sha256_of(cached) == artifact["sha256"]:
        return cached
    partial = cached.with_suffix(cached.suffix + ".partial")
    partial.unlink(missing_ok=True)
    limit = int(artifact.get("sizeBytes") or 0) or 512 * 1024 * 1024
    fetch(artifact["url"], partial, limit)
    actual = sha256_of(partial)
    if actual != artifact["sha256"]:
        partial.unlink(missing_ok=True)
        raise BuildError(f"{artifact['id']}: downloaded SHA-256 {actual} is not the pinned {artifact['sha256']}")
    if artifact.get("sizeBytes") and partial.stat().st_size != artifact["sizeBytes"]:
        partial.unlink(missing_ok=True)
        raise BuildError(f"{artifact['id']}: downloaded size differs from the pinned {artifact['sizeBytes']}")
    partial.replace(cached)
    return cached


def zip_tree(directory: Path, destination: Path) -> None:
    """A deterministic zip of the files under ``directory``: sorted entries, fixed timestamps, no extra fields."""
    files = sorted((path for path in directory.rglob("*") if path.is_file()), key=lambda p: p.relative_to(directory).as_posix())
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(directory).as_posix(), date_time=ZIP_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())


def extract(archive: Path, destination: Path) -> None:
    with zipfile.ZipFile(archive) as bundle:
        root = destination.resolve()
        for member in bundle.namelist():
            if not (destination / member).resolve().is_relative_to(root):
                raise BuildError(f"{archive.name}: entry escapes the extraction directory: {member}")
        bundle.extractall(destination)


def spdx_license(value: str) -> dict:
    simple = re.fullmatch(r"[A-Za-z0-9.+-]+", value) is not None
    return {"license": {"id": value} if simple else {"name": value}}


def artifact_component(entry: dict) -> dict:
    return {
        "type": "application",
        "name": entry["id"],
        "version": entry["version"],
        "purl": f"pkg:generic/{quote(entry['id'])}@{quote(entry['version'])}",
        "description": f"Pinned artifact shipped in the MINOS distribution ({entry['url']})"
        + (f". {entry['notice']}" if entry.get("notice") else ""),
        "licenses": [spdx_license(entry["license"])],
    }


# ---------------------------------------------------------------- npm-ci (scip-typescript)

def build_npm_tree(component: dict, node_zip: Path, work: Path) -> tuple[Path, list[dict]]:
    lock = RESOURCES / component["lock"]
    if not lock.is_file():
        raise BuildError(f"{component['id']}: lockfile {component['lock']} is missing")
    node_home = work / "node"
    extract(node_zip, node_home)
    roots = [path for path in node_home.iterdir() if path.is_dir()]
    if len(roots) != 1:
        raise BuildError("the Node.js archive must have one top-level directory")
    node = roots[0] / ("node.exe" if os.name == "nt" else "bin/node")
    npm_cli = roots[0] / "node_modules" / "npm" / "bin" / "npm-cli.js"
    if not node.is_file() or not npm_cli.is_file():
        raise BuildError("the pinned Node.js archive does not contain node and npm")
    tree = work / "tree"
    tree.mkdir()
    (tree / "package.json").write_text(
        json.dumps({"private": True, "dependencies": {component["package"]: component["version"]}}, indent=2) + "\n",
        encoding="utf-8", newline="\n")
    shutil.copyfile(lock, tree / "package-lock.json")
    env = dict(os.environ, PATH=str(node.parent) + os.pathsep + os.environ.get("PATH", ""))
    result = subprocess.run([str(node), str(npm_cli), "ci", "--prefix", str(tree), "--no-audit", "--no-fund",
                             "--ignore-scripts"], cwd=work, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        raise BuildError(f"npm ci failed: {result.stderr.strip()[-400:]}")
    entry_script = tree / "node_modules" / Path(component["package"]) / "dist" / "src" / "main.js"
    if not entry_script.is_file():
        raise BuildError(f"{component['id']}: the installed package has no dist/src/main.js")
    return tree, npm_components(tree / "package-lock.json")


def npm_components(lock: Path) -> list[dict]:
    components = []
    for key, package in json.loads(lock.read_text(encoding="utf-8")).get("packages", {}).items():
        if not key.startswith("node_modules/"):
            continue
        name = key.split("node_modules/")[-1]
        version = package.get("version", "")
        licenses = package.get("license")
        if not version or not licenses:
            raise BuildError(f"npm package {name} has no version or license in the lockfile")
        purl_name = quote(name, safe="/").replace("@", "%40", 1) if name.startswith("@") else quote(name)
        components.append({
            "type": "library", "name": name, "version": version, "purl": f"pkg:npm/{purl_name}@{version}",
            "licenses": [spdx_license(licenses if isinstance(licenses, str) else str(licenses))],
        })
    return components


# ---------------------------------------------------------------- coursier-fetch (scip-java)

def build_classpath_tree(component: dict, coursier_zip: Path, work: Path) -> tuple[Path, list[dict]]:
    cs_home = work / "cs"
    extract(coursier_zip, cs_home)
    launchers = [path for path in cs_home.rglob("*") if path.suffix.lower() == ".exe" or path.name == "cs"]
    if len(launchers) != 1:
        raise BuildError("the pinned Coursier archive must contain exactly one launcher")
    cache = work / "cache"
    cache.mkdir()
    env = dict(os.environ, COURSIER_CACHE=str(cache))
    result = subprocess.run([str(launchers[0]), "fetch", "--classpath", component["coordinate"]],
                            cwd=work, env=env, capture_output=True, text=True)
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if result.returncode != 0 or not lines:
        raise BuildError(f"coursier fetch failed: {result.stderr.strip()[-400:]}")
    tree = work / "classpath"
    tree.mkdir()
    names: list[str] = []
    components: list[dict] = []
    for entry in lines[-1].split(os.pathsep):
        jar = Path(entry.strip())
        if not jar.is_file() or jar.suffix != ".jar":
            raise BuildError(f"the resolved classpath names something that is not a jar: {jar.name}")
        if jar.name in names:
            raise BuildError(f"two classpath jars share the name {jar.name}")
        shutil.copyfile(jar, tree / jar.name)
        names.append(jar.name)
        components.append(maven_component(jar, cache))
    (tree / "classpath.txt").write_text("\n".join(names) + "\n", encoding="utf-8", newline="\n")
    return tree, components


def maven_component(jar: Path, cache: Path) -> dict:
    relative = jar.resolve().relative_to(cache.resolve()).as_posix()
    match = re.search(r"/maven2/(.+)/([^/]+)/([^/]+)/[^/]+\.jar$", relative)
    if not match:
        raise BuildError(f"cannot read the Maven coordinate of {jar.name} from the Coursier cache layout")
    group, artifact, version = match.group(1).replace("/", "."), match.group(2), match.group(3)
    licenses = pom_licenses(jar.parent / f"{artifact}-{version}.pom", cache)
    if not licenses:
        raise BuildError(f"no license found in the POM chain of {group}:{artifact}:{version}")
    return {"type": "library", "group": group, "name": artifact, "version": version,
            "purl": f"pkg:maven/{group}/{artifact}@{version}", "licenses": licenses}


def pom_licenses(pom: Path, cache: Path, depth: int = 0) -> list[dict]:
    if depth > 8 or not pom.is_file():
        return []
    root = ET.parse(pom).getroot()
    found = []
    for element in root.findall("{*}licenses/{*}license"):
        name = (element.findtext("{*}name", default="") or "").strip()
        url = (element.findtext("{*}url", default="") or "").strip()
        if name or url:
            found.append({"license": {k: v for k, v in (("name", name), ("url", url)) if v}})
    if found:
        return found
    parent = root.find("{*}parent")
    if parent is None:
        return []
    group = (parent.findtext("{*}groupId", default="") or "").strip()
    artifact = (parent.findtext("{*}artifactId", default="") or "").strip()
    version = (parent.findtext("{*}version", default="") or "").strip()
    candidates = list(cache.rglob(f"{artifact}-{version}.pom"))
    candidates = [path for path in candidates if group.replace(".", "/") in path.as_posix()]
    return pom_licenses(candidates[0], cache, depth + 1) if candidates else []


# ---------------------------------------------------------------- orchestration

RECIPES = {"npm-ci": "nodejs", "coursier-fetch": "coursier"}


def build(catalog: dict, platform: str, output: Path, cache: Path, fetch: Fetch = download,
          sbom: Path | None = None) -> dict:
    entries = [dict(kind="artifact", **a) for a in catalog["artifacts"]
               if a.get("embedded") and a["platform"] in (platform, "any")]
    entries += [dict(kind="assembled", **a) for a in catalog["assembled"]
                if a.get("embedded") and a["platform"] == platform]
    if not entries:
        raise BuildError(f"the catalogue embeds nothing for {platform}")
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    files: list[dict] = []
    components: list[dict] = []
    pinned: dict[str, Path] = {}
    for entry in sorted((e for e in entries if e["kind"] == "artifact"), key=lambda e: e["id"]):
        source = fetch_pinned(entry, cache, fetch)
        target = output / entry["payload"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        pinned[entry["id"]] = target
        components.append(artifact_component(entry))
        files.append(manifest_entry(entry, target, entry["url"]))
    for entry in sorted((e for e in entries if e["kind"] == "assembled"), key=lambda e: e["id"]):
        needed = RECIPES.get(entry["recipe"])
        if needed is None:
            raise BuildError(f"{entry['id']}: unknown recipe {entry['recipe']}")
        if needed not in pinned:
            raise BuildError(f"{entry['id']}: the recipe {entry['recipe']} needs the embedded artifact {needed}")
        with tempfile.TemporaryDirectory(prefix="minos-tools-") as temporary:
            work = Path(temporary)
            if entry["recipe"] == "npm-ci":
                tree, tree_components = build_npm_tree(entry, pinned[needed], work)
            else:
                tree, tree_components = build_classpath_tree(entry, pinned[needed], work)
            target = output / entry["payload"]
            zip_tree(tree, target)
        components.append(artifact_component({**entry, "url": f"recipe:{entry['recipe']}"}))
        components.extend(tree_components)
        files.append(manifest_entry(entry, target, f"recipe:{entry['recipe']}"))
    manifest = {"formatVersion": 1, "platform": platform, "files": files}
    (output / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n",
                                        encoding="utf-8", newline="\n")
    if sbom is not None:
        extend_sbom(sbom, components)
    return manifest


def manifest_entry(entry: dict, file: Path, origin: str) -> dict:
    return {"id": entry["id"], "kind": entry["kind"], "version": entry["version"], "path": entry["payload"],
            "sha256": sha256_of(file), "sizeBytes": file.stat().st_size, "license": entry["license"], "origin": origin}


def extend_sbom(sbom: Path, components: list[dict]) -> None:
    document = json.loads(sbom.read_text(encoding="utf-8"))
    existing = document.setdefault("components", [])
    known = {component.get("purl") for component in existing}
    for component in components:
        if component["purl"] not in known:
            existing.append(component)
            known.add(component["purl"])
    sbom.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--platform", default="windows-x64")
    parser.add_argument("--output", required=True, type=Path, help="the tools directory of the distribution")
    parser.add_argument("--cache", type=Path, default=ROOT / "target" / "tools-cache")
    parser.add_argument("--sbom", type=Path, default=None, help="CycloneDX SBOM to extend with the embedded components")
    args = parser.parse_args(argv)
    try:
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        manifest = build(catalog, args.platform, args.output.resolve(), args.cache.resolve(), sbom=args.sbom)
    except (BuildError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as failure:
        print(f"EMBEDDED TOOLS BUILD FAILED: {failure}", file=sys.stderr)
        return 1
    total = sum(entry["sizeBytes"] for entry in manifest["files"])
    for entry in manifest["files"]:
        print(f"  {entry['id']:28} {entry['sizeBytes'] / 1048576:8.1f} MiB  {entry['sha256'][:12]}")
    print(f"EMBEDDED TOOLS BUILD SUCCESS (platform={args.platform}, components={len(manifest['files'])}, "
          f"bytes={total})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

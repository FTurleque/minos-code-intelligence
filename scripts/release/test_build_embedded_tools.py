#!/usr/bin/env python3
"""Self-test of build-embedded-tools.py: reproducible zips, pinned hashes enforced, manifest the gate accepts."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_BUILDER = importlib.util.spec_from_file_location("build_embedded_tools", Path(__file__).parent / "build-embedded-tools.py")
builder = importlib.util.module_from_spec(_BUILDER)
_BUILDER.loader.exec_module(builder)
_GATE = importlib.util.spec_from_file_location(
    "check_tools_manifest", Path(__file__).parents[1] / "quality" / "check-tools-manifest.py")
gate = importlib.util.module_from_spec(_GATE)
_GATE.loader.exec_module(gate)

TOOL = b"pinned tool bytes"


def catalog(sha: str | None = None, recipe: str | None = None) -> dict:
    value = {
        "formatVersion": 1,
        "providers": [],
        "artifacts": [{
            "id": "maven", "platform": "any", "version": "3.9.16", "format": "zip",
            "url": "https://repo.example/maven.zip", "sha256": sha or hashlib.sha256(TOOL).hexdigest(),
            "sizeBytes": len(TOOL), "license": "Apache-2.0", "embedded": True,
            "payload": "artifacts/maven-3.9.16-any.zip"}],
        "assembled": [],
    }
    if recipe:
        value["assembled"].append({
            "id": "tree", "platform": "windows-x64", "provider": "x", "version": "1", "recipe": recipe,
            "license": "MIT", "embedded": True, "payload": "assembled/tree-1-windows-x64.zip"})
    return value


def serve(content: bytes):
    calls = []

    def fetch(url: str, destination: Path, limit: int) -> None:
        calls.append(url)
        destination.write_bytes(content)
    fetch.calls = calls
    return fetch


class BuildEmbeddedToolsTest(unittest.TestCase):

    def test_the_payload_is_the_pinned_artifact_and_the_manifest_records_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fetch = serve(TOOL)
            manifest = builder.build(catalog(), "windows-x64", root / "tools", root / "cache", fetch)
            shipped = root / "tools" / "artifacts" / "maven-3.9.16-any.zip"
            self.assertEqual(TOOL, shipped.read_bytes())
            entry = manifest["files"][0]
            self.assertEqual(hashlib.sha256(TOOL).hexdigest(), entry["sha256"])
            self.assertEqual("artifact", entry["kind"])
            self.assertEqual(manifest, json.loads((root / "tools" / "TOOLS-MANIFEST.json").read_text(encoding="utf-8")))

    def test_a_download_whose_hash_differs_from_the_catalogue_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(builder.BuildError) as raised:
                builder.build(catalog(), "windows-x64", root / "tools", root / "cache", serve(b"tampered"))
            self.assertIn("is not the pinned", str(raised.exception))
            self.assertFalse(list((root / "cache").glob("*maven*")), "a refused download must not enter the cache")

    def test_a_verified_cache_entry_is_reused_without_downloading_again(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            builder.build(catalog(), "windows-x64", root / "tools", root / "cache", serve(TOOL))
            second = serve(b"must not be called")
            builder.build(catalog(), "windows-x64", root / "tools2", root / "cache", second)
            self.assertEqual([], second.calls)

    def test_a_corrupted_cache_entry_is_replaced_not_trusted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            builder.build(catalog(), "windows-x64", root / "tools", root / "cache", serve(TOOL))
            for cached in (root / "cache").glob("*maven*"):
                cached.write_bytes(b"corrupted")
            fetch = serve(TOOL)
            builder.build(catalog(), "windows-x64", root / "tools2", root / "cache", fetch)
            self.assertEqual(1, len(fetch.calls))

    def test_zips_are_reproducible_whatever_the_order_or_time_of_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("one", "two"):
                tree = root / name
                (tree / "b").mkdir(parents=True)
                (tree / "a.txt").write_text("a", encoding="utf-8")
                (tree / "b" / "c.txt").write_text("c", encoding="utf-8")
                builder.zip_tree(tree, root / f"{name}.zip")
            self.assertEqual(builder.sha256_of(root / "one.zip"), builder.sha256_of(root / "two.zip"))

    def test_an_unknown_recipe_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(builder.BuildError):
                builder.build(catalog(recipe="shell-out"), "windows-x64", root / "tools", root / "cache", serve(TOOL))

    def test_a_catalogue_that_embeds_nothing_for_the_platform_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = catalog()
            value["artifacts"][0]["embedded"] = False
            with self.assertRaises(builder.BuildError):
                builder.build(value, "windows-x64", root / "tools", root / "cache", serve(TOOL))

    def test_what_is_built_is_what_the_distribution_gate_accepts_and_its_sbom_names_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            distribution = root / "dist"
            (distribution / "supply-chain").mkdir(parents=True)
            sbom = distribution / "supply-chain" / "minos.cdx.json"
            sbom.write_text(json.dumps({"components": []}), encoding="utf-8")
            builder.build(catalog(), "windows-x64", distribution / "tools", root / "cache", serve(TOOL), sbom=sbom)
            (distribution / "supply-chain" / "THIRD-PARTY-NOTICES.txt").write_text("maven:3.9.16\n", encoding="utf-8")
            failures: list[str] = []
            gate.check_distribution(distribution, catalog(), failures)
            self.assertEqual([], failures)
            names = {(c["name"], c["version"]) for c in json.loads(sbom.read_text(encoding="utf-8"))["components"]}
            self.assertIn(("maven", "3.9.16"), names)

    def test_a_payload_altered_after_the_build_is_refused_by_the_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            distribution = root / "dist"
            (distribution / "supply-chain").mkdir(parents=True)
            sbom = distribution / "supply-chain" / "minos.cdx.json"
            sbom.write_text(json.dumps({"components": []}), encoding="utf-8")
            builder.build(catalog(), "windows-x64", distribution / "tools", root / "cache", serve(TOOL), sbom=sbom)
            (distribution / "supply-chain" / "THIRD-PARTY-NOTICES.txt").write_text("maven:3.9.16\n", encoding="utf-8")
            (distribution / "tools" / "artifacts" / "maven-3.9.16-any.zip").write_bytes(b"altered")
            failures: list[str] = []
            gate.check_distribution(distribution, catalog(), failures)
            self.assertTrue(any("is not the pinned" in failure for failure in failures), failures)


if __name__ == "__main__":
    unittest.main()

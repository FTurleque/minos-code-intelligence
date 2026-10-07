#!/usr/bin/env python3
"""Self-test of check-tools-manifest.py: one witness per way the tools description can drift."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-tools-manifest.py"
_SPEC = importlib.util.spec_from_file_location("check_tools_manifest", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

TOOL_BYTES = b"pinned tool"
TOOL_SHA = hashlib.sha256(TOOL_BYTES).hexdigest()
OTHER_SHA = hashlib.sha256(b"something else").hexdigest()
TREE_BYTES = b"assembled tree"
TREE_SHA = hashlib.sha256(TREE_BYTES).hexdigest()


def catalog(sha: str = TOOL_SHA) -> dict:
    return {
        "formatVersion": 1,
        "providers": [
            {"id": "scip-java", "version": "1.0.0", "license": "Apache-2.0",
             "dockerArgs": {"version": "SCIP_JAVA_VERSION"}, "components": ["maven", "classpath"]},
            {"id": "scip-go", "version": "2.0.0", "license": "Apache-2.0",
             "dockerArgs": {"version": "SCIP_GO_VERSION"}, "components": []},
        ],
        "artifacts": [
            {"id": "maven", "platform": "any", "version": "3.9.16", "format": "zip",
             "url": "https://repo.example/maven.zip", "sha256": sha, "sizeBytes": len(TOOL_BYTES),
             "license": "Apache-2.0", "embedded": True, "payload": "artifacts/maven-3.9.16-any.zip",
             "dockerArgs": {"version": "MAVEN_VERSION", "sha256": "MAVEN_ZIP_SHA256"}},
        ],
        "assembled": [
            {"id": "classpath", "platform": "windows-x64", "provider": "scip-java", "version": "1.0.0",
             "recipe": "coursier-fetch", "license": "Apache-2.0", "embedded": True,
             "payload": "assembled/classpath-1.0.0-windows-x64.zip"},
        ],
    }


def dockerfile(sha: str = TOOL_SHA, maven: str = "3.9.16", java: str = "1.0.0", extra: str = "") -> str:
    return (f"FROM eclipse-temurin:24@sha256:{'a' * 64}\nARG MAVEN_VERSION={maven}\nARG MAVEN_ZIP_SHA256={sha}\n"
            f"ARG SCIP_JAVA_VERSION={java}\nARG SCIP_GO_VERSION=2.0.0\n{extra}")


PROVIDER_CATALOG = '''
public static final String SCIP_GO_VERSION = "2.0.0";
public static final String SCIP_PYTHON_VERSION = "0.1";
public static final String SCIP_CLANG_VERSION = "0.1";
public static final String SCIP_DOTNET_VERSION = "0.1";
public static final String RUST_ANALYZER_SCIP_VERSION = "0.1";
return new IndexerDescriptor("scip-java", "1.0.0", "scip-java");
return new IndexerDescriptor("scip-typescript", "0.4.0", "scip-typescript");
'''


class CheckToolsManifestTest(unittest.TestCase):

    def tree(self, directory: Path, **overrides) -> Path:
        files = {
            str(gate.CATALOG): json.dumps(overrides.get("catalog", self.minimal_catalog())),
            str(gate.DOCKERFILE): overrides.get("dockerfile", dockerfile()),
            str(gate.PROVIDER_CATALOG): overrides.get("provider_catalog", self.minimal_provider_catalog()),
            str(gate.MANAGER): 'SCIP_JAVA_VERSION = "1.0.0"; SCIP_TYPESCRIPT_VERSION = "0.4.0";',
            str(gate.JAVA_SOURCES / "Other.java"): overrides.get("java", "class Other { }"),
            str(gate.BUILD_SCRIPT): "python build-embedded-tools.py",
            str(gate.PAYLOAD_BUILDER): "reads embedded-tools.json",
            str(gate.SYNC_SCRIPT): "reads embedded-tools.json",
        }
        for name, content in files.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        return directory

    @staticmethod
    def minimal_catalog() -> dict:
        value = catalog()
        # The gate expects every provider of ScipIndexerCatalog; the fixture catalogue carries them all.
        for provider_id, version in (("scip-python", "0.1"), ("scip-clang", "0.1"), ("scip-dotnet", "0.1"),
                                     ("rust-analyzer-scip", "0.1"), ("scip-typescript", "0.4.0")):
            value["providers"].append({"id": provider_id, "version": version, "license": "MIT", "components": []})
        return value

    @staticmethod
    def minimal_provider_catalog() -> str:
        return PROVIDER_CATALOG

    def run_gate(self, distribution=None, variant: str = "full", **overrides) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = self.tree(Path(directory), **overrides)
            if callable(distribution):
                distribution = distribution(root)
            failures, _ = gate.check(root, distribution, variant)
            return failures

    def test_a_consistent_tree_passes(self):
        self.assertEqual([], self.run_gate())

    def test_a_dockerfile_hash_that_differs_from_the_catalogue_is_refused(self):
        failures = self.run_gate(dockerfile=dockerfile(sha=OTHER_SHA))
        self.assertTrue(any("MAVEN_ZIP_SHA256" in failure for failure in failures), failures)

    def test_a_dockerfile_version_that_differs_from_the_catalogue_is_refused(self):
        failures = self.run_gate(dockerfile=dockerfile(maven="3.9.17"))
        self.assertTrue(any("MAVEN_VERSION" in failure for failure in failures), failures)

    def test_a_provider_version_that_differs_from_the_dockerfile_is_refused(self):
        failures = self.run_gate(dockerfile=dockerfile(java="0.9.0"))
        self.assertTrue(any("SCIP_JAVA_VERSION" in failure for failure in failures), failures)

    def test_a_second_copy_of_a_hash_in_the_dockerfile_is_refused(self):
        failures = self.run_gate(dockerfile=dockerfile(extra=f"RUN echo {OTHER_SHA} | sha256sum -c -\n"))
        self.assertTrue(any("instead of coming from an ARG" in failure for failure in failures), failures)

    def test_an_undescribed_hash_arg_is_refused(self):
        failures = self.run_gate(dockerfile=dockerfile(extra=f"ARG TOOL_LINUX_SHA256={OTHER_SHA}\n"))
        self.assertTrue(any("TOOL_LINUX_SHA256" in failure for failure in failures), failures)

    def test_a_pinned_sha256_written_in_java_is_refused(self):
        failures = self.run_gate(java=f'class Other {{ static final String PIN = "{OTHER_SHA}"; }}')
        self.assertTrue(any("Other.java:1" in failure for failure in failures), failures)

    def test_a_catalogue_version_that_differs_from_the_provider_catalogue_is_refused(self):
        provider_catalog = PROVIDER_CATALOG.replace('"scip-java", "1.0.0"', '"scip-java", "1.1.0"')
        failures = self.run_gate(provider_catalog=provider_catalog)
        self.assertTrue(any("scip-java" in failure and "ScipIndexerCatalog" in failure for failure in failures), failures)

    def test_an_embedded_artifact_without_a_payload_is_refused(self):
        broken = self.minimal_catalog()
        del broken["artifacts"][0]["payload"]
        failures = self.run_gate(catalog=broken)
        self.assertTrue(any("names no payload" in failure for failure in failures), failures)

    def test_a_provider_that_needs_an_undescribed_component_is_refused(self):
        broken = self.minimal_catalog()
        broken["providers"][0]["components"].append("ghost")
        failures = self.run_gate(catalog=broken)
        self.assertTrue(any("ghost" in failure for failure in failures), failures)

    def test_a_build_script_that_does_not_call_the_payload_builder_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.tree(Path(directory))
            (root / gate.BUILD_SCRIPT).write_text("nothing here", encoding="utf-8")
            failures, _ = gate.check(root)
        self.assertTrue(any("does not call build-embedded-tools.py" in failure for failure in failures), failures)

    # ------------------------------------------------------------------ distribution

    @staticmethod
    def write_distribution(root: Path, tool: bytes = TOOL_BYTES, tree: bytes = TREE_BYTES, listed_tool_sha=None,
                           extra: bool = False, drop_tool: bool = False, notices: str | None = None) -> Path:
        distribution = root / "dist"
        tools = distribution / "tools"
        (tools / "artifacts").mkdir(parents=True)
        (tools / "assembled").mkdir(parents=True)
        if not drop_tool:
            (tools / "artifacts" / "maven-3.9.16-any.zip").write_bytes(tool)
        (tools / "assembled" / "classpath-1.0.0-windows-x64.zip").write_bytes(tree)
        if extra:
            (tools / "stowaway.exe").write_bytes(b"x")
        manifest = {"formatVersion": 1, "platform": "windows-x64", "files": [
            {"id": "maven", "kind": "artifact", "version": "3.9.16", "path": "artifacts/maven-3.9.16-any.zip",
             "sha256": listed_tool_sha or hashlib.sha256(tool).hexdigest(), "sizeBytes": len(tool),
             "license": "Apache-2.0"},
            {"id": "classpath", "kind": "assembled", "version": "1.0.0",
             "path": "assembled/classpath-1.0.0-windows-x64.zip", "sha256": hashlib.sha256(tree).hexdigest(),
             "sizeBytes": len(tree), "license": "Apache-2.0"},
        ]}
        (tools / "TOOLS-MANIFEST.json").write_text(json.dumps(manifest), encoding="utf-8")
        supply = distribution / "supply-chain"
        supply.mkdir()
        (supply / "minos.cdx.json").write_text(json.dumps({"components": [
            {"name": "maven", "version": "3.9.16"}, {"name": "classpath", "version": "1.0.0"}]}), encoding="utf-8")
        (supply / "THIRD-PARTY-NOTICES.txt").write_text(
            notices if notices is not None else "maven:3.9.16\nclasspath:1.0.0\n", encoding="utf-8")
        return distribution

    def test_a_distribution_that_carries_exactly_the_catalogued_tools_passes(self):
        self.assertEqual([], self.run_gate(distribution=lambda root: self.write_distribution(root)))

    def test_an_embedded_artifact_altered_after_the_build_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_distribution(root, tool=b"altered"))
        self.assertTrue(any("is not the pinned" in failure for failure in failures), failures)

    def test_a_manifest_hash_that_does_not_match_its_file_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_distribution(root, listed_tool_sha=OTHER_SHA))
        self.assertTrue(any("does not match the file it lists" in failure for failure in failures), failures)

    def test_a_catalogued_tool_missing_from_the_distribution_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_distribution(root, drop_tool=True))
        self.assertTrue(any("absent from the distribution" in failure for failure in failures), failures)

    def test_a_file_shipped_but_described_nowhere_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_distribution(root, extra=True))
        self.assertTrue(any("stowaway.exe" in failure for failure in failures), failures)

    def test_an_embedded_component_without_a_notice_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_distribution(root, notices="maven:3.9.16\n"))
        self.assertTrue(any("has no notice" in failure for failure in failures), failures)


    @staticmethod
    def write_lite(root: Path, with_tools: bool = False, sbom_names=()) -> Path:
        distribution = root / "dist-lite"
        (distribution / "supply-chain").mkdir(parents=True)
        (distribution / "supply-chain" / "minos.cdx.json").write_text(
            json.dumps({"components": [{"name": name, "version": "1"} for name in sbom_names]}), encoding="utf-8")
        if with_tools:
            (distribution / "tools").mkdir()
            (distribution / "tools" / "TOOLS-MANIFEST.json").write_text("{}", encoding="utf-8")
        return distribution

    def test_a_lite_distribution_without_tools_passes(self):
        self.assertEqual([], self.run_gate(distribution=lambda root: self.write_lite(root), variant="lite"))

    def test_a_lite_distribution_that_ships_tools_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_lite(root, with_tools=True), variant="lite")
        self.assertTrue(any("lite distribution must not ship" in failure for failure in failures), failures)

    def test_a_lite_sbom_that_names_an_embedded_tool_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_lite(root, sbom_names=("maven",)), variant="lite")
        self.assertTrue(any("lite SBOM names" in failure for failure in failures), failures)

    def test_a_full_distribution_without_tools_is_refused(self):
        failures = self.run_gate(distribution=lambda root: self.write_lite(root), variant="full")
        self.assertTrue(any("missing from the distribution" in failure for failure in failures), failures)


class ShippedTreeTest(unittest.TestCase):
    def test_the_repository_itself_passes(self):
        failures, components = gate.check(gate.DEFAULT_ROOT)
        self.assertEqual([], failures)
        self.assertGreater(components, 0)


if __name__ == "__main__":
    unittest.main()

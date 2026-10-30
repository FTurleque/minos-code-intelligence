#!/usr/bin/env python3
"""Self-test of check-tools-manifest.py: one witness per way the tools description can drift."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import re
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
             "recipe": "coursier-fetch", "coordinate": "org.example:classpath:1.0.0",
             "lock": "classpath.lock.json", "license": "Apache-2.0", "embedded": True,
             "payload": "assembled/classpath-1.0.0-windows-x64.zip"},
        ],
    }


def lock(version: str = "1.0.0", coordinate: str = "org.example:classpath:1.0.0", jars=None) -> dict:
    if jars is None:
        jars = [{"name": f"classpath-{version}.jar", "size": 10, "sha256": TOOL_SHA},
                {"name": "dependency-2.jar", "size": 20, "sha256": OTHER_SHA}]
    return {"coordinate": coordinate, "version": version, "jars": jars}


def inventory(sdk: str = "${dotnet_sdk_version}", toolchain: str = "${go_toolchain_version}",
              runtime: str = "Node ${node_runtime_version}") -> str:
    """The RUN instruction that writes the provider inventory, shaped like the one of the release Dockerfile."""
    return ('RUN set -eux; \\\n'
            '    go_toolchain_version="$(go env GOVERSION)"; \\\n'
            "    printf '%s\\n' \\\n"
            "      '{' \\\n"
            f'      "    {{\\"id\\":\\"scip-typescript\\",\\"version\\":\\"${{SCIP_TYPESCRIPT_VERSION}}\\",\\"runtime\\":\\"{runtime}\\"}}," \\\n'
            f'      "    {{\\"id\\":\\"scip-dotnet\\",\\"sdk\\":\\"{sdk}\\"}}," \\\n'
            f'      "    {{\\"id\\":\\"scip-go\\",\\"version\\":\\"${{SCIP_GO_VERSION}}\\",\\"toolchain\\":\\"{toolchain}\\"}}" \\\n'
            "      '}' > /opt/minos/provider-evidence/provider-inventory.json\n")


def dockerfile(sha: str = TOOL_SHA, maven: str = "3.9.16", java: str = "1.0.0", extra: str = "",
               inventory_block: str | None = None) -> str:
    return (f"FROM eclipse-temurin:24@sha256:{'a' * 64}\nARG MAVEN_VERSION={maven}\nARG MAVEN_ZIP_SHA256={sha}\n"
            f"ARG SCIP_JAVA_VERSION={java}\nARG SCIP_GO_VERSION=2.0.0\n{extra}"
            f"{inventory() if inventory_block is None else inventory_block}")


def node(platform: str, version: str) -> dict:
    return {"id": "nodejs", "platform": platform, "version": version, "format": "zip",
            "url": f"https://nodejs.example/node-v{version}-{platform}.zip", "sha256": OTHER_SHA,
            "sizeBytes": 1, "license": "MIT", "embedded": False}


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
        if overrides.get("lock", True) is not None:
            files[str(gate.CATALOG.parent / "classpath.lock.json")] = json.dumps(overrides.get("lock", lock()))
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

    def test_a_coursier_fetch_component_without_a_lock_file_is_refused(self):
        failures = self.run_gate(lock=None)
        self.assertTrue(any("missing or unreadable" in f for f in failures), failures)

    def test_a_lock_that_does_not_match_the_catalogue_version_is_refused(self):
        failures = self.run_gate(lock=lock(version="0.9.0"))
        self.assertTrue(any("coordinate and version" in f for f in failures), failures)

    def test_a_lock_for_another_coordinate_is_refused(self):
        failures = self.run_gate(lock=lock(coordinate="org.other:classpath:1.0.0"))
        self.assertTrue(any("coordinate and version" in f for f in failures), failures)

    def test_a_lock_that_does_not_pin_the_artifact_itself_is_refused(self):
        failures = self.run_gate(lock=lock(jars=[{"name": "dependency-2.jar", "size": 20, "sha256": OTHER_SHA}]))
        self.assertTrue(any("does not pin" in f for f in failures), failures)

    def test_a_lock_with_a_malformed_or_duplicated_jar_is_refused(self):
        for jars in ([{"name": "classpath-1.0.0.jar", "size": 10, "sha256": "not-a-hash"}],
                     [{"name": "classpath-1.0.0.jar", "size": 10, "sha256": TOOL_SHA},
                      {"name": "classpath-1.0.0.jar", "size": 10, "sha256": TOOL_SHA}],
                     [{"name": "classpath-1.0.0.jar", "size": 0, "sha256": TOOL_SHA}]):
            with self.subTest(jars=jars):
                failures = self.run_gate(lock=lock(jars=jars))
                self.assertTrue(any("malformed or duplicated" in f for f in failures), failures)

    def test_an_empty_lock_is_refused(self):
        failures = self.run_gate(lock=lock(jars=[]))
        self.assertTrue(any("lists no jar" in f for f in failures), failures)

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

    # ------------------------------------------------------------------ Node runtime

    def test_node_majors_that_differ_between_platforms_are_refused(self):
        broken = self.minimal_catalog()
        broken["artifacts"] += [node("windows-x64", "24.20.0"), node("linux-x64", "20.20.2")]
        failures = self.run_gate(catalog=broken)
        self.assertTrue(any("24.20.0" in failure and "20.20.2" in failure for failure in failures), failures)

    def test_node_of_the_same_major_on_every_platform_is_accepted(self):
        aligned = self.minimal_catalog()
        aligned["artifacts"] += [node("windows-x64", "24.20.0"), node("linux-x64", "24.19.1")]
        self.assertEqual([], self.run_gate(catalog=aligned))

    # ------------------------------------------------------------------ provider inventory

    def test_a_literal_version_in_the_provider_inventory_is_refused_with_its_line(self):
        for field, block in (("toolchain", inventory(toolchain="1.97.1")),
                             ("sdk", inventory(sdk="10.0.302")),
                             ("runtime", inventory(runtime="Node 20")),
                             ("toolchain", inventory(toolchain="${go_toolchain_version:-1.97.1}"))):
            with self.subTest(block=block):
                text = dockerfile(inventory_block=block)
                line = next(number for number, content in enumerate(text.splitlines(), start=1)
                            if f'\\"{field}\\"' in content)
                failures = self.run_gate(dockerfile=text)
                self.assertTrue(any(f"{gate.DOCKERFILE}:{line}:" in failure and field in failure
                                    for failure in failures), failures)

    def test_an_inventory_version_read_into_a_shell_variable_is_accepted(self):
        block = inventory(sdk="${dotnet_sdk_version}", toolchain="$rust_toolchain_version",
                          runtime="Node ${node_runtime_version}")
        self.assertEqual([], self.run_gate(dockerfile=dockerfile(inventory_block=block)))

    def test_an_inventory_version_that_comes_from_no_variable_is_refused(self):
        failures = self.run_gate(dockerfile=dockerfile(inventory_block=inventory(toolchain="stable")))
        self.assertTrue(any("toolchain" in failure and "shell variable" in failure for failure in failures), failures)

    def test_a_dockerfile_that_writes_no_provider_inventory_is_refused(self):
        failures = self.run_gate(dockerfile=dockerfile(inventory_block=""))
        self.assertTrue(any("provider-inventory.json" in failure for failure in failures), failures)

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

    def test_the_shipped_catalogue_with_an_older_node_major_on_linux_is_refused(self):
        shipped = json.loads((gate.DEFAULT_ROOT / gate.CATALOG).read_text(encoding="utf-8"))
        linux = next(artifact for artifact in shipped["artifacts"]
                     if artifact["id"] == gate.NODE_RUNTIME and artifact["platform"] == "linux-x64")
        linux["version"] = "20.20.2"
        failures: list[str] = []
        gate.check_node_runtime(shipped, failures)
        self.assertTrue(any("linux-x64 is 20.20.2" in failure for failure in failures), failures)

    def test_the_shipped_dockerfile_with_a_literal_toolchain_is_refused(self):
        shipped = (gate.DEFAULT_ROOT / gate.DOCKERFILE).read_text(encoding="utf-8")
        mutated, count = re.subn(r'(\\"toolchain\\":\\")[^"\\]*(\\")', r"\g<1>1.97.1\g<2>", shipped, count=1)
        self.assertEqual(1, count, "the shipped Dockerfile no longer writes a toolchain field")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / gate.DOCKERFILE
            path.parent.mkdir(parents=True)
            path.write_text(mutated, encoding="utf-8")
            failures: list[str] = []
            gate.check_provider_inventory(Path(directory), failures)
        self.assertTrue(any("toolchain" in failure and "1.97.1" in failure for failure in failures), failures)


_SYNC_SPEC = importlib.util.spec_from_file_location("sync_tools_manifest", gate.DEFAULT_ROOT / gate.SYNC_SCRIPT)
sync_script = importlib.util.module_from_spec(_SYNC_SPEC)
_SYNC_SPEC.loader.exec_module(sync_script)


class SyncToolsManifestTest(unittest.TestCase):
    """sync-tools-manifest.py --write changes the ARG values and nothing else, line endings included."""

    def write_tree(self, directory: Path, newline: str) -> Path:
        dockerfile_path = directory / gate.DOCKERFILE
        dockerfile_path.parent.mkdir(parents=True)
        dockerfile_path.write_bytes(dockerfile(java="0.9.0").replace("\n", newline).encode("utf-8"))
        catalogue_path = directory / gate.CATALOG
        catalogue_path.parent.mkdir(parents=True)
        catalogue_path.write_text(json.dumps(catalog()), encoding="utf-8")
        return dockerfile_path

    def test_write_keeps_the_crlf_line_endings_of_the_dockerfile(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_tree(Path(directory), "\r\n")
            self.assertEqual([], sync_script.sync(Path(directory), write=True))
            written = path.read_bytes()
        self.assertEqual(dockerfile().replace("\n", "\r\n").encode("utf-8"), written)

    def test_write_keeps_the_lf_line_endings_of_the_dockerfile(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_tree(Path(directory), "\n")
            self.assertEqual([], sync_script.sync(Path(directory), write=True))
            written = path.read_bytes()
        self.assertEqual(dockerfile().encode("utf-8"), written)

    def test_check_reports_the_differing_arg_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_tree(Path(directory), "\r\n")
            before = path.read_bytes()
            failures = sync_script.sync(Path(directory), write=False)
            self.assertEqual(before, path.read_bytes())
        self.assertTrue(any("ARG SCIP_JAVA_VERSION is 0.9.0" in failure for failure in failures), failures)


if __name__ == "__main__":
    unittest.main()

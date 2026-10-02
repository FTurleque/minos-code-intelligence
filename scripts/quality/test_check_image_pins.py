#!/usr/bin/env python3
"""Self-test of check-image-pins.py: one witness per way a container image can escape its pin."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-image-pins.py"
_SPEC = importlib.util.spec_from_file_location("check_image_pins", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

DIGEST = "sha256:" + "a" * 64
PINNED_DOCKERFILE = f"FROM eclipse-temurin:24.0.2_12-jre@{DIGEST}\nUSER 10001\n"
PINNED_COMPOSE = f"""services:
  app:
    image: "${{MINOS_IMAGE}}"
  db:
    # comment: image: not-a-pin
    image: "${{MINOS_POSTGRES_IMAGE:-pgvector/pgvector:0.8.2-pg17@{DIGEST}}}"
"""


class CheckImagePinsTest(unittest.TestCase):

    def run_gate(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            failures, _ = gate.check(root)
            return failures

    def test_a_fully_pinned_tree_passes(self):
        self.assertEqual([], self.run_gate({
            "docker/Dockerfile.mcp": PINNED_DOCKERFILE,
            "docker/compose.mcp.connected.yaml": PINNED_COMPOSE,
        }))

    def test_digest_without_tag_is_refused_because_dependabot_would_move_it_to_latest(self):
        failures = self.run_gate({"docker/Dockerfile.mcp": f"FROM eclipse-temurin@{DIGEST}\n"})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Dockerfile.mcp:1", failures[0])

    def test_tag_without_digest_is_refused(self):
        failures = self.run_gate({"docker/Dockerfile.mcp": "FROM eclipse-temurin:24-jre\n"})
        self.assertEqual(1, len(failures), failures)

    def test_a_short_or_malformed_digest_is_refused(self):
        failures = self.run_gate({"docker/Dockerfile.mcp": "FROM rust:1.97.1@sha256:abc123\n"})
        self.assertEqual(1, len(failures), failures)

    def test_stage_references_and_scratch_are_not_registry_images(self):
        self.assertEqual([], self.run_gate({
            "docker/Dockerfile.mcp.release":
                f"FROM rust:1.97.1-bookworm@{DIGEST} AS rust-toolchain\nFROM scratch\n"
                "FROM rust-toolchain\nCOPY --from=rust-toolchain /a /b\n",
        }))

    def test_platform_flag_does_not_hide_an_unpinned_from(self):
        failures = self.run_gate({"docker/Dockerfile.mcp": "FROM --platform=linux/amd64 golang:1.26\n"})
        self.assertEqual(1, len(failures), failures)

    def test_build_arg_indirection_is_refused_because_the_pin_cannot_be_read(self):
        failures = self.run_gate({"docker/Dockerfile.mcp": "ARG BASE\nFROM ${BASE}\n"})
        self.assertEqual(1, len(failures), failures)

    def test_compose_default_without_digest_is_refused(self):
        failures = self.run_gate({
            "docker/compose.mcp.connected.yaml":
                'services:\n  o:\n    image: "${MINOS_OLLAMA_IMAGE:-ollama/ollama:0.32.0}"\n'})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("default must be pinned", failures[0])

    def test_literal_compose_image_without_digest_is_refused(self):
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": "services:\n  s:\n    image: redis:7\n"})
        self.assertEqual(1, len(failures), failures)

    def test_a_variable_without_default_other_than_the_minos_image_is_refused(self):
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": 'services:\n  s:\n    image: "${OTHER_IMAGE}"\n'})
        self.assertEqual(1, len(failures), failures)

    def test_the_minos_image_variable_is_the_only_unpinned_reference_allowed(self):
        self.assertEqual([], self.run_gate({"docker/compose.mcp.prod.yaml": 'services:\n  s:\n    image: "${MINOS_IMAGE}"\n'}))

    def test_a_second_copy_of_a_compose_pinned_service_image_is_refused(self):
        failures = self.run_gate({
            "docker/Dockerfile.mcp": PINNED_DOCKERFILE,
            "docker/compose.mcp.connected.yaml": PINNED_COMPOSE,
            "docker/scripts/configure.ps1": "[string] $PostgresImage = 'pgvector/pgvector:0.8.2-pg17',\n",
        })
        self.assertEqual(1, len(failures), failures)
        self.assertIn("configure.ps1:1", failures[0])
        self.assertIn("single source", failures[0])

    def test_prose_under_docs_may_quote_the_reference(self):
        self.assertEqual([], self.run_gate({
            "docker/Dockerfile.mcp": PINNED_DOCKERFILE,
            "docker/compose.mcp.connected.yaml": PINNED_COMPOSE,
            "docs/user/docker-runtime.md": "Image pgvector/pgvector:0.8.2-pg17 is managed.\n",
        }))

    def test_a_tree_without_docker_files_does_not_pass_silently(self):
        failures = self.run_gate({"README.md": "x\n"})
        self.assertEqual(1, len(failures), failures)

    def test_the_real_repository_is_pinned(self):
        failures, inspected = gate.check(gate.DEFAULT_ROOT)
        self.assertEqual([], failures)
        self.assertGreaterEqual(inspected, 4)


if __name__ == "__main__":
    unittest.main()

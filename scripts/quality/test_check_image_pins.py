#!/usr/bin/env python3
"""Self-test of check-image-pins.py: one witness per way a container image can escape its pin."""
from __future__ import annotations

import importlib.util
import re
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


PR_CI = """jobs:
  invariants:
    steps:
      - name: gate
        run: python scripts/quality/check-image-pins.py
      - name: self-test
        run: python scripts/quality/test_check_image_pins.py -v
"""
DEPENDABOT = """version: 2
updates:
  - package-ecosystem: "docker"
    directory: "/docker"
  - package-ecosystem: "docker-compose"
    directory: "/docker"
    target-branch: "develop"
"""
NO_STEPS_CI = """jobs:
  invariants:
    steps: []
"""


class CheckImagePinsTest(unittest.TestCase):

    def run_gate(self, files: dict[str, str], pr_ci: str = PR_CI, dependabot: str | None = DEPENDABOT) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = {".github/workflows/pr-ci.yml": pr_ci}
            if dependabot is not None:
                base[".github/dependabot.yml"] = dependabot
            for name, content in {**base, **files}.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            failures, _ = gate.check(root)
            return failures

    def test_a_fully_pinned_tree_passes(self):
        self.assertEqual([], self.run_gate({
            "docker/Dockerfile.mcp": PINNED_DOCKERFILE,
            "docker/compose-mcp.connected.yaml": PINNED_COMPOSE,
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
            "docker/compose-mcp.connected.yaml":
                'services:\n  o:\n    image: "${MINOS_OLLAMA_IMAGE:-ollama/ollama:0.32.0}"\n'})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("default must be pinned", failures[0])

    def test_literal_compose_image_without_digest_is_refused(self):
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": "services:\n  s:\n    image: redis:7\n"})
        self.assertEqual(1, len(failures), failures)

    def test_a_variable_without_default_other_than_the_minos_image_is_refused(self):
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": 'services:\n  s:\n    image: "${OTHER_IMAGE}"\n'})
        self.assertEqual(1, len(failures), failures)

    def test_the_minos_image_variable_is_the_only_unpinned_reference_allowed(self):
        self.assertEqual([], self.run_gate({"docker/compose-mcp.prod.yaml": 'services:\n  s:\n    image: "${MINOS_IMAGE}"\n'}))

    def test_a_required_variable_guard_keeps_the_same_rule(self):
        # MINOS-AUD-H20: ${VAR:?message} is still the launcher-provided variable, nothing more.
        self.assertEqual([], self.run_gate({"docker/compose-mcp.prod.yaml":
                                            'services:\n  s:\n    image: "${MINOS_IMAGE:?MINOS_IMAGE is required}"\n'}))
        failures = self.run_gate({"docker/compose-mcp.prod.yaml":
                                  'services:\n  s:\n    image: "${OTHER_IMAGE:?OTHER_IMAGE is required}"\n'})
        self.assertEqual(1, len(failures), failures)

    def test_a_second_copy_of_a_compose_pinned_service_image_is_refused(self):
        failures = self.run_gate({
            "docker/Dockerfile.mcp": PINNED_DOCKERFILE,
            "docker/compose-mcp.connected.yaml": PINNED_COMPOSE,
            "docker/scripts/configure.ps1": "[string] $PostgresImage = 'pgvector/pgvector:0.8.2-pg17',\n",
        })
        self.assertEqual(1, len(failures), failures)
        self.assertIn("configure.ps1:1", failures[0])
        self.assertIn("single source", failures[0])

    def test_prose_under_docs_may_quote_the_reference(self):
        self.assertEqual([], self.run_gate({
            "docker/Dockerfile.mcp": PINNED_DOCKERFILE,
            "docker/compose-mcp.connected.yaml": PINNED_COMPOSE,
            "docs/user/docker-runtime.md": "Image pgvector/pgvector:0.8.2-pg17 is managed.\n",
        }))

    def test_a_gate_that_pr_ci_does_not_run_is_refused(self):
        files = {"docker/Dockerfile.mcp": PINNED_DOCKERFILE}
        self.assertEqual(2, len(self.run_gate(files, pr_ci=NO_STEPS_CI)))
        self.assertEqual(2, len(self.run_gate(files, pr_ci=PR_CI.replace("run:", "# run:"))))

    def test_steps_that_live_in_another_job_do_not_count(self):
        other_job = PR_CI.replace("  invariants:", "  elsewhere:")
        failures = self.run_gate({"docker/Dockerfile.mcp": PINNED_DOCKERFILE}, pr_ci=other_job)
        self.assertTrue(any("job `invariants` not found" in f for f in failures), failures)
        two_jobs = PR_CI + "  invariants:\n    steps: []\n"
        failures = self.run_gate({"docker/Dockerfile.mcp": PINNED_DOCKERFILE}, pr_ci=PR_CI.replace("jobs:\n  invariants:", "jobs:\n  elsewhere:") + "  invariants:\n    steps: []\n")
        self.assertEqual(2, len([f for f in failures if "no step running" in f]), failures)

    def test_a_condition_on_the_step_or_on_the_job_is_refused(self):
        step_if = PR_CI.replace("run: python scripts/quality/check-image-pins.py\n", "run: python scripts/quality/check-image-pins.py\n        if: false\n")
        failures = self.run_gate({"docker/Dockerfile.mcp": PINNED_DOCKERFILE}, pr_ci=step_if)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("has an `if:` condition", failures[0])
        job_if = PR_CI.replace("  invariants:\n", "  invariants:\n    if: github.event_name == 'push'\n")
        failures = self.run_gate({"docker/Dockerfile.mcp": PINNED_DOCKERFILE}, pr_ci=job_if)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("job `invariants` has an `if:` condition", failures[0])

    def test_a_condition_on_a_neighbouring_step_or_job_is_not_confused_with_ours(self):
        neighbour = PR_CI + "  verify:\n    if: false\n    steps:\n      - name: x\n        if: false\n        run: echo\n"
        self.assertEqual([], self.run_gate({"docker/Dockerfile.mcp": PINNED_DOCKERFILE}, pr_ci=neighbour))

    # ---- Dependabot must be able to see the compose files (a pin nobody updates is a security regression) ----

    def test_a_compose_file_named_outside_dependabots_pattern_is_refused(self):
        # Two dotted segments: the name the compose files had before the rename, invisible to docker-compose.
        for name in ("compose.mcp.prod.yaml", "compose-mcp-prod.yaml", "compose.a.b.yml"):
            with self.subTest(name=name):
                failures = self.run_gate({f"docker/{name}": PINNED_COMPOSE})
                self.assertEqual(1, len(failures), failures)
                self.assertIn("FILENAME_REGEX", failures[0])

    def test_the_names_dependabot_reads_are_accepted(self):
        for name in ("compose-mcp.prod.yaml", "compose-mcp.connected.yaml", "compose.yaml", "compose.yml"):
            with self.subTest(name=name):
                self.assertEqual([], self.run_gate({f"docker/{name}": PINNED_COMPOSE}))

    def test_a_missing_or_misdirected_docker_compose_entry_is_refused(self):
        compose = {"docker/compose-mcp.connected.yaml": PINNED_COMPOSE}
        without_entry = DEPENDABOT.split('  - package-ecosystem: "docker-compose"')[0]
        entry = DEPENDABOT[len(without_entry):]
        wrong_directory = without_entry + entry.replace('directory: "/docker"', 'directory: "/"')
        commented = without_entry + "".join("  # " + line + "\n" for line in entry.splitlines())
        # `directory: "/docker"` is present, but it belongs to the maven entry that follows, not to docker-compose.
        directory_of_another_entry = (without_entry + entry.replace('directory: "/docker"', 'directory: "/elsewhere"')
                                      + '  - package-ecosystem: "maven"\n    directory: "/docker"\n')
        for label, config in (("no entry", without_entry), ("other directory", wrong_directory),
                              ("commented out", commented), ("directory belongs to another entry", directory_of_another_entry),
                              ("no file", None)):
            with self.subTest(case=label):
                failures = self.run_gate(compose, dependabot=config)
                self.assertEqual(1, len(failures), failures)
                self.assertIn("dependabot.yml", failures[0])

    def test_a_tree_without_docker_files_does_not_pass_silently(self):
        failures = self.run_gate({"README.md": "x\n"})
        self.assertEqual(1, len(failures), failures)

    # ---- simulated Dependabot diff on the runtime image (free, outside CI) ----
    DIGEST_B = "sha256:" + "b" * 64

    def temurin_patterns(self):
        """The two places that pin the runtime base image by pattern, loaded from their real sources."""
        spec = importlib.util.spec_from_file_location(
            "remediation_v2", gate.DEFAULT_ROOT / "scripts/remediation/check-audit-remediation-v2.py")
        remediation = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(remediation)
        ps1 = (gate.DEFAULT_ROOT / "docker/scripts/verify-run-configurations.ps1").read_text(encoding="utf-8")
        literal = re.search(r"-match '(\(\?m\)\^FROM eclipse-temurin[^']+)'", ps1)
        self.assertIsNotNone(literal, "verify-run-configurations.ps1 must pin eclipse-temurin by pattern")
        return [remediation.TEMURIN_JRE_FROM, re.compile(literal.group(1))]

    def test_a_dependabot_bump_to_another_24_jre_with_a_new_digest_stays_green(self):
        bumped = f"FROM eclipse-temurin:24.0.3_9-jre@{self.DIGEST_B}\nUSER 10001\n"
        self.assertEqual([], self.run_gate({"docker/Dockerfile.mcp": bumped}))
        for pattern in self.temurin_patterns():
            self.assertTrue(pattern.search(bumped), pattern.pattern)
            self.assertTrue(pattern.search(PINNED_DOCKERFILE), pattern.pattern)

    def test_what_a_dependabot_bump_must_never_produce_stays_red(self):
        red = {
            "floating tag": "FROM eclipse-temurin:24-jre@" + DIGEST + "\n",
            "major 25": f"FROM eclipse-temurin:25.0.1_8-jre@{self.DIGEST_B}\n",
            "tag without digest": "FROM eclipse-temurin:24.0.3_9-jre\n",
            "digest without tag": f"FROM eclipse-temurin@{self.DIGEST_B}\n",
            "jdk instead of jre": f"FROM eclipse-temurin:24.0.3_9-jdk@{self.DIGEST_B}\n",
            "short digest": "FROM eclipse-temurin:24.0.3_9-jre@sha256:abc\n",
        }
        for label, dockerfile in red.items():
            for pattern in self.temurin_patterns():
                with self.subTest(case=label, pattern=pattern.pattern[:30]):
                    self.assertFalse(pattern.search(dockerfile))
        # The generic gate refuses the shapes that Dependabot semantics make dangerous or mutable.
        for label in ("tag without digest", "digest without tag", "short digest"):
            with self.subTest(generic=label):
                self.assertNotEqual([], self.run_gate({"docker/Dockerfile.mcp": red[label]}))

    def test_the_real_repository_is_pinned(self):
        failures, inspected = gate.check(gate.DEFAULT_ROOT)
        self.assertEqual([], failures)
        self.assertGreaterEqual(inspected, 4)


if __name__ == "__main__":
    unittest.main()

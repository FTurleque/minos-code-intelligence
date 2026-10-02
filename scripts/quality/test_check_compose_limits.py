#!/usr/bin/env python3
"""Self-test of check-compose-limits.py: one witness per way a service can escape its ceiling."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-compose-limits.py"
_SPEC = importlib.util.spec_from_file_location("check_compose_limits", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

BLOCK = '''x-limits-query: &limits-query
  cpus: "${MINOS_MCP_CPUS:-4}"
  mem_limit: "${MINOS_MCP_MEM_LIMIT:-3g}"
  memswap_limit: "${MINOS_MCP_MEM_LIMIT:-3g}"
  pids_limit: "${MINOS_MCP_PIDS_LIMIT:-1024}"
'''
GOOD = f'''name: "x"

{BLOCK}
services:
  minos-mcp:
    <<: *limits-query
    image: "${{MINOS_IMAGE}}"
    read_only: true

volumes:
  data: {{}}
'''
DOC = ("| `MINOS_MCP_CPUS` = `4` | `MINOS_MCP_MEM_LIMIT` = `3g` |\n"
       "| `MINOS_MCP_PIDS_LIMIT` = `1024` |\n")


class CheckComposeLimitsTest(unittest.TestCase):

    def run_gate(self, files: dict[str, str], doc: str = DOC) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, content in {**files, "docs/user/docker-runtime.md": doc}.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            failures, _ = gate.check(root)
            return failures

    def test_a_service_with_its_ceilings_passes(self):
        self.assertEqual([], self.run_gate({"docker/compose.mcp.prod.yaml": GOOD}))

    def test_a_service_without_a_ceiling_is_refused(self):
        bad = GOOD.replace("    <<: *limits-query\n", "")
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": bad})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("minos-mcp has no `<<: *limits-<role>`", failures[0])

    def test_a_ceiling_spelled_inside_a_service_is_refused(self):
        bad = GOOD.replace("    read_only: true\n", "    read_only: true\n    pids_limit: 99\n")
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": bad})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("spells a ceiling itself", failures[0])

    def test_deploy_resources_are_refused_because_they_would_be_a_second_definition(self):
        bad = GOOD.replace("    read_only: true\n", "    read_only: true\n    deploy:\n")
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": bad})
        self.assertEqual(1, len(failures), failures)

    def test_an_undefined_block_is_refused(self):
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": GOOD.replace("*limits-query", "*limits-ghost")})
        self.assertTrue(any("not defined" in failure for failure in failures), failures)

    def test_swap_must_equal_memory(self):
        bad = GOOD.replace('memswap_limit: "${MINOS_MCP_MEM_LIMIT:-3g}"', 'memswap_limit: "${MINOS_MCP_MEM_LIMIT:-6g}"')
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": bad})
        self.assertTrue(any("memswap_limit must equal mem_limit" in failure for failure in failures), failures)

    def test_a_literal_value_instead_of_an_overridable_variable_is_refused(self):
        bad = GOOD.replace('pids_limit: "${MINOS_MCP_PIDS_LIMIT:-1024}"', "pids_limit: 1024")
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": bad})
        self.assertTrue(any("unreadable line" in failure for failure in failures), failures)

    def test_a_missing_key_is_refused(self):
        bad = GOOD.replace('  pids_limit: "${MINOS_MCP_PIDS_LIMIT:-1024}"\n', "")
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": bad})
        self.assertTrue(any("must define exactly" in failure for failure in failures), failures)

    def test_two_files_may_not_disagree(self):
        other = GOOD.replace("-3g}", "-4g}")
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": GOOD, "docker/compose.mcp.connected.yaml": other})
        self.assertTrue(any("differs between" in failure for failure in failures), failures)

    def test_a_shared_service_keeps_the_same_role_in_both_files(self):
        second_block = BLOCK.replace("query", "admin").replace("MCP", "ADMIN")
        other = GOOD.replace("*limits-query", "*limits-admin").replace(BLOCK, BLOCK + second_block)
        first = GOOD.replace(BLOCK, BLOCK + second_block)
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": first, "docker/compose.mcp.connected.yaml": other},
                                 DOC + "| `MINOS_ADMIN_CPUS` = `4` | `MINOS_ADMIN_MEM_LIMIT` = `3g` | `MINOS_ADMIN_PIDS_LIMIT` = `1024` |\n")
        self.assertTrue(any("has role" in failure and "minos-mcp" in failure for failure in failures), failures)

    def test_an_undocumented_variable_is_refused(self):
        failures = self.run_gate({"docker/compose.mcp.prod.yaml": GOOD}, doc="nothing here\n")
        self.assertEqual(3, len([f for f in failures if "no line documents" in f]), failures)

    def test_a_tree_without_compose_files_does_not_pass_silently(self):
        failures = self.run_gate({"README.md": "x\n"})
        self.assertTrue(any("would check nothing" in failure for failure in failures), failures)

    def test_the_real_repository_is_bounded(self):
        failures, inspected = gate.check(gate.DEFAULT_ROOT)
        self.assertEqual([], failures)
        self.assertEqual(2, inspected)


if __name__ == "__main__":
    unittest.main()

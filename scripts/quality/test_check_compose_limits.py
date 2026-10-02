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
  cpus: "${MINOS_MCP_CPUS:-0}"
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
DOC = ("| `MINOS_MCP_CPUS` = `0` | `MINOS_MCP_MEM_LIMIT` = `3g` |\n"
       "| `MINOS_MCP_PIDS_LIMIT` = `1024` |\n")
EXAMPLE = ("# MINOS_MCP_MEM_LIMIT=3g   # unit: bytes\n"
           "# MINOS_MCP_PIDS_LIMIT=1024   # unit: processes\n"
           "# MINOS_MCP_CPUS=0   # unit: CPUs\n")
PR_CI = ("jobs:\n  invariants:\n    steps:\n"
         "      - run: python scripts/quality/check-compose-limits.py\n"
         "      - run: python scripts/quality/test_check_compose_limits.py -v\n")


class CheckComposeLimitsTest(unittest.TestCase):

    def run_gate(self, files: dict[str, str], doc: str = DOC, example: str = EXAMPLE, pr_ci: str = PR_CI) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            defaults = {"docs/user/docker-runtime.md": doc, "docker/.env.example": example,
                        ".github/workflows/pr-ci.yml": pr_ci}
            for name, content in {**defaults, **files}.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
            failures, _ = gate.check(root)
            return failures

    def test_a_service_with_its_ceilings_passes(self):
        self.assertEqual([], self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}))

    # ---- memory and PID: strictly positive defaults ----
    def test_memory_defaults_that_mean_unlimited_or_nothing_are_refused(self):
        for bad in ("0", "-1", "0g", "0m", "", "3", "g", "-3g"):
            with self.subTest(mem_limit=bad):
                compose = GOOD.replace("MINOS_MCP_MEM_LIMIT:-3g", f"MINOS_MCP_MEM_LIMIT:-{bad}")
                failures = [f for f in self.run_gate({"docker/compose-mcp.prod.yaml": compose}) if "refused" in f]
                self.assertEqual(2, len(failures), failures)  # mem_limit and memswap_limit
                self.assertIn("real limit", failures[0])

    def test_a_valid_byte_count_is_accepted_and_one_below_dockers_minimum_is_refused(self):
        compose = GOOD.replace("MINOS_MCP_MEM_LIMIT:-3g", "MINOS_MCP_MEM_LIMIT:-536870912")
        example = EXAMPLE.replace("MINOS_MCP_MEM_LIMIT=3g", "MINOS_MCP_MEM_LIMIT=536870912")
        doc = DOC.replace("`MINOS_MCP_MEM_LIMIT` = `3g`", "`MINOS_MCP_MEM_LIMIT` = `536870912`")
        self.assertEqual([], self.run_gate({"docker/compose-mcp.prod.yaml": compose}, doc, example))
        tiny = GOOD.replace("MINOS_MCP_MEM_LIMIT:-3g", "MINOS_MCP_MEM_LIMIT:-4194304")
        failures = [f for f in self.run_gate({"docker/compose-mcp.prod.yaml": tiny}) if "refused" in f]
        self.assertEqual(2, len(failures), failures)
        self.assertIn("6291456", failures[0])

    def test_pid_defaults_that_mean_unlimited_or_nothing_are_refused(self):
        for bad in ("0", "-1", "", "1k", "1.5"):
            with self.subTest(pids_limit=bad):
                compose = GOOD.replace("MINOS_MCP_PIDS_LIMIT:-1024", f"MINOS_MCP_PIDS_LIMIT:-{bad}")
                failures = [f for f in self.run_gate({"docker/compose-mcp.prod.yaml": compose}) if "refused" in f]
                self.assertEqual(1, len(failures), failures)

    def test_a_literal_value_instead_of_an_overridable_variable_is_refused(self):
        bad = GOOD.replace('pids_limit: "${MINOS_MCP_PIDS_LIMIT:-1024}"', "pids_limit: 1024")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": bad})
        self.assertTrue(any("unreadable line" in failure for failure in failures), failures)

    # ---- CPU: no default above 1 ----
    def test_cpu_defaults_the_daemon_can_always_start_are_accepted(self):
        for good in ("0", "1", "1.0", "0.5", ".25"):
            with self.subTest(cpus=good):
                compose = GOOD.replace("MINOS_MCP_CPUS:-0", f"MINOS_MCP_CPUS:-{good}")
                example = EXAMPLE.replace("MINOS_MCP_CPUS=0", f"MINOS_MCP_CPUS={good}")
                doc = DOC.replace("`MINOS_MCP_CPUS` = `0`", f"`MINOS_MCP_CPUS` = `{good}`")
                self.assertEqual([], self.run_gate({"docker/compose-mcp.prod.yaml": compose}, doc, example))

    def test_an_absent_cpus_key_means_no_ceiling_and_is_accepted(self):
        compose = GOOD.replace('  cpus: "${MINOS_MCP_CPUS:-0}"\n', "")
        doc = DOC.replace("| `MINOS_MCP_CPUS` = `0` | ", "| ")
        example = EXAMPLE.replace("# MINOS_MCP_CPUS=0   # unit: CPUs\n", "")
        self.assertEqual([], self.run_gate({"docker/compose-mcp.prod.yaml": compose}, doc, example))

    def test_cpu_defaults_above_one_negative_or_empty_are_refused_with_the_reason(self):
        for bad in ("2", "4", "1.5", "16", "-1", "-0.5", ""):
            with self.subTest(cpus=bad):
                compose = GOOD.replace("MINOS_MCP_CPUS:-0", f"MINOS_MCP_CPUS:-{bad}")
                failures = [f for f in self.run_gate({"docker/compose-mcp.prod.yaml": compose}) if "cpus default" in f]
                self.assertEqual(1, len(failures), failures)
                self.assertIn("refuses to create a container whose cpus exceeds the host's CPU count", failures[0])
                self.assertIn("1 CPU starts everywhere", failures[0])

    # ---- structure ----
    def test_a_service_without_a_ceiling_is_refused(self):
        bad = GOOD.replace("    <<: *limits-query\n", "")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": bad})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("minos-mcp has no `<<: *limits-<role>`", failures[0])

    def test_a_ceiling_spelled_inside_a_service_is_refused(self):
        bad = GOOD.replace("    read_only: true\n", "    read_only: true\n    pids_limit: 99\n")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": bad})
        self.assertEqual(1, len(failures), failures)
        self.assertIn("spells a ceiling itself", failures[0])

    def test_deploy_resources_are_refused_because_they_would_be_a_second_definition(self):
        bad = GOOD.replace("    read_only: true\n", "    read_only: true\n    deploy:\n")
        self.assertEqual(1, len(self.run_gate({"docker/compose-mcp.prod.yaml": bad})))

    def test_an_undefined_block_is_refused(self):
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD.replace("*limits-query", "*limits-ghost")})
        self.assertTrue(any("not defined" in failure for failure in failures), failures)

    def test_swap_must_equal_memory(self):
        bad = GOOD.replace('memswap_limit: "${MINOS_MCP_MEM_LIMIT:-3g}"', 'memswap_limit: "${MINOS_MCP_MEM_LIMIT:-6g}"')
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": bad})
        self.assertTrue(any("memswap_limit must equal mem_limit" in failure for failure in failures), failures)

    def test_a_missing_required_key_is_refused(self):
        bad = GOOD.replace('  pids_limit: "${MINOS_MCP_PIDS_LIMIT:-1024}"\n', "")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": bad})
        self.assertTrue(any("must define" in failure for failure in failures), failures)

    # ---- two files ----
    def test_two_files_may_not_disagree(self):
        other = GOOD.replace("-3g}", "-4g}")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD, "docker/compose-mcp.connected.yaml": other})
        self.assertTrue(any("differs between" in failure for failure in failures), failures)

    def test_a_difference_of_comments_order_or_spacing_is_not_a_divergence(self):
        reordered = GOOD.replace('  cpus: "${MINOS_MCP_CPUS:-0}"\n', "  # comment\n").replace(
            '  pids_limit: "${MINOS_MCP_PIDS_LIMIT:-1024}"\n',
            '  pids_limit: "${MINOS_MCP_PIDS_LIMIT:-1024}"\n  cpus: "${MINOS_MCP_CPUS:-0}"\n')
        self.assertEqual([], self.run_gate(
            {"docker/compose-mcp.prod.yaml": GOOD, "docker/compose-mcp.connected.yaml": reordered}))

    def test_a_shared_service_keeps_the_same_role_in_both_files(self):
        second_block = BLOCK.replace("query", "admin").replace("MCP", "ADMIN")
        other = GOOD.replace("*limits-query", "*limits-admin").replace(BLOCK, BLOCK + second_block)
        first = GOOD.replace(BLOCK, BLOCK + second_block)
        more_doc = "| `MINOS_ADMIN_CPUS` = `0` | `MINOS_ADMIN_MEM_LIMIT` = `3g` | `MINOS_ADMIN_PIDS_LIMIT` = `1024` |\n"
        more_example = ("# MINOS_ADMIN_MEM_LIMIT=3g   # unit: bytes\n# MINOS_ADMIN_PIDS_LIMIT=1024   # unit: processes\n"
                        "# MINOS_ADMIN_CPUS=0   # unit: CPUs\n")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": first, "docker/compose-mcp.connected.yaml": other},
                                 DOC + more_doc, EXAMPLE + more_example)
        self.assertTrue(any("has role" in failure and "minos-mcp" in failure for failure in failures), failures)

    # ---- documentation, example, CI ----
    def test_an_undocumented_variable_is_refused(self):
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, doc="nothing here\n")
        self.assertEqual(3, len([f for f in failures if "no line documents" in f]), failures)

    def test_a_variable_missing_from_the_env_example_or_without_unit_is_refused(self):
        self.assertEqual(3, len(self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, example="")))
        no_unit = EXAMPLE.replace("   # unit: processes", "")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, example=no_unit)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("MINOS_MCP_PIDS_LIMIT", failures[0])

    def test_a_gate_that_pr_ci_does_not_run_is_refused(self):
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, pr_ci="jobs:\n  invariants:\n    steps: []\n")
        self.assertEqual(2, len(failures), failures)
        commented = PR_CI.replace("- run:", "# - run:")
        self.assertEqual(2, len(self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, pr_ci=commented)))

    def test_steps_that_live_in_another_job_do_not_count(self):
        other_job = PR_CI.replace("  invariants:", "  elsewhere:")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, pr_ci=other_job)
        self.assertTrue(any("job `invariants` not found" in f for f in failures), failures)
        two_jobs = PR_CI + "  invariants:\n    steps: []\n"
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, pr_ci=PR_CI.replace("jobs:\n  invariants:", "jobs:\n  elsewhere:") + "  invariants:\n    steps: []\n")
        self.assertEqual(2, len([f for f in failures if "no step running" in f]), failures)

    def test_a_condition_on_the_step_or_on_the_job_is_refused(self):
        step_if = PR_CI.replace("run: python scripts/quality/check-compose-limits.py\n", "run: python scripts/quality/check-compose-limits.py\n        if: false\n")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, pr_ci=step_if)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("has an `if:` condition", failures[0])
        job_if = PR_CI.replace("  invariants:\n", "  invariants:\n    if: github.event_name == 'push'\n")
        failures = self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, pr_ci=job_if)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("job `invariants` has an `if:` condition", failures[0])

    def test_a_condition_on_a_neighbouring_step_or_job_is_not_confused_with_ours(self):
        neighbour = PR_CI + "  verify:\n    if: false\n    steps:\n      - name: x\n        if: false\n        run: echo\n"
        self.assertEqual([], self.run_gate({"docker/compose-mcp.prod.yaml": GOOD}, pr_ci=neighbour))

    def test_a_tree_without_compose_files_does_not_pass_silently(self):
        failures = self.run_gate({"README.md": "x\n"})
        self.assertTrue(any("would check nothing" in failure for failure in failures), failures)

    def test_the_real_repository_is_bounded_and_wired(self):
        failures, inspected = gate.check(gate.DEFAULT_ROOT)
        self.assertEqual([], failures)
        self.assertEqual(2, inspected)


if __name__ == "__main__":
    unittest.main()

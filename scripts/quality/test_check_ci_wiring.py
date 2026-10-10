#!/usr/bin/env python3
"""Self-test of check-ci-wiring.py: one witness per way a required check or a gate self-test can come undone."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-ci-wiring.py"
_SPEC = importlib.util.spec_from_file_location("check_ci_wiring", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

PR_CI = """name: PR Validation
on:
  pull_request:
    branches: [main, develop]
  push:
    branches: [main, develop]
jobs:
  vulnerability-scan:
    name: Dependency vulnerability gate
    uses: example/scanner/.github/workflows/scan.yml@0123456789abcdef
  invariants:
    name: Static invariants (single run)
    runs-on: ubuntu-24.04
    steps:
      - name: Gate
        run: python scripts/quality/check-thing.py
      - name: Self-tests
        run: |
          python scripts/quality/test_check_thing.py -v
          python scripts/quality/check-selftested.py --self-test
  verify:
    name: Verify (${{ matrix.os }})
    strategy:
      matrix:
        os: [ubuntu-24.04, windows-2022]
    runs-on: ${{ matrix.os }}
    steps:
      - run: ./mvnw verify
"""

SECRET_SCAN = """name: Secret scan
on:
  pull_request:
    branches: [main, develop]
jobs:
  gitleaks:
    name: Gitleaks
    runs-on: ubuntu-24.04
    if: ${{ github.actor != 'nobody' }}
    steps:
      - run: echo scan
"""

PLUGIN = """name: Plugin
on:
  pull_request:
    branches: [main, develop]
jobs:
  gate:
    name: IntelliJ plugin (gate)
    if: ${{ always() }}
    runs-on: ubuntu-24.04
    steps:
      - run: echo gate
"""

MANIFEST = {
    "version": 1,
    "rulesets": {
        "Protect main & develop": {
            "branches": ["main", "develop"],
            "checks": [
                {"context": "Verify (ubuntu-24.04)", "source": "workflow", "workflow": "pr-ci.yml"},
                {"context": "Verify (windows-2022)", "source": "workflow", "workflow": "pr-ci.yml"},
                {"context": "Dependency vulnerability gate / scan", "source": "reusable", "workflow": "pr-ci.yml"},
                {"context": "Static invariants (single run)", "source": "workflow", "workflow": "pr-ci.yml"},
                {"context": "Gitleaks", "source": "workflow", "workflow": "secret-scan.yml"},
                {"context": "IntelliJ plugin (gate)", "source": "workflow", "workflow": "plugin.yml"},
                {"context": "SonarCloud Code Analysis", "source": "application"},
            ],
        },
    },
}


class WiringTestCase(unittest.TestCase):

    def tree(self, workflows: dict[str, str] | None = None, manifest: object = MANIFEST,
             scripts: dict[str, str] | None = None) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        files = {"pr-ci.yml": PR_CI, "secret-scan.yml": SECRET_SCAN, "plugin.yml": PLUGIN}
        files.update(workflows or {})
        for name, content in files.items():
            if content is not None:
                path = root / ".github" / "workflows" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
        if manifest is not None:
            (root / ".github" / "required-checks.json").write_text(json.dumps(manifest), encoding="utf-8")
        defaults = {
            "scripts/quality/check-thing.py": "print('gate')\n",
            "scripts/quality/test_check_thing.py": "print('test')\n",
            "scripts/quality/check-selftested.py": 'parser.add_argument("--self-test", action="store_true")\n',
            "scripts/history/m1/test_old.py": "print('archived')\n",
        }
        defaults.update(scripts or {})
        for name, content in defaults.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        return root

    def failures(self, root: Path) -> list[str]:
        return gate.check(root)[0]

    def with_manifest(self, *checks: dict) -> dict:
        return {"version": 1, "rulesets": {"R": {"branches": ["main", "develop"], "checks": list(checks)}}}


class CleanTreeTest(WiringTestCase):

    def test_a_consistent_tree_passes_and_counts_what_it_resolved(self):
        failures, resolved, wired = gate.check(self.tree())
        self.assertEqual([], failures)
        self.assertEqual(7, resolved)
        self.assertEqual(2, wired)


class ResolutionTest(WiringTestCase):

    def test_a_context_without_a_job_is_refused(self):
        manifest = self.with_manifest({"context": "Nothing", "source": "workflow", "workflow": "pr-ci.yml"})
        failures = self.failures(self.tree(manifest=manifest))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("'Nothing'", failures[0])
        self.assertIn("found 0", failures[0])

    def test_a_renamed_job_is_refused(self):
        renamed = PR_CI.replace("name: Static invariants (single run)", "name: Static checks")
        failures = self.failures(self.tree({"pr-ci.yml": renamed}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Static invariants (single run)", failures[0])

    def test_a_matrix_name_is_expanded_and_a_removed_value_is_refused(self):
        narrowed = PR_CI.replace("os: [ubuntu-24.04, windows-2022]", "os: [ubuntu-24.04]")
        failures = self.failures(self.tree({"pr-ci.yml": narrowed}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Verify (windows-2022)", failures[0])

    def test_a_missing_workflow_file_is_refused_not_ignored(self):
        manifest = self.with_manifest({"context": "Gitleaks", "source": "workflow", "workflow": "gone.yml"})
        failures = self.failures(self.tree(manifest=manifest))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("gone.yml", failures[0])

    def test_a_reusable_workflow_is_resolved_by_its_calling_job(self):
        manifest = self.with_manifest(
            {"context": "Dependency vulnerability gate / scan", "source": "reusable", "workflow": "pr-ci.yml"},
            {"context": "Another gate / scan", "source": "reusable", "workflow": "pr-ci.yml"})
        failures = self.failures(self.tree(manifest=manifest))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("Another gate / scan", failures[0])

    def test_a_job_level_condition_is_allowed(self):
        manifest = self.with_manifest({"context": "Gitleaks", "source": "workflow", "workflow": "secret-scan.yml"})
        self.assertEqual([], self.failures(self.tree(manifest=manifest)))

    def test_a_missing_or_unreadable_manifest_is_refused(self):
        self.assertEqual(1, len(self.failures(self.tree(manifest=None))))
        root = self.tree()
        (root / ".github" / "required-checks.json").write_text("{not json", encoding="utf-8")
        self.assertEqual(1, len(self.failures(root)))


class ReachabilityTest(WiringTestCase):

    def test_a_workflow_filtered_by_paths_is_refused(self):
        filtered = PLUGIN.replace("    branches: [main, develop]\n",
                                  "    branches: [main, develop]\n    paths:\n      - 'minos-intellij/**'\n")
        failures = self.failures(self.tree({"plugin.yml": filtered}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("plugin.yml", failures[0])
        self.assertIn("paths", failures[0])

    def test_paths_ignore_is_refused_too(self):
        filtered = PLUGIN.replace("    branches: [main, develop]\n",
                                  "    branches: [main, develop]\n    paths-ignore:\n      - 'docs/**'\n")
        self.assertEqual(1, len(self.failures(self.tree({"plugin.yml": filtered}))))

    def test_a_filter_on_the_push_trigger_only_is_not_a_problem(self):
        text = PLUGIN + ""
        text = text.replace("jobs:", "  push:\n    branches: [main]\n    paths:\n      - 'x/**'\njobs:", 1)
        self.assertEqual([], self.failures(self.tree({"plugin.yml": text})))

    def test_a_workflow_that_does_not_start_on_pull_request_is_refused(self):
        push_only = PLUGIN.replace("  pull_request:", "  push:")
        failures = self.failures(self.tree({"plugin.yml": push_only}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("does not start on pull_request", failures[0])

    def test_a_workflow_that_misses_a_protected_branch_is_refused(self):
        main_only = PLUGIN.replace("branches: [main, develop]", "branches: [main]")
        failures = self.failures(self.tree({"plugin.yml": main_only}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("develop", failures[0])

    def test_a_branch_list_written_as_block_items_is_read(self):
        block = PLUGIN.replace("    branches: [main, develop]\n", "    branches:\n      - main\n      - develop\n")
        self.assertEqual([], self.failures(self.tree({"plugin.yml": block})))


class SelfTestWiringTest(WiringTestCase):

    def test_a_test_script_that_no_invariants_step_runs_is_refused(self):
        root = self.tree(scripts={"scripts/quality/test_check_other.py": "print('x')\n"})
        failures = self.failures(root)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("test_check_other.py", failures[0])

    def test_a_test_run_by_another_job_does_not_count(self):
        elsewhere = PR_CI.replace("python scripts/quality/test_check_thing.py -v\n          ", "") \
            + "      - run: python scripts/quality/test_check_thing.py -v\n"
        failures = self.failures(self.tree({"pr-ci.yml": elsewhere}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("test_check_thing.py", failures[0])

    def test_a_script_with_a_self_test_option_must_be_called_with_it(self):
        without_option = PR_CI.replace("check-selftested.py --self-test", "check-selftested.py")
        failures = self.failures(self.tree({"pr-ci.yml": without_option}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("check-selftested.py --self-test", failures[0])

    def test_archived_tests_are_not_required(self):
        self.assertEqual([], self.failures(self.tree()))

    def test_a_pr_ci_without_an_invariants_job_is_refused(self):
        renamed = PR_CI.replace("  invariants:", "  fast-checks:")
        failures = self.failures(self.tree({"pr-ci.yml": renamed}))
        self.assertTrue(any("no 'invariants' job" in failure for failure in failures), failures)

    def test_a_commented_step_does_not_count(self):
        commented = PR_CI.replace("          python scripts/quality/test_check_thing.py -v\n",
                                  "          # python scripts/quality/test_check_thing.py -v\n")
        failures = self.failures(self.tree({"pr-ci.yml": commented}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("test_check_thing.py", failures[0])


class RulesetReadingTest(WiringTestCase):

    def test_reading_the_ruleset_in_a_workflow_without_a_recorded_payload_is_refused(self):
        leak = PR_CI + "      - run: python scripts/quality/verify-ruleset.py\n"
        failures = self.failures(self.tree({"pr-ci.yml": leak}))
        self.assertEqual(1, len(failures), failures)
        self.assertIn("verify-ruleset.py", failures[0])

    def test_replaying_a_recorded_payload_is_allowed(self):
        replay = PR_CI + "      - run: python scripts/quality/verify-ruleset.py --from-json payload.json\n"
        self.assertEqual([], self.failures(self.tree({"pr-ci.yml": replay})))


if __name__ == "__main__":
    unittest.main()

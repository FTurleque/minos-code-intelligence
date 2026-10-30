#!/usr/bin/env python3
"""Self-test of check-ci-wiring.py: one witness per way a required check or a gate self-test can come undone."""
from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-ci-wiring.py"
_SPEC = importlib.util.spec_from_file_location("check_ci_wiring", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

REPOSITORY = Path(__file__).resolve().parents[2]

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
        failures, resolved, wired, lockfiles = gate.check(self.tree())
        self.assertEqual([], failures)
        self.assertEqual(7, resolved)
        self.assertEqual(2, wired)
        self.assertEqual(0, lockfiles)


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


RESOURCES = "tool/src/main/resources/com/example/runtime"
SCANNED = PR_CI.replace(
    "    uses: example/scanner/.github/workflows/scan.yml@0123456789abcdef\n",
    "    uses: example/scanner/.github/workflows/scan.yml@0123456789abcdef\n"
    "    with:\n"
    "      scan-args: |-\n"
    "        --recursive\n"
    f"        --lockfile=package-lock.json:{RESOURCES}/scip-one-package-lock.json\n"
    "        ./\n"
    "      fail-on-vuln: true\n")


class ShippedLockfileTest(WiringTestCase):
    """AUD-DEP-03: the scanner recognises an npm lockfile by its name, so a prefixed one must be passed explicitly."""

    def lock(self, root: Path, relative: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('{"lockfileVersion": 3, "packages": {}}\n', encoding="utf-8")

    def test_a_shipped_lockfile_passed_to_the_scan_is_accepted_and_counted(self):
        root = self.tree({"pr-ci.yml": SCANNED})
        self.lock(root, f"{RESOURCES}/scip-one-package-lock.json")
        failures, _resolved, _wired, lockfiles = gate.check(root)
        self.assertEqual([], failures)
        self.assertEqual(1, lockfiles)

    def test_a_shipped_lockfile_missing_from_the_scan_arguments_is_refused_by_name(self):
        root = self.tree({"pr-ci.yml": SCANNED})
        self.lock(root, f"{RESOURCES}/scip-one-package-lock.json")
        self.lock(root, f"{RESOURCES}/scip-other-package-lock.json")
        failures = self.failures(root)
        self.assertEqual(1, len(failures), failures)
        self.assertIn(f"{RESOURCES}/scip-other-package-lock.json", failures[0])
        self.assertIn("--lockfile=package-lock.json:", failures[0])

    def test_a_shipped_lockfile_is_refused_when_no_job_has_scan_arguments(self):
        root = self.tree()
        self.lock(root, f"{RESOURCES}/scip-one-package-lock.json")
        failures = self.failures(root)
        self.assertEqual(1, len(failures), failures)
        self.assertIn(f"{RESOURCES}/scip-one-package-lock.json", failures[0])

    def test_a_commented_lockfile_argument_does_not_count(self):
        commented = SCANNED.replace("        --lockfile=", "        # --lockfile=")
        root = self.tree({"pr-ci.yml": commented})
        self.lock(root, f"{RESOURCES}/scip-one-package-lock.json")
        self.assertEqual(1, len(self.failures(root)))

    def test_a_lockfile_argument_outside_the_scan_arguments_does_not_count(self):
        moved = PR_CI + f"      - run: echo --lockfile=package-lock.json:{RESOURCES}/scip-one-package-lock.json\n"
        root = self.tree({"pr-ci.yml": moved})
        self.lock(root, f"{RESOURCES}/scip-one-package-lock.json")
        self.assertEqual(1, len(self.failures(root)))

    def test_lockfiles_of_fixtures_tests_and_build_output_are_ignored(self):
        root = self.tree({"pr-ci.yml": SCANNED})
        self.lock(root, f"{RESOURCES}/scip-one-package-lock.json")
        for ignored in ("fixtures/typescript/simple/package-lock.json",
                        "tool/src/test/resources/com/example/scip-test-package-lock.json",
                        "tool/target/classes/com/example/runtime/scip-one-package-lock.json",
                        "fixtures/polyglot/app/src/main/resources/package-lock.json",
                        ".claude/worktrees/other/tool/src/main/resources/scip-elsewhere-package-lock.json"):
            self.lock(root, ignored)
        failures, _resolved, _wired, lockfiles = gate.check(root)
        self.assertEqual([], failures)
        self.assertEqual(1, lockfiles)

    def test_a_scan_argument_naming_a_lockfile_that_does_not_exist_is_refused(self):
        root = self.tree({"pr-ci.yml": SCANNED})
        failures = self.failures(root)
        self.assertEqual(1, len(failures), failures)
        self.assertIn("scip-one-package-lock.json", failures[0])
        self.assertIn("not a file", failures[0])

    def test_the_repository_passes_its_shipped_lockfiles_and_a_copy_with_a_third_one_is_refused(self):
        failures, _resolved, _wired, lockfiles = gate.check(REPOSITORY)
        self.assertEqual([], failures)
        self.assertGreaterEqual(lockfiles, 2)
        root = self.tree()
        shutil.copyfile(REPOSITORY / ".github" / "workflows" / "pr-ci.yml", root / ".github" / "workflows" / "pr-ci.yml")
        shipped = gate.shipped_npm_lockfiles(REPOSITORY)
        for relative in shipped:
            self.lock(root, relative)
        self.assertEqual([], [failure for failure in self.failures(root) if "lockfile" in failure])
        added = Path(shipped[0]).parent.as_posix() + "/scip-autre-package-lock.json"
        self.lock(root, added)
        refused = [failure for failure in self.failures(root) if "lockfile" in failure]
        self.assertEqual(1, len(refused), refused)
        self.assertIn(added, refused[0])


RELEASE = """name: Release
on:
  workflow_dispatch:
jobs:
  publish:
    runs-on: windows-2022
    steps:
      - run: .\\\\scripts\\\\release\\\\publish-windows-release.ps1 -Version 1.0.0 -PublishOnly
  plugin:
    needs: publish
    permissions:
      contents: write
    uses: ./.github/workflows/intellij-plugin-release.yml
    with:
      tag: v1.0.0
"""

PLUGIN_RELEASE = """name: Publish IntelliJ Plugin
on:
  workflow_call:
    inputs:
      tag:
        type: string
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - run: echo build
"""


class ReleasePluginTest(WiringTestCase):

    def release_failures(self, workflows: dict[str, str | None]) -> list[str]:
        return gate.check_releases_publish_the_plugin(self.tree(workflows))

    def test_a_release_that_calls_the_plugin_workflow_is_accepted(self):
        self.assertEqual([], self.release_failures({"release.yml": RELEASE, "intellij-plugin-release.yml": PLUGIN_RELEASE}))
        self.assertEqual([], self.failures(self.tree({"release.yml": RELEASE,
                                                      "intellij-plugin-release.yml": PLUGIN_RELEASE})))

    def test_a_release_without_the_plugin_call_is_refused(self):
        release = RELEASE.split("  plugin:\n")[0]
        self.assertEqual([".github/workflows/release.yml: publishes a GitHub Release but no job calls "
                          "./.github/workflows/intellij-plugin-release.yml: the release would ship without the "
                          "IntelliJ plugin"],
                         self.release_failures({"release.yml": release, "intellij-plugin-release.yml": PLUGIN_RELEASE}))

    def test_a_release_created_by_the_cli_counts_as_a_publication(self):
        release = RELEASE.split("  plugin:\n")[0].replace(
            ".\\\\scripts\\\\release\\\\publish-windows-release.ps1 -Version 1.0.0 -PublishOnly", "gh release create v1.0.0")
        failures = self.release_failures({"release.yml": release, "intellij-plugin-release.yml": PLUGIN_RELEASE})
        self.assertEqual(1, len(failures))
        self.assertIn("publishes a GitHub Release but no job calls", failures[0])

    def test_a_validation_only_run_and_a_commented_call_do_not_publish(self):
        validation = RELEASE.split("  plugin:\n")[0].replace("-PublishOnly", "-SkipBuild -ValidateOnly")
        commented = RELEASE.split("  plugin:\n")[0].replace("      - run:", "      # gh release create v1\n      - run:").replace(
            "-PublishOnly", "-ValidateOnly")
        self.assertEqual([], self.release_failures({"a.yml": validation, "b.yml": commented}))

    def test_a_plugin_call_written_in_a_comment_does_not_count(self):
        release = RELEASE.replace("    uses: ./.github", "    # uses: ./.github")
        failures = self.release_failures({"release.yml": release, "intellij-plugin-release.yml": PLUGIN_RELEASE})
        self.assertEqual(1, len(failures))
        self.assertIn("no job calls", failures[0])

    def test_a_missing_plugin_workflow_is_refused_when_a_release_is_published(self):
        self.assertEqual([".github/workflows/intellij-plugin-release.yml: missing, no workflow can attach the IntelliJ "
                          "plugin to a release"], self.release_failures({"release.yml": RELEASE}))
        self.assertEqual([], self.release_failures({}))

    def test_a_plugin_workflow_started_by_the_release_event_only_is_refused(self):
        plugin = PLUGIN_RELEASE.replace(
            "  workflow_call:\n    inputs:\n      tag:\n        type: string\n  workflow_dispatch:\n",
            "  release:\n    types: [published]\n")
        failures = self.release_failures({"release.yml": RELEASE, "intellij-plugin-release.yml": plugin})
        self.assertEqual([".github/workflows/intellij-plugin-release.yml: does not start on workflow_call, the release "
                          "workflow cannot call it",
                          ".github/workflows/intellij-plugin-release.yml: still starts on a release: event, which "
                          "would publish the plugin a second time beside the call of the release workflow"], failures)

    def test_a_release_trigger_kept_beside_the_call_is_refused(self):
        plugin = PLUGIN_RELEASE.replace("  workflow_dispatch:\n", "  workflow_dispatch:\n  release:\n    types: [published]\n")
        failures = self.release_failures({"intellij-plugin-release.yml": plugin})
        self.assertEqual(1, len(failures))
        self.assertIn("still starts on a release: event", failures[0])

    def test_an_inline_trigger_list_is_read(self):
        plugin = PLUGIN_RELEASE.replace(
            "on:\n  workflow_call:\n    inputs:\n      tag:\n        type: string\n  workflow_dispatch:\n",
            "on: [release, workflow_dispatch]\n")
        failures = self.release_failures({"intellij-plugin-release.yml": plugin})
        self.assertEqual(2, len(failures))

    def test_the_repository_publishes_the_plugin_with_its_release(self):
        self.assertEqual([], gate.check_releases_publish_the_plugin(REPOSITORY))


if __name__ == "__main__":
    unittest.main()

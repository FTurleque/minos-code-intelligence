#!/usr/bin/env python3
"""Self-test of check-workflow-pins.py: for each rule of the gate, one witness refused and one accepted,
on temporary trees and on mutated copies of the real workflows."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import shutil
import tempfile
import unittest
from unittest import mock
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-workflow-pins.py"
_SPEC = importlib.util.spec_from_file_location("check_workflow_pins", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)

REPOSITORY = Path(__file__).resolve().parents[2]
SHA = "3d3c42e5aac5ba805825da76410c181273ba90b1"
RELEASE_SCRIPTS = ("scripts/release/build-windows-installer.ps1", "scripts/release/iscc-provenance.ps1",
                   "scripts/release/test-iscc-provenance.ps1")

COVERED_PATHS = ("      - 'scripts/quality/check-workflow-pins.py'\n"
                 "      - '.github/workflows/release-windows.yml'\n")
WINDOWS_INSTALLER = ("name: Windows Installer Validation\n\non:\n"
                     f"  pull_request:\n    branches: [main, develop]\n    paths:\n{COVERED_PATHS}"
                     f"  push:\n    branches: [main, develop]\n    paths:\n{COVERED_PATHS}"
                     "\njobs:\n  qualify:\n    runs-on: windows-2022\n    timeout-minutes: 75\n    steps:\n"
                     f"      - uses: actions/checkout@{SHA} # v7\n        with:\n          persist-credentials: false\n")
INSTALLER_SCRIPT = ("param([string]$IsccPath, [string]$RequiredIsccVersion)\n"
                    "if ([string]::IsNullOrWhiteSpace($IsccPath) -ne [string]::IsNullOrWhiteSpace($RequiredIsccVersion)) {\n"
                    "  throw 'IsccPath and RequiredIsccVersion go together.'\n}\n"
                    "if ([string]::IsNullOrWhiteSpace($IsccPath)) {\n  $IsccCandidates = @('C:/Inno/ISCC.exe')\n}\n"
                    "Assert-IsccEngineVersion -Log $log -Expected $RequiredIsccVersion\n")

INNO_STEP = ("jobs:\n  build:\n    runs-on: windows-2022\n    steps:\n"
             "      - name: Install Inno Setup\n        shell: pwsh\n        run: |\n"
             "          choco install innosetup --version=6.7.1 --yes\n"
             "          $path = 'C:/Program Files (x86)/Inno Setup 6/ISCC.exe'\n"
             "          \"ISCC_PATH=$path\" >> $env:GITHUB_ENV\n"
             "      - name: Build\n        shell: pwsh\n"
             "        run: ./scripts/release/publish-windows-release.ps1 -IsccPath $env:ISCC_PATH "
             "-RequiredIsccVersion 6.7.1\n")


class TreeTest(unittest.TestCase):
    """A temporary tree in which the whole gate passes; each test mutates one place."""

    # The closed list of Docker Hub logins seen by the test: none in a temporary tree, the real one for the repository.
    docker_login_kept: dict | None = {}

    def setUp(self):
        if self.docker_login_kept is not None:
            patcher = mock.patch.object(gate, "DOCKER_LOGIN_KEPT", self.docker_login_kept)
            patcher.start()
            self.addCleanup(patcher.stop)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write(f"{gate.WORKFLOWS}/windows-installer.yml", WINDOWS_INSTALLER)
        self.write(RELEASE_SCRIPTS[0], INSTALLER_SCRIPT)
        self.write(RELEASE_SCRIPTS[1], "function Assert-IsccEngineVersion {}\n")
        self.write(RELEASE_SCRIPTS[2], "# tests\n")

    def write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def failures(self) -> list[str]:
        return gate.check(self.root)[0]


class PinRulesTest(unittest.TestCase):

    def test_an_action_pinned_by_commit_sha_is_accepted_and_counted(self):
        failures, checked = gate.find_pin_violations("x.yml", f"    steps:\n      - uses: actions/checkout@{SHA} # v7\n")
        self.assertEqual([], failures)
        self.assertEqual(1, checked)

    def test_an_action_on_a_mutable_tag_is_refused(self):
        failures, checked = gate.find_pin_violations("x.yml", "    steps:\n      - uses: actions/checkout@v7\n")
        self.assertEqual(["x.yml:2: mutable uses ref actions/checkout@v7"], failures)
        self.assertEqual(1, checked)

    def test_a_local_action_is_neither_checked_nor_counted(self):
        self.assertEqual(([], 0), gate.find_pin_violations("x.yml", "      - uses: ./.github/actions/setup@main\n"))

    def test_an_inno_setup_pinned_to_the_qualified_version_is_accepted(self):
        text = "        run: choco install innosetup --version=6.7.1 --yes\n"
        self.assertEqual(([], 0), gate.find_pin_violations("x.yml", text))

    def test_an_unpinned_inno_setup_is_refused(self):
        failures, _checked = gate.find_pin_violations("x.yml", "        run: choco install innosetup --yes\n")
        self.assertEqual([f"x.yml:1: Inno Setup must be pinned to {gate.INNO_VERSION}"], failures)

    def test_an_inno_setup_version_read_from_a_pinned_env_is_accepted(self):
        text = ("env:\n  INNO_SETUP_VERSION: '6.7.1'\n"
                "        run: choco install innosetup --version=$env:INNO_SETUP_VERSION --yes\n")
        self.assertEqual(([], 0), gate.find_pin_violations("x.yml", text))

    def test_an_inno_setup_version_read_from_an_env_with_another_version_is_refused(self):
        text = ("env:\n  INNO_SETUP_VERSION: '6.2.0'\n"
                "        run: choco install innosetup --version=$env:INNO_SETUP_VERSION --yes\n")
        failures, _checked = gate.find_pin_violations("x.yml", text)
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith("x.yml:3: 'choco install innosetup' reads $env:INNO_SETUP_VERSION"))


class RunBlockTest(unittest.TestCase):

    def test_an_expression_inside_a_run_block_is_refused(self):
        text = "      - run: |\n          echo start\n          echo ${{ inputs.version }}\n"
        failures = gate.find_run_block_violations("x.yml", text)
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith("x.yml:3: '${{ ... }}' interpolated directly inside a run: block"))

    def test_an_expression_inside_an_inline_run_is_refused(self):
        failures = gate.find_run_block_violations("x.yml", "      - run: echo ${{ github.head_ref }}\n")
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith("x.yml:1: "))

    def test_an_expression_routed_through_env_is_accepted(self):
        text = ("      - env:\n          VERSION: ${{ inputs.version }}\n        run: |\n"
                "          echo \"$VERSION\"\n          # a comment may name ${{ inputs.version }}\n"
                "      - uses: actions/checkout@abc\n        with:\n          ref: ${{ github.sha }}\n")
        self.assertEqual([], gate.find_run_block_violations("x.yml", text))

    def test_a_closed_enum_step_outcome_is_accepted(self):
        text = "      - run: echo ${{ steps.build.outcome }} ${{ steps.build.conclusion }}\n"
        self.assertEqual([], gate.find_run_block_violations("x.yml", text))


class InnoProvenanceTest(unittest.TestCase):

    def test_a_workflow_without_inno_setup_is_not_concerned(self):
        self.assertEqual([], gate.find_inno_setup_provenance_violations("x.yml", "jobs: {}\n"))

    def test_a_named_install_step_that_exports_and_forwards_iscc_path_is_accepted(self):
        self.assertEqual([], gate.find_inno_setup_provenance_violations("x.yml", INNO_STEP))

    def test_choco_install_outside_a_named_inno_setup_step_is_refused(self):
        text = INNO_STEP.replace("- name: Install Inno Setup", "- name: Install compiler")
        self.assertEqual(["x.yml: 'choco install innosetup' found outside an identifiable Inno Setup install step "
                          "(expected a step name containing 'Inno Setup'); cannot verify compiler provenance."],
                         gate.find_inno_setup_provenance_violations("x.yml", text))

    def test_an_ambiguous_iscc_search_is_refused(self):
        text = INNO_STEP.replace("          $path = 'C:/Program Files (x86)/Inno Setup 6/ISCC.exe'\n",
                                 "          $path = (Get-Command ISCC.exe).Source\n")
        failures = gate.find_inno_setup_provenance_violations("x.yml", text)
        self.assertEqual(1, len(failures))
        self.assertIn("('Get-Command ISCC.exe')", failures[0])

    def test_an_install_step_that_does_not_export_iscc_path_is_refused(self):
        text = INNO_STEP.replace("          \"ISCC_PATH=$path\" >> $env:GITHUB_ENV\n", "")
        failures = gate.find_inno_setup_provenance_violations("x.yml", text)
        self.assertEqual(1, len(failures))
        self.assertIn("does not export a resolved ISCC_PATH", failures[0])

    def test_a_workflow_that_never_forwards_iscc_path_is_refused(self):
        text = INNO_STEP.replace(" -IsccPath $env:ISCC_PATH", "")
        failures = gate.find_inno_setup_provenance_violations("x.yml", text)
        self.assertEqual(1, len(failures))
        self.assertIn("no step forwards -IsccPath", failures[0])


class IsccPropagationTest(unittest.TestCase):

    def test_a_setup_building_invocation_with_both_flags_is_accepted(self):
        self.assertEqual([], gate.find_iscc_propagation_violations("x.yml", INNO_STEP))

    def test_a_setup_building_invocation_without_the_qualified_compiler_is_refused(self):
        text = INNO_STEP.replace(" -IsccPath $env:ISCC_PATH -RequiredIsccVersion 6.7.1", " -ValidateOnly")
        failures = gate.find_iscc_propagation_violations("x.yml", text)
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith("x.yml:13: publish-windows-release.ps1 can compile a setup here but "
                                               "the invocation does not pass -IsccPath, -RequiredIsccVersion"))

    def test_a_publish_only_invocation_is_exempt(self):
        text = INNO_STEP.replace(" -IsccPath $env:ISCC_PATH -RequiredIsccVersion 6.7.1", " -PublishOnly")
        self.assertEqual([], gate.find_iscc_propagation_violations("x.yml", text))

    def test_a_workflow_that_does_not_install_inno_setup_is_not_concerned(self):
        text = "        run: ./scripts/release/build-local-windows-candidate.ps1\n"
        self.assertEqual([], gate.find_iscc_propagation_violations("x.yml", text))


class WindowsQualificationCoverageTest(TreeTest):

    def coverage(self) -> list[str]:
        return gate.find_windows_qualification_coverage_violations(self.root)

    def test_filters_that_cover_the_release_workflow_and_the_gate_are_accepted(self):
        self.assertEqual([], self.coverage())

    def test_a_missing_windows_qualification_workflow_is_refused(self):
        (self.root / gate.WORKFLOWS / "windows-installer.yml").unlink()
        self.assertEqual([".github/workflows/windows-installer.yml: Windows qualification workflow not found"],
                         self.coverage())

    def test_a_trigger_without_paths_filter_is_refused(self):
        self.write(f"{gate.WORKFLOWS}/windows-installer.yml",
                   WINDOWS_INSTALLER.replace(f"  push:\n    branches: [main, develop]\n    paths:\n{COVERED_PATHS}",
                                             "  push:\n    branches: [main, develop]\n"))
        self.assertEqual([".github/workflows/windows-installer.yml: no 'push' paths filter found; cannot verify that "
                          "Windows qualification covers the release workflow."], self.coverage())

    def test_filters_that_no_longer_cover_the_release_workflow_are_refused(self):
        self.write(f"{gate.WORKFLOWS}/windows-installer.yml",
                   WINDOWS_INSTALLER.replace("      - '.github/workflows/release-windows.yml'\n", ""))
        self.assertEqual([f".github/workflows/windows-installer.yml: '{trigger}' paths filter does not include "
                          "'.github/workflows/release-windows.yml'; changing it would skip Windows qualification."
                          for trigger in ("pull_request", "push")], self.coverage())

    def test_pull_request_and_push_filters_that_differ_are_refused(self):
        self.write(f"{gate.WORKFLOWS}/windows-installer.yml",
                   WINDOWS_INSTALLER.replace("  push:\n    branches: [main, develop]\n    paths:\n",
                                             "  push:\n    branches: [main, develop]\n    paths:\n      - 'docker/**'\n"))
        self.assertEqual([".github/workflows/windows-installer.yml: pull_request and push paths filters differ; a "
                          "change qualified on a PR must be re-qualified on the branch it lands on."], self.coverage())


class InstallerScriptTest(TreeTest):

    def script_failures(self) -> list[str]:
        return gate.check_build_windows_installer_script(self.root)

    def test_a_script_that_honours_and_proves_the_qualified_compiler_is_accepted(self):
        self.assertEqual([], self.script_failures())

    def test_a_missing_installer_script_is_refused(self):
        (self.root / RELEASE_SCRIPTS[0]).unlink()
        self.assertEqual([f"{RELEASE_SCRIPTS[0]}: script not found"], self.script_failures())

    def test_a_script_without_an_iscc_path_parameter_is_refused(self):
        self.write(RELEASE_SCRIPTS[0], "param([string]$Version)\n")
        failures = self.script_failures()
        self.assertEqual(1, len(failures))
        self.assertIn("does not accept an -IsccPath parameter", failures[0])

    def test_a_script_that_never_branches_on_iscc_path_is_refused(self):
        self.write(RELEASE_SCRIPTS[0], "param([string]$IsccPath)\nAssert-IsccEngineVersion\n")
        failures = self.script_failures()
        self.assertTrue(any("does not branch on whether -IsccPath was supplied" in failure for failure in failures))
        self.assertTrue(any("does not reject a half-qualified invocation" in failure for failure in failures))

    def test_an_ambiguous_search_before_the_guard_is_refused(self):
        self.write(RELEASE_SCRIPTS[0], "$IsccCandidates = @('a')\n" + INSTALLER_SCRIPT)
        failures = self.script_failures()
        self.assertEqual(1, len(failures))
        self.assertIn("ambiguous ISCC candidate search appears before the -IsccPath guard", failures[0])

    def test_a_script_that_does_not_assert_the_engine_version_is_refused(self):
        self.write(RELEASE_SCRIPTS[0], INSTALLER_SCRIPT.replace("Assert-IsccEngineVersion", "Write-Host"))
        failures = self.script_failures()
        self.assertEqual(1, len(failures))
        self.assertIn("does not assert the engine version", failures[0])

    def test_a_script_that_accepts_a_half_qualified_invocation_is_refused(self):
        self.write(RELEASE_SCRIPTS[0], INSTALLER_SCRIPT.replace(
            " -ne [string]::IsNullOrWhiteSpace($RequiredIsccVersion)", ""))
        failures = self.script_failures()
        self.assertEqual(1, len(failures))
        self.assertIn("does not reject a half-qualified invocation", failures[0])

    def test_a_missing_provenance_helper_is_refused(self):
        (self.root / RELEASE_SCRIPTS[1]).unlink()
        self.assertEqual([f"{RELEASE_SCRIPTS[1]}: required Inno Setup provenance file not found"], self.script_failures())


class FloatingRunnerTest(TreeTest):

    def runners(self, text: str) -> tuple[list[str], int]:
        return gate.find_floating_runner_violations("x.yml", text)

    def test_a_versioned_runner_image_is_accepted_and_counted(self):
        self.assertEqual(([], 2), self.runners("jobs:\n  a:\n    runs-on: ubuntu-24.04\n  b:\n    runs-on: 'windows-2022'\n"))

    def test_a_floating_runner_image_is_refused(self):
        failures, count = self.runners("jobs:\n  a:\n    runs-on: ubuntu-latest\n    timeout-minutes: 10\n")
        self.assertEqual(["x.yml:3: floating runner image 'ubuntu-latest' -- pin a versioned runner image "
                          "(ubuntu-24.04, windows-2022): '-latest' changes without a commit."], failures)
        self.assertEqual(1, count)

    def test_a_floating_label_in_a_flow_or_block_sequence_is_refused(self):
        flow = self.runners("jobs:\n  a:\n    runs-on: [self-hosted, linux-latest]\n")[0]
        block = self.runners("jobs:\n  a:\n    runs-on:\n      - self-hosted\n      - windows-latest\n    steps: []\n")[0]
        self.assertEqual(1, len(flow))
        self.assertTrue(flow[0].startswith("x.yml:3: floating runner image 'linux-latest'"))
        self.assertEqual(1, len(block))
        self.assertTrue(block[0].startswith("x.yml:5: floating runner image 'windows-latest'"))

    def test_a_floating_label_chosen_through_a_matrix_is_refused(self):
        text = ("jobs:\n  verify:\n    strategy:\n      matrix:\n        os: [ubuntu-24.04, macos-latest]\n"
                "    runs-on: ${{ matrix.os }}\n")
        failures, count = self.runners(text)
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith("x.yml:5: floating runner image 'macos-latest'"))
        self.assertEqual(1, count)

    def test_comments_and_non_runner_names_ending_in_latest_are_accepted(self):
        text = ("# ubuntu-latest used to float under this workflow\njobs:\n  a:\n"
                "    runs-on: ubuntu-24.04  # was ubuntu-latest\n    steps:\n"
                "      - run: docker pull example/image:latest && echo cache-latest\n")
        self.assertEqual(([], 1), self.runners(text))

    def test_the_whole_gate_names_a_floating_runner_of_any_workflow(self):
        self.write(f"{gate.WORKFLOWS}/release.yml", "jobs:\n  publish:\n    runs-on: ubuntu-latest\n    timeout-minutes: 5\n")
        failures = self.failures()
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith(".github/workflows/release.yml:3: floating runner image 'ubuntu-latest'"))


class WholeGateTest(TreeTest):

    def test_a_tree_in_agreement_is_accepted_with_its_counters(self):
        self.assertEqual(([], 1, 1), gate.check(self.root))

    def test_the_root_option_points_the_gate_at_another_tree(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(0, gate.main(["--root", str(self.root)]))
        self.assertIn("WORKFLOW SUPPLY-CHAIN PIN GATE SUCCESS (external uses=1, runs-on=1, Inno=6.7.1)", stdout.getvalue())
        self.write(f"{gate.WORKFLOWS}/release.yml", "jobs:\n  a:\n    runs-on: ubuntu-latest\n")
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            self.assertEqual(1, gate.main(["--root", str(self.root)]))
        self.assertIn("WORKFLOW SUPPLY-CHAIN PIN GATE FAILED", stderr.getvalue())
        self.assertIn(" - .github/workflows/release.yml:3: floating runner image 'ubuntu-latest'", stderr.getvalue())


class RepositoryTest(TreeTest):

    docker_login_kept = None

    def copy_repository(self) -> None:
        shutil.rmtree(self.root / gate.WORKFLOWS)
        shutil.copytree(REPOSITORY / gate.WORKFLOWS, self.root / gate.WORKFLOWS)
        for relative in RELEASE_SCRIPTS + (gate.TESTCONTAINERS_GUARD_CLASS,):
            (self.root / relative).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / relative, self.root / relative)

    def mutate(self, name: str, old: str, new: str) -> None:
        path = self.root / gate.WORKFLOWS / name
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1), encoding="utf-8")

    def test_the_repository_passes_the_gate(self):
        failures, checked, runners = gate.check(REPOSITORY)
        self.assertEqual([], failures)
        self.assertGreater(checked, 0)
        self.assertGreater(runners, 0)

    def test_a_copy_of_the_repository_is_accepted_then_each_mutation_is_named(self):
        self.copy_repository()
        self.assertEqual([], self.failures())
        self.mutate("intellij-plugin-release.yml", "runs-on: ubuntu-24.04", "runs-on: ubuntu-latest")
        self.mutate("release-windows.yml", f"actions/checkout@{SHA}", "actions/checkout@v7")
        self.mutate("pr-ci.yml", "os: [ubuntu-24.04, windows-2022]", "os: [ubuntu-latest, windows-2022]")
        failures = self.failures()
        for expected in (".github/workflows/intellij-plugin-release.yml:", "floating runner image 'ubuntu-latest'",
                         ".github/workflows/release-windows.yml:", "mutable uses ref actions/checkout@v7",
                         ".github/workflows/pr-ci.yml:"):
            self.assertTrue(any(expected in failure for failure in failures), expected)
        self.assertEqual(3, len(failures))

    def test_job_hardening_mutations_of_the_repository_are_named(self):
        self.copy_repository()
        self.mutate("secret-scan.yml", "          persist-credentials: false\n", "")
        self.mutate("pr-ci.yml", "    timeout-minutes: 90\n", "")
        self.mutate("docker-release-validation.yml", "          docker logout\n", "")
        failures = self.failures()
        for expected in (".github/workflows/secret-scan.yml:", "actions/checkout without 'persist-credentials: false'",
                         "job 'verify' runs on a runner without timeout-minutes",
                         "job 'provider-complete-image' runs code while logged in to Docker Hub"):
            self.assertTrue(any(expected in failure for failure in failures), expected)
        self.assertEqual(3, len(failures))

    def test_a_kept_login_that_disappears_from_the_repository_is_a_stale_entry(self):
        self.copy_repository()
        self.mutate("docker-upgrade-qualification.yml", "uses: docker/login-action@", "uses: example/not-a-login@")
        self.assertEqual([".github/workflows/docker-upgrade-qualification.yml: job 'real-docker-upgrade' is listed in "
                          "DOCKER_LOGIN_KEPT but no longer logs in to Docker Hub -- remove the entry."], self.failures())

    def test_the_test_jobs_log_out_after_pulling_the_testcontainers_images(self):
        self.assertNotIn(("pr-ci.yml", "verify"), gate.DOCKER_LOGIN_KEPT)
        self.assertNotIn(("code-audit.yml", "mutation"), gate.DOCKER_LOGIN_KEPT)
        self.copy_repository()
        self.mutate("pr-ci.yml", "          trap log_out EXIT\n", "")
        self.mutate("pr-ci.yml", "log_out() { docker logout || ", "log_out() { true || ")
        self.mutate("code-audit.yml", "      TESTCONTAINERS_RYUK_DISABLED: 'true'\n", "")
        failures = self.failures()
        for expected in ("job 'verify' runs code while logged in to Docker Hub",
                         "job 'mutation' pulls the Testcontainers images, then logs out, without "
                         "'TESTCONTAINERS_RYUK_DISABLED: true' in its env"):
            self.assertTrue(any(expected in failure for failure in failures), (expected, failures))
        self.assertEqual(2, len(failures), failures)

    def test_the_logout_step_of_a_test_job_runs_no_repository_code(self):
        self.copy_repository()
        self.mutate("pr-ci.yml", '          for image in $IMAGES; do docker pull "$image"; done\n',
                    '          for image in $(python3 scripts/quality/check-image-pins.py --testcontainers-images); '
                    'do docker pull "$image"; done\n')
        failures = self.failures()
        self.assertEqual(1, len(failures), failures)
        self.assertIn("job 'verify' runs repository code in the step that logs out of Docker Hub", failures[0])

    def test_the_pull_policy_class_the_workflows_select_must_exist(self):
        self.copy_repository()
        (self.root / gate.TESTCONTAINERS_GUARD_CLASS).unlink()
        self.assertEqual([f"{gate.TESTCONTAINERS_GUARD_CLASS} is missing: the workflows select it as the "
                          "Testcontainers pull policy, so the tests would fail to start any container."],
                         self.failures())


CHECKOUT_STEP = f"      - name: Checkout\n        uses: actions/checkout@{SHA} # v7\n"
LOGIN_STEP = (f"      - name: Log in\n        uses: docker/login-action@{SHA} # v4\n"
              "        with:\n          username: x\n")


class JobHardeningTest(TreeTest):

    def hardening(self, text: str, label: str = ".github/workflows/x.yml") -> tuple[list[str], set[str]]:
        return gate.find_job_hardening_violations(label, text)

    def job(self, steps: str, keys: str = "    runs-on: ubuntu-24.04\n    timeout-minutes: 10\n") -> str:
        return f"name: x\non: push\njobs:\n  build:\n{keys}    steps:\n{steps}"

    def test_a_checkout_that_does_not_persist_the_token_is_accepted(self):
        text = self.job(CHECKOUT_STEP + "        with:\n          fetch-depth: 0\n          persist-credentials: false\n")
        self.assertEqual(([], set()), self.hardening(text))

    def test_a_checkout_written_as_a_first_key_is_read_too(self):
        text = self.job(f"      - uses: actions/checkout@{SHA}\n        with: {{persist-credentials: 'false'}}\n")
        failures, _kept = self.hardening(text)
        self.assertEqual(1, len(failures), "a flow mapping is not read: the key must be written on its own line")
        text = self.job(f"      - uses: actions/checkout@{SHA}\n        with:\n          persist-credentials: 'false'\n")
        self.assertEqual(([], set()), self.hardening(text))

    def test_a_checkout_that_persists_the_token_is_refused(self):
        for with_block in ("", "        with:\n          fetch-depth: 0\n",
                           "        with:\n          persist-credentials: true\n"):
            failures, _kept = self.hardening(self.job(CHECKOUT_STEP + with_block))
            self.assertEqual([".github/workflows/x.yml:8: actions/checkout without 'persist-credentials: false' -- "
                              "the repository token stays in .git/config for every later step of job 'build'."],
                             failures, with_block)

    def test_the_option_of_another_step_does_not_cover_a_checkout(self):
        text = self.job(CHECKOUT_STEP + f"      - uses: actions/setup-java@{SHA}\n        with:\n"
                        "          persist-credentials: false\n")
        failures, _kept = self.hardening(text)
        self.assertEqual(1, len(failures))
        self.assertTrue(failures[0].startswith(".github/workflows/x.yml:8: actions/checkout without"))

    def test_a_job_on_a_runner_without_a_timeout_is_refused(self):
        text = self.job("      - run: echo ok\n", keys="    runs-on: ubuntu-24.04\n")
        self.assertEqual(([".github/workflows/x.yml:4: job 'build' runs on a runner without timeout-minutes -- a "
                           "stuck step would hold the runner for GitHub's 360-minute default."], set()),
                         self.hardening(text))

    def test_a_step_timeout_does_not_stand_for_the_job_timeout(self):
        text = self.job("      - run: echo ok\n        timeout-minutes: 5\n", keys="    runs-on: ubuntu-24.04\n")
        failures, _kept = self.hardening(text)
        self.assertEqual(1, len(failures))
        self.assertIn("job 'build' runs on a runner without timeout-minutes", failures[0])

    def test_a_matrix_runner_with_a_timeout_is_accepted(self):
        keys = ("    strategy:\n      matrix:\n        os: [ubuntu-24.04, windows-2022]\n"
                "    runs-on: ${{ matrix.os }}\n    timeout-minutes: 90\n")
        self.assertEqual(([], set()), self.hardening(self.job("      - run: echo ok\n", keys=keys)))

    def test_a_reusable_workflow_call_needs_no_timeout(self):
        text = ("jobs:\n  scan:\n    uses: ./.github/workflows/osv.yml\n    permissions:\n      contents: read\n"
                "  after:\n    needs: scan\n    runs-on: ubuntu-24.04\n    timeout-minutes: 5\n    steps:\n"
                "      - run: echo ok\n")
        self.assertEqual(([], set()), self.hardening(text))

    def test_keys_outside_the_jobs_block_are_not_jobs(self):
        text = "on:\n  workflow_dispatch:\n    inputs:\n      runs-on:\n        type: string\njobs: {}\n"
        self.assertEqual(([], set()), self.hardening(text))

    def test_a_login_followed_by_a_logout_before_any_other_code_is_accepted(self):
        text = self.job(LOGIN_STEP + f"      - uses: actions/setup-java@{SHA}\n"
                        "      - name: Pull, then log out\n        run: |\n          docker pull a@sha256:0\n"
                        "          docker logout\n      - run: ./build.sh\n")
        self.assertEqual(([], set()), self.hardening(text))

    def test_repository_code_in_the_logout_step_is_refused(self):
        for code in ("python3 scripts/list.py", "./scripts/list.sh", "./mvnw -q help:evaluate"):
            with self.subTest(code=code):
                text = self.job(LOGIN_STEP + f"      - run: |\n          {code}\n          docker logout\n")
                failures, _kept = self.hardening(text)
                self.assertEqual(1, len(failures), failures)
                self.assertIn("runs repository code in the step that logs out of Docker Hub", failures[0])
        text = self.job(LOGIN_STEP + '      - run: |\n          docker pull "$IMAGES"\n          docker logout\n')
        self.assertEqual(([], set()), self.hardening(text))

    def test_code_run_while_logged_in_is_refused(self):
        text = self.job(LOGIN_STEP + "      - name: Build\n        run: ./build.sh\n"
                        "      - run: docker logout\n")
        failures, _kept = self.hardening(text)
        self.assertEqual([".github/workflows/x.yml:12: job 'build' runs code while logged in to Docker Hub -- pull "
                          "the pinned images, then 'docker logout' in the first run: step after the login, or name "
                          "the job in DOCKER_LOGIN_KEPT with its reason."], failures)

    def test_a_logout_written_in_a_comment_does_not_count(self):
        text = self.job(LOGIN_STEP + "      - run: |\n          # docker logout\n          ./build.sh\n")
        failures, _kept = self.hardening(text)
        self.assertEqual(1, len(failures))
        self.assertIn("runs code while logged in to Docker Hub", failures[0])

    def test_a_job_of_the_closed_list_keeps_its_login_and_is_reported_as_kept(self):
        with mock.patch.object(gate, "DOCKER_LOGIN_KEPT", {("x.yml", "build"): "Testcontainers pulls during the tests."}):
            text = self.job(LOGIN_STEP + "      - run: ./mvnw verify\n")
            self.assertEqual(([], {"build"}), self.hardening(text))
            self.assertEqual([], gate.find_stale_docker_login_entries({("x.yml", "build")}))

    def test_a_stale_entry_of_the_closed_list_is_refused(self):
        with mock.patch.object(gate, "DOCKER_LOGIN_KEPT", {("x.yml", "build"): "reason"}):
            text = self.job("      - run: ./mvnw verify\n")
            failures, kept = self.hardening(text)
            self.assertEqual(([], set()), (failures, kept))
            self.assertEqual([".github/workflows/x.yml: job 'build' is listed in DOCKER_LOGIN_KEPT but no longer logs "
                              "in to Docker Hub -- remove the entry."], gate.find_stale_docker_login_entries(kept))

    def test_the_whole_gate_refuses_a_closed_list_entry_whose_workflow_is_gone(self):
        with mock.patch.object(gate, "DOCKER_LOGIN_KEPT", {("gone.yml", "verify"): "reason"}):
            self.assertEqual([".github/workflows/gone.yml: job 'verify' is listed in DOCKER_LOGIN_KEPT but no longer "
                              "logs in to Docker Hub -- remove the entry."], self.failures())

    def test_the_whole_gate_names_each_hardening_failure_of_a_workflow(self):
        self.write(f"{gate.WORKFLOWS}/release.yml",
                   "jobs:\n  publish:\n    runs-on: ubuntu-24.04\n    steps:\n" + CHECKOUT_STEP)
        failures = self.failures()
        self.assertEqual(2, len(failures))
        self.assertTrue(failures[0].startswith(".github/workflows/release.yml:2: job 'publish' runs on a runner"))
        self.assertTrue(failures[1].startswith(".github/workflows/release.yml:5: actions/checkout without"))


ATTEST_STEP = (f"      - uses: actions/attest-build-provenance@{SHA} # v4\n"
               "        with:\n          subject-path: target/dist/*.exe\n")
PUBLISH_STEP = "      - run: ./scripts/release/publish-windows-release.ps1 -Version 1.0.0 -PublishOnly\n"


class ReleaseAttestationTest(TreeTest):

    def publishing_job(self, steps: str, permissions: str = "      contents: write\n      id-token: write\n"
                       "      attestations: write\n", top: str = "permissions:\n  contents: read\n") -> str:
        return (f"name: release\non: workflow_dispatch\n{top}jobs:\n  publish:\n    runs-on: windows-2022\n"
                f"    timeout-minutes: 30\n    permissions:\n{permissions}    steps:\n{steps}")

    def attestation(self, text: str) -> list[str]:
        return gate.find_release_attestation_violations("x.yml", text)

    def test_a_publication_attested_first_with_job_permissions_is_accepted(self):
        self.assertEqual([], self.attestation(self.publishing_job(ATTEST_STEP + PUBLISH_STEP)))

    def test_a_publication_without_attestation_is_refused_and_names_the_job(self):
        self.assertEqual(["x.yml:6: job 'publish' publishes release assets without attesting their provenance first "
                          "(actions/attest-build-provenance)."], self.attestation(self.publishing_job(PUBLISH_STEP)))

    def test_an_attestation_after_the_publication_does_not_count(self):
        failures = self.attestation(self.publishing_job(PUBLISH_STEP + ATTEST_STEP))
        self.assertEqual(1, len(failures))
        self.assertIn("without attesting their provenance first", failures[0])

    def test_each_cli_publication_counts(self):
        for command in ("gh release create v1 a.zip", "gh release upload \"$TAG\" a.zip --repo x"):
            failures = self.attestation(self.publishing_job(f"      - run: {command}\n"))
            self.assertEqual(1, len(failures), command)

    def test_a_validation_only_run_and_a_commented_upload_publish_nothing(self):
        steps = ("      - run: ./scripts/release/publish-windows-release.ps1 -Version 1.0.0 -ValidateOnly\n"
                 "      - run: |\n          # gh release upload v1 a.zip --clobber\n          echo done\n")
        self.assertEqual([], self.attestation(self.publishing_job(steps, permissions="      contents: read\n")))

    def test_missing_job_permissions_are_refused_one_by_one(self):
        failures = self.attestation(self.publishing_job(ATTEST_STEP + PUBLISH_STEP,
                                                        permissions="      contents: write\n      id-token: write\n"))
        self.assertEqual(["x.yml:6: job 'publish' publishes release assets without 'attestations: write' in its own "
                          "permissions; the provenance attestation needs it."], failures)
        failures = self.attestation(self.publishing_job(ATTEST_STEP + PUBLISH_STEP, permissions="      contents: write\n"))
        self.assertEqual(2, len(failures))

    def test_write_all_at_job_level_grants_the_permissions(self):
        text = self.publishing_job(ATTEST_STEP + PUBLISH_STEP, permissions="").replace(
            "    permissions:\n    steps:", "    permissions: write-all\n    steps:")
        self.assertEqual([], self.attestation(text))

    def test_attestation_permissions_granted_to_the_whole_workflow_are_refused(self):
        top = "permissions:\n  contents: read\n  id-token: write\n  attestations: write\n"
        failures = self.attestation(self.publishing_job(ATTEST_STEP + PUBLISH_STEP, top=top))
        self.assertEqual(["x.yml: 'id-token: write' granted to the whole workflow -- grant it to the publishing job "
                          "only.",
                          "x.yml: 'attestations: write' granted to the whole workflow -- grant it to the publishing "
                          "job only."], failures)
        self.assertEqual(2, len(self.attestation(self.publishing_job(ATTEST_STEP + PUBLISH_STEP,
                                                                      top="permissions: write-all\n"))))

    def test_a_documented_script_that_does_not_exist_is_refused(self):
        self.write(gate.SUPPLY_CHAIN_DOC, "Run `scripts/release/build-windows-installer.ps1`, then\n"
                                          "```powershell\n.\\scripts\\m21\\run-s5.ps1 -ExpectedHead <sha>\n```\n")
        self.assertEqual(["docs/developer/supply-chain.md:3: names .\\scripts\\m21\\run-s5.ps1, which is not a file "
                          "of the repository."], self.failures())

    def test_the_repository_attests_what_its_releases_publish(self):
        for name, job in (("release-windows.yml", "publish"), ("intellij-plugin-release.yml", "publish")):
            text = (REPOSITORY / gate.WORKFLOWS / name).read_text(encoding="utf-8")
            self.assertEqual([], gate.find_release_attestation_violations(name, text))
            mutated = text.replace("uses: actions/attest-build-provenance@", "uses: example/not-attest@", 1)
            failures = gate.find_release_attestation_violations(name, mutated)
            self.assertEqual(1, len(failures), name)
            self.assertIn(f"job '{job}' publishes release assets without attesting", failures[0])


if __name__ == "__main__":
    unittest.main()

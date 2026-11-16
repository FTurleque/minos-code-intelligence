#!/usr/bin/env python3
"""Self-test of docker-gate.py: which pull requests may skip the Docker A -> B qualification, and when the gate passes."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "docker-gate.py"
_SPEC = importlib.util.spec_from_file_location("docker_gate", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)


class ScopeTest(unittest.TestCase):

    def test_documentation_alone_is_out_of_scope(self):
        self.assertFalse(gate.in_scope(["docs/user/cli.md", "openspec/changes/x/tasks.md", "README.md",
                                        ".claude/rules/securite.md", "minos-intellij/build.gradle.kts"]))

    def test_a_file_of_the_image_among_documents_puts_the_pull_request_in_scope(self):
        self.assertTrue(gate.in_scope(["docs/user/cli.md", "docker/Dockerfile.mcp.release"]))

    def test_a_path_the_list_does_not_name_is_in_scope(self):
        for path in ("fixtures/java-simple/pom.xml", "packaging/windows/minos-installer.iss.template",
                     "somewhere/new.txt", "pom.xml", "mvnw", ".mvn/wrapper/maven-wrapper.properties"):
            with self.subTest(path=path):
                self.assertTrue(gate.in_scope([path]))

    def test_the_sources_that_build_the_jar_the_workflow_and_its_scripts_are_in_scope(self):
        for path in ("minos-engine/src/main/java/com/minos/store/A.java", "minos-app/pom.xml",
                     ".github/workflows/release-promotion-gate.yml", ".github/workflows/pr-ci.yml",
                     "scripts/ci/qualify-docker-upgrade.ps1", "scripts/release/check-docker-upgrade-evidence.py",
                     "scripts/ci/docker-gate.py"):
            with self.subTest(path=path):
                self.assertTrue(gate.in_scope([path]))

    def test_test_sources_do_not_change_the_image(self):
        # The qualification builds with -DskipTests -DskipITs: a pull request that only touches tests is out of scope.
        self.assertFalse(gate.in_scope(["minos-app/src/test/java/com/minos/packaging/X.java"]))
        self.assertTrue(gate.in_scope(["minos-app/src/test/java/X.java", "minos-app/src/main/java/X.java"]))

    def test_a_sibling_prefix_and_a_nested_markdown_are_in_scope(self):
        self.assertTrue(gate.in_scope(["docs-extra/x.md"]))
        self.assertTrue(gate.in_scope(["docker/README.md"]))

    def test_windows_separators_are_understood(self):
        self.assertFalse(gate.in_scope(["docs\\user\\cli.md"]))
        self.assertTrue(gate.in_scope(["docker\\Dockerfile.mcp.release"]))

    def test_an_empty_list_of_files_is_not_a_proof_of_absence(self):
        self.assertTrue(gate.scope_of([]))

    def test_every_excluded_prefix_exists_in_the_repository(self):
        for path in gate.EXCLUDED_PREFIXES:
            with self.subTest(path=path):
                self.assertTrue((gate.ROOT / path).exists(), f"{path} no longer exists: the exclusion would go dead")


class ExclusionListTest(unittest.TestCase):

    def test_the_closed_list_covers_no_path_of_the_image_or_of_the_gate(self):
        self.assertEqual([], gate.forbidden_exclusions(gate.EXCLUDED_PREFIXES, gate.EXCLUDED_PATTERNS))

    def test_a_list_widened_to_a_path_of_the_image_is_refused_by_entry(self):
        for entry in ("docker/", "pom.xml", ".github/workflows/release-promotion-gate.yml", "scripts/ci/",
                      "scripts/release/", "minos-app/src/main/", ".mvn/", "mvnw"):
            with self.subTest(entry=entry):
                refused = gate.forbidden_exclusions(gate.EXCLUDED_PREFIXES + (entry,), gate.EXCLUDED_PATTERNS)
                self.assertEqual([entry], refused)

    def test_a_pattern_that_covers_a_pom_or_a_main_source_is_refused_by_entry(self):
        for pattern in (r"^[^/]+/pom\.xml$", r"^[^/]+/src/main/", r"^.*\.py$"):
            with self.subTest(pattern=pattern):
                refused = gate.forbidden_exclusions(gate.EXCLUDED_PREFIXES, gate.EXCLUDED_PATTERNS + (pattern,))
                self.assertEqual([pattern], refused)


class ChangedFilesTest(unittest.TestCase):
    """The scope reads the diff of a real repository: base, head, and every case where the answer is 'in scope'."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")
        self.git("config", "commit.gpgsign", "false")
        self.write("docs/a.md", "a\n")
        self.write("docker/Dockerfile", "FROM x\n")
        self.commit("base")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *arguments: str) -> str:
        return subprocess.run(["git", *arguments], cwd=self.root, capture_output=True, text=True,
                              check=True).stdout.strip()

    def write(self, relative: str, content: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="\n")

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def test_a_pull_request_that_only_changes_documents_is_out_of_scope(self):
        self.write("docs/b.md", "b\n")
        head = self.commit("docs")
        self.assertFalse(gate.scope("pull_request", self.base, head, self.root))

    def test_a_file_of_the_image_among_documents_is_in_scope(self):
        self.write("docs/b.md", "b\n")
        self.write("docker/Dockerfile", "FROM y\n")
        head = self.commit("both")
        self.assertTrue(gate.scope("pull_request", self.base, head, self.root))

    def test_an_unknown_directory_is_in_scope(self):
        self.write("brand-new/x.txt", "x\n")
        head = self.commit("new")
        self.assertTrue(gate.scope("pull_request", self.base, head, self.root))

    def test_an_empty_base_an_unknown_base_and_a_git_failure_are_in_scope(self):
        self.write("docs/b.md", "b\n")
        head = self.commit("docs")
        self.assertTrue(gate.scope("pull_request", "", head, self.root))
        self.assertTrue(gate.scope("pull_request", "0" * 40, head, self.root))
        self.assertTrue(gate.scope("pull_request", "f" * 40, head, self.root))
        self.assertTrue(gate.scope("pull_request", self.base, head, self.root / "missing"))

    def test_a_manual_dispatch_is_in_scope(self):
        self.write("docs/b.md", "b\n")
        head = self.commit("docs")
        self.assertTrue(gate.scope("workflow_dispatch", self.base, head, self.root))

    def test_the_output_file_receives_the_scope(self):
        self.write("docs/b.md", "b\n")
        head = self.commit("docs")
        output = self.root / "github-output.txt"
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            code = gate.main(["scope", "--event", "pull_request", "--base", self.base, "--head", head,
                              "--output", str(output)], self.root)
        self.assertEqual(0, code)
        self.assertEqual("docker=false\n", output.read_text(encoding="utf-8"))
        self.assertEqual("docker=false\n", printed.getvalue())


class VerdictTest(unittest.TestCase):

    def test_a_pull_request_out_of_scope_with_a_skipped_qualification_passes_for_that_reason(self):
        problems, reason = gate.decide("success", "false", "skipped")
        self.assertEqual([], problems)
        self.assertEqual("non concerné", reason)

    def test_a_concerned_pull_request_whose_qualification_fails_or_is_cancelled_fails(self):
        for result in ("failure", "cancelled"):
            with self.subTest(result=result):
                problems, _reason = gate.decide("success", "true", result)
                self.assertEqual([f"the qualification ended '{result}'"], problems)

    def test_a_skip_that_the_scope_does_not_justify_fails(self):
        problems, _reason = gate.decide("success", "true", "skipped")
        self.assertEqual(["the qualification ended 'skipped' although the pull request is in scope"], problems)

    def test_a_concerned_pull_request_with_a_successful_qualification_passes_for_the_evidence_check(self):
        problems, reason = gate.decide("success", "true", "success")
        self.assertEqual([], problems)
        self.assertEqual("concerné", reason)

    def test_a_scope_computation_that_did_not_succeed_fails_whatever_it_said(self):
        for changes in ("failure", "cancelled", "skipped", ""):
            with self.subTest(changes=changes):
                problems, reason = gate.decide(changes, "false", "skipped")
                self.assertEqual([f"the scope computation ended '{changes}': the pull request cannot be declared "
                                  "out of scope"], problems)
                self.assertEqual("concerné", reason)

    def test_an_empty_scope_is_treated_as_in_scope(self):
        problems, reason = gate.decide("success", "", "skipped")
        self.assertEqual(["the qualification ended 'skipped' although the pull request is in scope"], problems)
        self.assertEqual("concerné", reason)

    def test_an_out_of_scope_pull_request_whose_qualification_ran_and_failed_still_fails(self):
        problems, _reason = gate.decide("success", "false", "failure")
        self.assertEqual(["the qualification ended 'failure'"], problems)

    def test_the_command_line_prints_the_reason_and_the_exit_code(self):
        for arguments, code, stream in ((["--changes", "success", "--scope", "false", "--qualification", "skipped"], 0, "out"),
                                        (["--changes", "success", "--scope", "true", "--qualification", "failure"], 1, "err"),
                                        (["--changes", "failure", "--scope", "false", "--qualification", "skipped"], 1, "err")):
            with self.subTest(arguments=arguments):
                out, err = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    self.assertEqual(code, gate.main(["verdict", *arguments]))
                if stream == "out":
                    self.assertIn("DOCKER GATE SUCCESS (non concerné)", out.getvalue())
                else:
                    self.assertIn("DOCKER GATE FAILED", err.getvalue())


if __name__ == "__main__":
    unittest.main()

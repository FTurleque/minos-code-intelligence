#!/usr/bin/env python3
"""Self-test of plugin-gate.py: which changes put the IntelliJ plugin in scope, and when its gate may pass."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import tempfile
import unittest
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "plugin-gate.py"
_SPEC = importlib.util.spec_from_file_location("plugin_gate", _MODULE_PATH)
gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(gate)


class ScopeTest(unittest.TestCase):

    def test_the_renderers_that_produce_the_json_the_plugin_reads_are_in_scope(self):
        self.assertTrue(gate.in_scope(["minos-application/src/main/java/com/minos/output/ProjectJson.java"]))

    def test_the_characterization_goldens_are_in_scope(self):
        self.assertTrue(gate.in_scope(["minos-app/src/test/resources/characterization/cli-json.golden"]))

    def test_the_plugin_the_cli_the_git_adapter_and_the_domain_are_in_scope(self):
        for path in ("minos-intellij/src/main/java/com/minos/intellij/protocol/MinosCliClient.java",
                     "minos-cli/src/main/java/com/minos/cli/MinosCli.java",
                     "minos-integration-git/pom.xml",
                     "minos-domain/src/main/java/com/minos/domain/Symbol.java",
                     ".github/workflows/intellij-plugin.yml"):
            with self.subTest(path=path):
                self.assertTrue(gate.in_scope([path]))

    def test_a_change_elsewhere_is_out_of_scope(self):
        self.assertFalse(gate.in_scope(["README.md", "docs/adr/0058-declarer-les-usages-de-modules.md",
                                        "minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java"]))

    def test_one_file_in_scope_is_enough_and_windows_separators_are_understood(self):
        self.assertTrue(gate.in_scope(["README.md", "minos-application\\src\\main\\java\\com\\minos\\output\\X.java"]))

    def test_a_sibling_prefix_is_not_in_scope(self):
        self.assertFalse(gate.in_scope(["minos-cli-extra/Foo.java", "minos-domain-notes.md"]))

    def test_every_scope_path_exists_in_the_repository(self):
        for path in gate.PLUGIN_PATHS:
            with self.subTest(path=path):
                self.assertTrue((gate.ROOT / path).exists(), f"{path} no longer exists: the filter would go dead")


class GitScopeTest(unittest.TestCase):

    def repository(self) -> tuple[Path, str, str]:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)

        def git(*arguments: str) -> str:
            return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *arguments], cwd=root,
                                  capture_output=True, text=True, check=True).stdout.strip()

        git("init", "-q")
        (root / "README.md").write_text("a", encoding="utf-8")
        git("add", ".")
        git("commit", "-q", "-m", "base")
        base = git("rev-parse", "HEAD")
        (root / "docs").mkdir()
        (root / "docs" / "note.md").write_text("b", encoding="utf-8")
        git("add", ".")
        git("commit", "-q", "-m", "docs")
        docs_head = git("rev-parse", "HEAD")
        (root / "minos-application" / "src" / "main" / "java" / "com" / "minos" / "output").mkdir(parents=True)
        (root / "minos-application" / "src" / "main" / "java" / "com" / "minos" / "output" / "ProjectJson.java"
         ).write_text("c", encoding="utf-8")
        git("add", ".")
        git("commit", "-q", "-m", "output")
        return root, base, docs_head

    def test_a_pull_request_that_only_touches_documentation_is_out_of_scope(self):
        root, base, docs_head = self.repository()
        self.assertFalse(gate.scope("pull_request", base, docs_head, root))

    def test_a_pull_request_that_touches_a_renderer_is_in_scope(self):
        root, base, _ = self.repository()
        self.assertTrue(gate.scope("pull_request", base, "HEAD", root))

    def test_a_push_is_compared_between_before_and_after(self):
        root, base, docs_head = self.repository()
        self.assertFalse(gate.scope("push", base, docs_head, root))
        self.assertTrue(gate.scope("push", docs_head, "HEAD", root))

    def test_when_in_doubt_the_plugin_is_in_scope(self):
        root, base, docs_head = self.repository()
        self.assertTrue(gate.scope("workflow_dispatch", base, docs_head, root))
        self.assertTrue(gate.scope("pull_request", "", docs_head, root))
        self.assertTrue(gate.scope("push", "0" * 40, docs_head, root))
        self.assertTrue(gate.scope("pull_request", "deadbeef" * 5, docs_head, root))


class VerdictTest(unittest.TestCase):
    OK = {"plugin": "success", "windows-ownership": "success"}
    SKIPPED = {"plugin": "skipped", "windows-ownership": "skipped"}

    def test_a_plugin_out_of_scope_with_skipped_jobs_passes(self):
        self.assertEqual([], gate.verdict("success", False, self.SKIPPED))

    def test_a_plugin_in_scope_with_green_jobs_passes(self):
        self.assertEqual([], gate.verdict("success", True, self.OK))

    def test_a_failed_or_cancelled_job_fails_in_every_case(self):
        for result in ("failure", "cancelled"):
            for in_scope in (True, False):
                with self.subTest(result=result, in_scope=in_scope):
                    problems = gate.verdict("success", in_scope, {**self.OK, "plugin": result})
                    self.assertEqual(1, len(problems), problems)
                    self.assertIn("plugin", problems[0])

    def test_a_skipped_job_although_the_plugin_is_in_scope_fails(self):
        problems = gate.verdict("success", True, {**self.OK, "windows-ownership": "skipped"})
        self.assertEqual(1, len(problems), problems)
        self.assertIn("windows-ownership", problems[0])

    def test_a_failed_scope_computation_fails_even_when_every_job_was_skipped(self):
        self.assertTrue(gate.verdict("failure", False, self.SKIPPED))


class CommandLineTest(unittest.TestCase):

    def run_main(self, *arguments: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(list(arguments))
        return code, out.getvalue(), err.getvalue()

    def test_scope_writes_the_github_output_line(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "github-output"
            code, out, _ = self.run_main("scope", "--event", "workflow_dispatch", "--output", str(output))
            self.assertEqual(0, code)
            self.assertEqual("plugin=true\n", output.read_text(encoding="utf-8"))
            self.assertIn("plugin=true", out)

    def test_verdict_exit_codes(self):
        passing = ("verdict", "--changes", "success", "--scope", "false",
                   "--plugin", "skipped", "--windows-ownership", "skipped")
        self.assertEqual(0, self.run_main(*passing)[0])
        failing = ("verdict", "--changes", "success", "--scope", "true",
                   "--plugin", "failure", "--windows-ownership", "success")
        code, _, err = self.run_main(*failing)
        self.assertEqual(1, code)
        self.assertIn("plugin ended 'failure'", err)

    def test_an_empty_scope_is_treated_as_in_scope(self):
        code, _, err = self.run_main("verdict", "--changes", "failure", "--scope", "",
                                     "--plugin", "skipped", "--windows-ownership", "skipped")
        self.assertEqual(1, code)
        self.assertIn("scope computation", err)


if __name__ == "__main__":
    unittest.main()

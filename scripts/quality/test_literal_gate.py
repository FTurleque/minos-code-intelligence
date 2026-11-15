#!/usr/bin/env python3
"""Self-test of literal_gate.py: the --self-test driver refuses a gate whose facts it cannot falsify (AUD-TST-09).

Each case builds a small gate on a temporary tree and runs the driver on it: a sound gate passes with one refused
mutation per fact, and every way a rule can be vacuous (a mutation accepted, a fact without a mutation, no fact at
all) makes the driver fail. The tree is compared before and after: the driver never writes to disk.
"""
from __future__ import annotations

import contextlib
import io
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import literal_gate as lg  # noqa: E402 - after the script directory is added to the path

FILES = {
    "src/Service.java": "class Service {\n    static final String ID = \"svc-v1\";\n    void run() {}\n}\n",
    "docs/guide.md": "# Guide\n\nThe `Service` is **fail-closed**.\n\nStatus: Accepted\n",
    "fixture/meta.properties": "formatVersion=1\nname=demo\n",
}


class DriverTestCase(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for relative, content in FILES.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content.encode("utf-8"))

    def snapshot(self) -> dict[str, bytes]:
        return {path.relative_to(self.root).as_posix(): path.read_bytes()
                for path in sorted(self.root.rglob("*")) if path.is_file()}

    def self_test(self, evaluate, mutations=None) -> tuple[int, str, str]:
        before = self.snapshot()
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = lg.self_test(self.root, evaluate, mutations or {})
        self.assertEqual(before, self.snapshot(), "the driver must not write to disk")
        return code, out.getvalue(), err.getvalue()

    def assert_passes(self, evaluate, mutations=None, refused: int | None = None) -> None:
        code, out, err = self.self_test(evaluate, mutations)
        self.assertEqual(0, code, err)
        count = int(re.search(r"(\d+) mutations refused", out).group(1))
        self.assertGreater(count, 0)
        if refused is not None:
            self.assertEqual(refused, count, out)

    def assert_refused(self, evaluate, *expected: str, mutations=None) -> str:
        code, _out, err = self.self_test(evaluate, mutations)
        self.assertEqual(1, code, err)
        for fragment in expected:
            self.assertIn(fragment, err)
        return err


class SoundGateTest(DriverTestCase):

    def test_a_required_literal_and_its_file_give_two_refused_mutations(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.require("src/Service.java", text, 'ID = "svc-v1"', "src/Service.java: missing ID")
            return "OK"
        self.assert_passes(evaluate, refused=2)

    def test_a_literal_under_presentation_normalisation_is_removed_whatever_its_markup(self):
        def normalise(value: str) -> str:
            return re.sub(r"\s+", " ", re.sub(r"[`*]+", "", value)).strip().casefold()

        def evaluate():
            text = lg.read("docs/guide.md")
            lg.require("docs/guide.md", text, "service is fail-closed", "docs/guide.md: missing", normalise)
            return "OK"
        self.assert_passes(evaluate, refused=2)

    def test_a_forbidden_literal_is_inserted(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.forbid("src/Service.java", text, "System.exit(", "src/Service.java: forbidden exit")
            return "OK"
        self.assert_passes(evaluate, refused=2)

    def test_a_forbidden_literal_is_inserted_inside_an_extracted_section(self):
        def evaluate():
            text = lg.read("src/Service.java")
            body = text[text.index("{") + 1:text.rindex("}")]
            lg.forbid("Service body", body, "System.exit(", "Service body: forbidden exit")
            return "OK"
        self.assert_passes(evaluate, refused=2)

    def test_a_required_pattern_is_removed(self):
        def evaluate():
            text = lg.read("docs/guide.md")
            lg.require_pattern("docs/guide.md", text, r"(?m)^Status:\s*Accepted$", "docs/guide.md: status")
            return "OK"
        self.assert_passes(evaluate, refused=2)

    def test_a_forbidden_pattern_receives_its_witness(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.forbid_pattern("src/Service.java", text, r"\bRuntime\.getRuntime\(\)\.exec\b",
                              "src/Service.java: exec", witness="Runtime.getRuntime().exec")
            return "OK"
        self.assert_passes(evaluate, refused=2)

    def test_a_required_file_is_removed_and_a_forbidden_file_is_created(self):
        def evaluate():
            lg.require_file("fixture/meta.properties", "fixture/meta.properties",
                            "missing fixture: fixture/meta.properties")
            lg.forbid_file("tmp/leftover.yml", "tmp/leftover.yml", "leftover must be absent: tmp/leftover.yml")
            return "OK"
        self.assert_passes(evaluate, refused=2)

    def test_a_structured_rule_is_refused_by_its_declared_mutation(self):
        def evaluate():
            meta = lg.read("fixture/meta.properties")
            values = dict(line.split("=", 1) for line in meta.splitlines() if line)
            lg.check("format version", values.get("formatVersion") == "1", "fixture formatVersion must be 1")
            return "OK"
        mutations = {"format version": [("fixture/meta.properties", lambda text: text.replace("=1", "=2"))]}
        self.assert_passes(evaluate, mutations, refused=2)

    def test_a_concatenation_of_files_is_mutated_in_each_contributing_file(self):
        def evaluate():
            text = "\n".join(lg.read(path) for path in ("src/Service.java", "docs/guide.md"))
            lg.require("implementation", text, "Service", "implementation: missing Service")
            return "OK"
        self.assert_passes(evaluate, refused=3)


class VacuousGateTest(DriverTestCase):

    def test_a_rule_that_accepts_the_mutated_copy_is_named_with_its_file_and_literal(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.contains("src/Service.java", text, "svc-v1")  # recorded, but its result is ignored
            return "OK"
        self.assert_refused(evaluate, "accepted", "src/Service.java", "svc-v1")

    def test_an_ignored_forbidden_literal_is_reported(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.lacks("src/Service.java", text, "System.exit(")
            return "OK"
        self.assert_refused(evaluate, "accepted", "System.exit(")

    def test_an_optional_read_is_reported(self):
        def evaluate():
            try:
                lg.read("docs/guide.md")
            except lg.GateFailure:
                pass
            return "OK"
        self.assert_refused(evaluate, "accepted", "docs/guide.md")

    def test_a_fact_on_a_text_that_no_file_holds_has_no_mutation(self):
        def evaluate():
            lg.read("src/Service.java")
            lg.require("constant", "always here", "here", "constant: missing")
            return "OK"
        self.assert_refused(evaluate, "no mutation", "constant")

    def test_a_pattern_that_matches_an_empty_text_has_no_mutation(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.require_pattern("src/Service.java", text, r"x*", "src/Service.java: anything")
            return "OK"
        self.assert_refused(evaluate, "no mutation", "x*")

    def test_a_forbidden_pattern_without_witness_has_no_mutation(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.forbid_pattern("src/Service.java", text, r"exec\(", "src/Service.java: exec", witness="")
            return "OK"
        self.assert_refused(evaluate, "no mutation", "exec")

    def test_a_structured_rule_without_declared_mutation_is_reported(self):
        def evaluate():
            lg.read("fixture/meta.properties")
            lg.check("format version", True, "never")
            return "OK"
        self.assert_refused(evaluate, "no mutation", "format version")

    def test_a_declared_mutation_that_changes_nothing_is_reported(self):
        def evaluate():
            lg.read("fixture/meta.properties")
            lg.check("format version", True, "never")
            return "OK"
        mutations = {"format version": [("fixture/meta.properties", lambda text: text)]}
        self.assert_refused(evaluate, "changes nothing", mutations=mutations)

    def test_a_declared_mutation_for_a_rule_the_gate_never_checks_is_reported(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.require("src/Service.java", text, "svc-v1", "src/Service.java: missing")
            return "OK"
        mutations = {"ghost rule": [("src/Service.java", lambda text: "")]}
        self.assert_refused(evaluate, "ghost rule", mutations=mutations)

    def test_a_failure_that_does_not_name_the_file_is_reported(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.require("src/Service.java", text, "svc-v1", "something is wrong")
            return "OK"
        self.assert_refused(evaluate, "does not name", "src/Service.java")

    def test_a_gate_without_any_fact_fails(self):
        self.assert_refused(lambda: "OK", "no fact")

    def test_a_gate_that_fails_on_the_tree_fails(self):
        def evaluate():
            text = lg.read("src/Service.java")
            lg.require("src/Service.java", text, "absent-literal", "src/Service.java: missing absent-literal")
            return "OK"
        self.assert_refused(evaluate, "absent-literal")


class RunTest(DriverTestCase):

    def test_run_prints_the_success_message_or_the_prefixed_failure(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(0, lg.run(self.root, lambda: "GATE SUCCESS", "GATE FAILED"))
            self.assertEqual(1, lg.run(self.root, lambda: lg.read("nothing.txt"), "GATE FAILED"))
        self.assertEqual("GATE SUCCESS\n", out.getvalue())
        self.assertEqual("GATE FAILED: missing required file: nothing.txt\n", err.getvalue())

    def test_a_reported_failure_is_printed_as_is(self):
        def evaluate():
            raise lg.ReportedFailure("INNER GATE FAILED: detail")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(1, lg.run(self.root, evaluate, "OUTER GATE FAILED"))
        self.assertEqual("INNER GATE FAILED: detail\n", err.getvalue())


if __name__ == "__main__":
    unittest.main()

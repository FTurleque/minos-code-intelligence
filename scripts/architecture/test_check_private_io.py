#!/usr/bin/env python3
"""Self-test of check-private-io.py: the gate that keeps raw file I/O out of production code.

Each forbidden call has its own witness mutation (a source that uses exactly that call must fail), and
each way of escaping the rule has its own refusal: a directory or wildcard in the allowlist, a missing
justification, one more occurrence than listed, a stale maximum. Comments and string literals do not
count; tests, resources, the excluded module and the primitives themselves are out of scope. The real
repository is checked by running the script directly, as the CI does just before this self-test.
"""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest

from importlib import util as importlib_util
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-private-io.py"
_SPEC = importlib_util.spec_from_file_location("check_private_io", _MODULE_PATH)
_MODULE = importlib_util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)

PRIMITIVE = "minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java"
SOURCE = "minos-storage-local/src/main/java/com/minos/storage/local/Store.java"
OTHER = "minos-storage-local/src/main/java/com/minos/storage/local/Other.java"

# One witness per forbidden call: a body that uses exactly that call.
WITNESSES = {
    "Files.createDirectories": "Files.createDirectories(root);",
    "Files.write": "Files.write(file, bytes);",
    "Files.writeString": "Files.writeString(file, text);",
    "Files.newInputStream": "try (var in = Files.newInputStream(file)) { }",
    "FileChannel.open": "var c = FileChannel.open(file, StandardOpenOption.WRITE);",
    "FileChannel.lock": "lockChannel.tryLock();",
}


def java(body: str) -> str:
    return f"package p;\n\nclass C {{\n    void m() throws Exception {{\n        {body}\n    }}\n}}\n"


class Repo:
    """A throw-away source tree plus an allowlist file."""

    def __init__(self, root: Path):
        self.root = root
        self.allowlist = root / "allowlist.json"

    def put(self, relative: str, source: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")

    def allow(self, *entries: dict) -> None:
        self.allowlist.write_text(json.dumps(list(entries)), encoding="utf-8")

    def check(self):
        return _MODULE.check(self.root, self.allowlist)


def entry(file: str = SOURCE, method: str = "Files.createDirectories", maximum: int = 1,
          why: str = "Écrit vers un chemin explicite choisi par l'utilisateur.") -> dict:
    return {"file": file, "method": method, "max": maximum, "justification": why}


class PrivateIoGateTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.repo = Repo(Path(self._tmp.name))

    # ----------------------------------------------------------------------- the forbidden calls

    def test_a_clean_tree_passes(self):
        self.repo.put(SOURCE, java("Object ok = PrivateLocalStorage.ensurePrivateDirectory(root);"))
        violations, scanned, listed = self.repo.check()
        self.assertEqual([], violations)
        self.assertEqual((1, 0), (scanned, listed))

    def test_each_forbidden_call_fails_on_its_own(self):
        for method, body in WITNESSES.items():
            with self.subTest(method=method):
                self.repo.put(SOURCE, java(body))
                violations, _, _ = self.repo.check()
                self.assertEqual(1, len(violations), violations)
                self.assertIn(method, violations[0])
                self.assertIn(SOURCE, violations[0])

    def test_the_witnesses_cover_every_forbidden_call(self):
        self.assertEqual(set(_MODULE.FORBIDDEN), set(WITNESSES))

    def test_a_fully_qualified_call_is_caught(self):
        self.repo.put(SOURCE, java("java.nio.file.Files.createDirectories(root);"))
        self.assertEqual(1, len(self.repo.check()[0]))

    def test_a_static_import_is_caught(self):
        self.repo.put(SOURCE, "package p;\nimport static java.nio.file.Files.writeString;\nclass C { }\n")
        violations, _, _ = self.repo.check()
        self.assertEqual(1, len(violations))
        self.assertIn("Files.writeString", violations[0])

    def test_a_stored_file_lock_is_caught_whatever_the_receiver_is_named(self):
        self.repo.put(SOURCE, java("FileLock held = owner.lock();"))
        self.assertEqual(1, len(self.repo.check()[0]))

    def test_a_jvm_lock_is_not_a_file_lock(self):
        self.repo.put(SOURCE, java("stripe.lock(); other.tryLock(1, TimeUnit.SECONDS);"))
        self.assertEqual([], self.repo.check()[0])

    def test_the_similar_but_allowed_calls_are_not_caught(self):
        self.repo.put(SOURCE, java(
            "Files.createDirectory(d); Files.newOutputStream(f); Files.readAllBytes(f); Files.writeFoo(f);"))
        self.assertEqual([], self.repo.check()[0])

    # ------------------------------------------------------------------------------- what is ignored

    def test_comments_and_string_literals_do_not_count(self):
        self.repo.put(SOURCE, java(
            '// Files.createDirectories(root);\n        /* Files.write(f, b); */\n'
            '        String s = "Files.newInputStream(f) FileChannel.open(f)";\n'
            '        String t = """\n        Files.writeString(f, t);\n        """;\n'
            "        char c = '\"'; /** Files.write(x) */"))
        self.assertEqual([], self.repo.check()[0])

    def test_tests_resources_the_excluded_module_and_the_primitives_are_out_of_scope(self):
        body = java("Files.createDirectories(root);")
        self.repo.put("minos-storage-local/src/test/java/p/T.java", body)
        self.repo.put("minos-provider-scip/src/main/resources/p/Script.java", body)
        self.repo.put("minos-intellij/src/main/java/p/Ide.java", body)
        self.repo.put(PRIMITIVE, body)
        violations, scanned, _ = self.repo.check()
        self.assertEqual([], violations)
        self.assertEqual(1, scanned)  # only the primitive's file is under src/main/java and scanned

    # --------------------------------------------------------------------------------- the allowlist

    def test_a_listed_occurrence_passes_up_to_its_maximum(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a); Files.createDirectories(b);"))
        self.repo.allow(entry(maximum=2))
        violations, _, listed = self.repo.check()
        self.assertEqual([], violations)
        self.assertEqual(2, listed)

    def test_one_more_occurrence_than_listed_fails(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a); Files.createDirectories(b);"))
        self.repo.allow(entry(maximum=1))
        violations, _, _ = self.repo.check()
        self.assertEqual(1, len(violations))
        self.assertIn("allowlist maximum is 1", violations[0])

    def test_a_new_file_is_not_covered_by_the_entry_of_another(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a);"))
        self.repo.put(OTHER, java("Files.createDirectories(a);"))
        self.repo.allow(entry())
        violations, _, _ = self.repo.check()
        self.assertEqual(1, len(violations))
        self.assertIn(OTHER, violations[0])

    def test_a_listed_file_using_another_forbidden_call_fails(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a); Files.write(f, b);"))
        self.repo.allow(entry())
        violations, _, _ = self.repo.check()
        self.assertEqual(1, len(violations))
        self.assertIn("Files.write", violations[0])

    def test_a_stale_maximum_fails_so_the_list_only_shrinks(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a);"))
        self.repo.allow(entry(maximum=3))
        violations, _, _ = self.repo.check()
        self.assertEqual(1, len(violations))
        self.assertIn("ratchet", violations[0])

    def test_an_entry_whose_occurrences_are_gone_fails(self):
        self.repo.put(SOURCE, java("Object ok = null;"))
        self.repo.allow(entry())
        self.assertEqual(1, len(self.repo.check()[0]))

    def test_a_directory_or_a_wildcard_is_refused(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a);"))
        for bad in ("minos-storage-local/src/main/java/", "minos-storage-local/src/main/java/**/*.java",
                    "minos-storage-local/src/main/java/com/minos/storage/local/*.java"):
            with self.subTest(file=bad):
                self.repo.allow(entry(file=bad))
                with self.assertRaises(ValueError):
                    self.repo.check()

    def test_a_missing_file_a_primitive_or_a_test_source_is_refused(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a);"))
        self.repo.put(PRIMITIVE, java("Files.createDirectories(a);"))
        self.repo.put("minos-storage-local/src/test/java/p/T.java", java("int x;"))
        for bad in ("minos-storage-local/src/main/java/com/minos/Absent.java", PRIMITIVE,
                    "minos-storage-local/src/test/java/p/T.java"):
            with self.subTest(file=bad):
                self.repo.allow(entry(file=bad))
                with self.assertRaises(ValueError):
                    self.repo.check()

    def test_a_blank_or_missing_justification_is_refused(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a);"))
        for why in ("", "   ", "à migrer"):
            with self.subTest(why=why):
                self.repo.allow(entry(why=why))
                with self.assertRaises(ValueError):
                    self.repo.check()
        broken = entry()
        del broken["justification"]
        self.repo.allow(broken)
        with self.assertRaises(ValueError):
            self.repo.check()

    def test_an_unknown_method_a_bad_maximum_or_a_duplicate_is_refused(self):
        self.repo.put(SOURCE, java("Files.createDirectories(a);"))
        for bad in ([entry(method="Files.delete")], [entry(maximum=0)], [entry(maximum="2")],
                    [entry(maximum=True)], [entry(), entry()]):
            with self.subTest(bad=bad):
                self.repo.allow(*bad)
                with self.assertRaises(ValueError):
                    self.repo.check()

    # ------------------------------------------------------------------------------------- the CLI

    def test_main_reports_failure_and_success(self):
        original = (_MODULE.ROOT, _MODULE.ALLOWLIST, sys.argv)
        try:
            _MODULE.ROOT = self.repo.root
            _MODULE.ALLOWLIST = self.repo.allowlist
            sys.argv = ["check-private-io.py"]
            self.repo.put(SOURCE, java("Files.write(f, b);"))
            self.assertEqual(1, self._main())
            self.repo.put(SOURCE, java("int ok;"))
            self.assertEqual(0, self._main())
        finally:
            _MODULE.ROOT, _MODULE.ALLOWLIST, sys.argv = original

    @staticmethod
    def _main() -> int:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return _MODULE.main()


if __name__ == "__main__":
    unittest.main()

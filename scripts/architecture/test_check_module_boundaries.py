#!/usr/bin/env python3
"""Self-test of the A3 and A7 rules of check-module-boundaries.py.

Each case builds a throw-away source tree (root pom.xml plus module directories) and runs one rule of
the real script against it: a production package split across modules, a test declared in a package
owned by another module, a split widened to a third module, a test whose path lies about its package,
a reactor module missing from the governed list, a governed module missing from the reactor, and a clean
tree. The rule is strict: no split is tolerated. main() is checked to run both rules (failing spies).
The repository itself is checked by running the script directly, as the CI does just before this
self-test.
"""

from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest

from importlib import util as importlib_util
from pathlib import Path

_MODULE_PATH = Path(__file__).parent / "check-module-boundaries.py"
_SPEC = importlib_util.spec_from_file_location("check_module_boundaries", _MODULE_PATH)
_MODULE = importlib_util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_MODULE)

check_package_ownership = _MODULE.check_package_ownership
check_reactor_modules = _MODULE.check_reactor_modules

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>parent</artifactId>
    <version>1</version>
    <packaging>pom</packaging>
    <modules>
{modules}
    </modules>
</project>
"""


class Tree:
    """A temporary reactor: root pom.xml, then Java sources per module and scope."""

    def __init__(self, root: Path, reactor: tuple[str, ...]):
        self.root = root
        modules = "\n".join(f"        <module>{module}</module>" for module in reactor)
        (root / "pom.xml").write_text(POM.format(modules=modules), encoding="utf-8")

    def java(self, module: str, scope: str, package: str, name: str) -> str:
        directory = self.root / module / "src" / scope / "java" / Path(*package.split("."))
        directory.mkdir(parents=True, exist_ok=True)
        source = directory / f"{name}.java"
        source.write_text(f"// {name}\npackage {package};\n\nclass {name} {{\n}}\n", encoding="utf-8")
        return source.relative_to(self.root).as_posix()


class BoundaryTestCase(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.tree = Tree(Path(directory.name), ("minos-a", "minos-b"))
        self.modules = ("minos-a", "minos-b")
        self.tree.java("minos-a", "main", "com.example.a", "Alpha")
        self.tree.java("minos-a", "test", "com.example.a", "AlphaTest")
        self.tree.java("minos-b", "main", "com.example.b", "Beta")
        self.tree.java("minos-b", "test", "com.example.b", "BetaTest")
        # A test-only package owned by no production source is legitimate.
        self.tree.java("minos-b", "test", "com.example.fixtures", "Fixture")

    def ownership(self):
        return check_package_ownership(self.tree.root, self.modules)

    def assertFailure(self, action, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            action()
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))
        return str(raised.exception)


class CleanTreeTest(BoundaryTestCase):
    def test_clean_tree_passes_both_rules(self):
        check_reactor_modules(self.tree.root, self.modules)
        self.assertEqual(self.ownership(), 2)


class SplitPackageTest(BoundaryTestCase):
    def test_production_package_declared_by_two_modules_fails(self):
        self.tree.java("minos-b", "main", "com.example.a", "Intruder")
        self.assertFailure(self.ownership, "A3", "package com.example.a", "minos-a, minos-b")

    def test_test_declared_in_a_package_owned_by_another_module_fails(self):
        relative = self.tree.java("minos-b", "test", "com.example.a", "ReachesIntoAlphaTest")
        self.assertFailure(self.ownership, "A3", relative, "package com.example.a", "belong to minos-a")

    def test_package_declared_in_a_comment_does_not_count(self):
        source = self.tree.root / self.tree.java("minos-b", "main", "com.example.b", "Commented")
        source.write_text("/*\npackage com.example.a;\n*/\npackage com.example.b;\n\nclass Commented {\n}\n",
                          encoding="utf-8")
        self.assertEqual(self.ownership(), 2)


    def test_split_across_three_modules_names_all_of_them(self):
        self.tree = Tree(self.tree.root, ("minos-a", "minos-b", "minos-c"))
        self.modules = ("minos-a", "minos-b", "minos-c")
        self.tree.java("minos-b", "main", "com.example.a", "Intruder")
        self.tree.java("minos-c", "main", "com.example.a", "SecondIntruder")
        self.assertFailure(self.ownership, "package com.example.a", "3 modules", "minos-a, minos-b, minos-c")

    def test_test_whose_path_does_not_state_its_package_fails(self):
        self.tree.java("minos-a", "main", "com.example.moved", "Owner")
        relative = self.tree.java("minos-b", "test", "com.example.b", "Liar")
        lying_source = "\n".join(("package com.example.moved;", "", "class Liar {", "}", ""))
        (self.tree.root / relative).write_text(lying_source, encoding="utf-8")
        self.assertFailure(self.ownership, relative, "package/path mismatch")

    def test_the_rule_has_no_tolerance_left(self):
        for leftover in ("TOLERATED_SPLIT_PACKAGES", "TOLERATED_FOREIGN_TESTS"):
            self.assertFalse(hasattr(_MODULE, leftover), leftover)


class ReactorTest(BoundaryTestCase):
    def test_reactor_module_absent_from_the_governed_list_fails(self):
        Tree(self.tree.root, ("minos-a", "minos-b", "minos-new"))
        self.assertFailure(lambda: check_reactor_modules(self.tree.root, self.modules),
                           "A7", "minos-new", "absent from MODULES")

    def test_governed_module_absent_from_the_reactor_fails(self):
        Tree(self.tree.root, ("minos-a",))
        self.assertFailure(lambda: check_reactor_modules(self.tree.root, self.modules),
                           "A7", "MODULES lists minos-b", "absent from the root pom.xml")

    def test_module_added_by_a_profile_is_part_of_the_reactor(self):
        pom = self.tree.root / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace(
            "</project>",
            "    <profiles><profile><id>extra</id><modules><module>minos-extra</module></modules></profile></profiles>\n"
            "</project>"), encoding="utf-8")
        self.assertFailure(lambda: check_reactor_modules(self.tree.root, self.modules), "minos-extra")

    def test_missing_root_pom_fails_instead_of_passing(self):
        (self.tree.root / "pom.xml").unlink()
        self.assertFailure(lambda: check_reactor_modules(self.tree.root, self.modules), "root pom.xml is missing")


class MainWiringTest(unittest.TestCase):
    """main() must run both rules: a spy that fails must turn the whole gate red."""

    def run_main_with(self, **spies):
        originals = {name: getattr(_MODULE, name) for name in spies}
        argv = sys.argv
        try:
            for name, spy in spies.items():
                setattr(_MODULE, name, spy)
            sys.argv = [str(_MODULE_PATH)]
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as error:
                code = _MODULE.main()
            return code, error.getvalue()
        finally:
            sys.argv = argv
            for name, original in originals.items():
                setattr(_MODULE, name, original)

    @staticmethod
    def spy(calls, name, fail=True, result=None):
        def spy(*arguments, **keywords):
            calls.append(name)
            if fail:
                raise RuntimeError(f"spy {name} failed")
            return result
        return spy

    def test_main_runs_the_reactor_rule(self):
        calls: list[str] = []
        code, error = self.run_main_with(check_reactor_modules=self.spy(calls, "reactor"))
        self.assertEqual(code, 1)
        self.assertEqual(calls, ["reactor"])
        self.assertIn("spy reactor failed", error)

    def test_main_runs_the_package_ownership_rule(self):
        calls: list[str] = []
        code, error = self.run_main_with(
            check_reactor_modules=self.spy(calls, "reactor", fail=False),
            check_package_ownership=self.spy(calls, "ownership"))
        self.assertEqual(code, 1)
        self.assertEqual(calls, ["reactor", "ownership"])
        self.assertIn("spy ownership failed", error)


if __name__ == "__main__":
    unittest.main()

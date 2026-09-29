#!/usr/bin/env python3
"""Self-test of the A3 and A7 rules of check-module-boundaries.py.

Each case builds a throw-away source tree (root pom.xml plus module directories) and runs one rule of
the real script against it: a production package split across modules, a test declared in a package
owned by another module, a reactor module missing from the governed list, a governed module missing
from the reactor, the ratchet (tolerated, stale and widened entries) and a clean tree. The repository
itself is checked by running the script directly, as the CI does just before this self-test.
"""

from __future__ import annotations

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

    def ownership(self, tolerated_split=None, tolerated_tests=frozenset()):
        return check_package_ownership(self.tree.root, self.modules, tolerated_split or {}, tolerated_tests)

    def assertFailure(self, action, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            action()
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))
        return str(raised.exception)


class CleanTreeTest(BoundaryTestCase):
    def test_clean_tree_passes_both_rules(self):
        check_reactor_modules(self.tree.root, self.modules)
        self.assertEqual(self.ownership(), (2, 0, 0))


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
        self.assertEqual(self.ownership(), (2, 0, 0))


class RatchetTest(BoundaryTestCase):
    def test_tolerated_split_and_tolerated_test_pass(self):
        self.tree.java("minos-b", "main", "com.example.a", "Intruder")
        relative = self.tree.java("minos-b", "test", "com.example.b.more", "Unrelated")
        self.tree.java("minos-a", "main", "com.example.b.more", "Owner")
        self.assertEqual(
            self.ownership({"com.example.a": frozenset({"minos-a", "minos-b"})}, frozenset({relative})),
            (3, 1, 1))

    def test_stale_split_entry_fails(self):
        self.assertFailure(
            lambda: self.ownership({"com.example.a": frozenset({"minos-a", "minos-b"})}),
            "stale ratchet entry", "com.example.a")

    def test_stale_test_entry_fails(self):
        stale = "minos-b/src/test/java/com/example/b/BetaTest.java"
        self.assertFailure(lambda: self.ownership(tolerated_tests=frozenset({stale})), "stale ratchet entry", stale)

    def test_split_widened_beyond_its_tolerated_modules_fails(self):
        self.tree = Tree(self.tree.root, ("minos-a", "minos-b", "minos-c"))
        self.modules = ("minos-a", "minos-b", "minos-c")
        self.tree.java("minos-b", "main", "com.example.a", "Intruder")
        self.tree.java("minos-c", "main", "com.example.a", "SecondIntruder")
        self.assertFailure(
            lambda: self.ownership({"com.example.a": frozenset({"minos-a", "minos-b"})}),
            "minos-a, minos-b, minos-c", "the ratchet tolerates only minos-a, minos-b")


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


class RepositoryPolicyTest(unittest.TestCase):
    def test_ratchet_only_names_governed_modules(self):
        governed = set(_MODULE.MODULES)
        for package, modules in getattr(_MODULE, "TOLERATED_SPLIT_PACKAGES", {}).items():
            self.assertTrue(len(modules) > 1, package)
            self.assertLessEqual(set(modules), governed, package)
        for relative in getattr(_MODULE, "TOLERATED_FOREIGN_TESTS", frozenset()):
            self.assertIn(relative.split("/", 1)[0], governed, relative)


if __name__ == "__main__":
    unittest.main()

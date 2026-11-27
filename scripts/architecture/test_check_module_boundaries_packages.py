#!/usr/bin/env python3
"""Self-test of the package and reactor rules of check-module-boundaries.py: A3, A7, A8, A15 and the source layout.

Each case builds a throw-away source tree (root pom.xml plus module directories) and runs one rule of the real script
against it: a production package split across modules, a test declared in a package owned by another module, a split
widened to a third module, a test whose path lies about its package, a reactor module missing from the governed list,
a governed module missing from the reactor, a package cycle, an unreferenced public type, and a clean tree. The rule
is strict: no split is tolerated.
"""

from __future__ import annotations

import contextlib
import io
import shutil
import sys
import tempfile
import unittest

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from module_boundaries_testing import PACKAGES, _MODULE, cut_around_dots, second_on_its_line, write_root_pom


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


class JavaLayoutRuleTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def write(self, module, relative, package, name="A"):
        path = self.root / module / "src" / "main" / "java" / relative / f"{name}.java"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"package {package};\nclass {name} {{}}\n", encoding="utf-8")

    def test_a_clean_tree_is_counted(self):
        self.write("minos-domain", Path("com/minos/domain"), "com.minos.domain")
        self.write("minos-engine", Path("com/minos/store"), "com.minos.store")
        total, counts = _MODULE.check_java_layout(self.root)
        self.assertEqual(2, total)
        self.assertEqual(1, counts["minos-domain"])
        self.assertEqual(0, counts["minos-cli"])

    def test_a_production_source_declared_by_two_modules_is_refused(self):
        self.write("minos-domain", Path("com/minos/shared"), "com.minos.shared")
        self.write("minos-engine", Path("com/minos/shared"), "com.minos.shared")
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_java_layout(self.root)
        self.assertIn("duplicate production source", str(raised.exception))

    def test_a_package_that_does_not_match_its_directory_is_refused(self):
        self.write("minos-domain", Path("com/minos/domain"), "com.minos.elsewhere")
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_java_layout(self.root)
        self.assertIn("package/path mismatch", str(raised.exception))

    def test_a_source_without_a_package_is_refused(self):
        path = self.root / "minos-domain" / "src" / "main" / "java" / "A.java"
        path.parent.mkdir(parents=True)
        path.write_text("class A {}\n", encoding="utf-8")
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_java_layout(self.root)
        self.assertIn("must declare a package", str(raised.exception))


class PackageCycleRuleTest(unittest.TestCase):
    """A8: package cycles, as a ratchet over a table of known cycles."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        write_root_pom(self.root, "com.example")
        self.modules = ("minos-a", "minos-b")

    def java(self, owner, package, name, imports=(), source="main"):
        """A source of `owner` (a module, or an external source root such as the plugin)."""
        directory = self.root / owner / "src" / source / "java" / Path(*package.split("."))
        directory.mkdir(parents=True, exist_ok=True)
        lines = "".join(f"import {line};\n" for line in imports)
        (directory / f"{name}.java").write_text(f"package {package};\n{lines}class {name} {{}}\n", encoding="utf-8")

    def cycle(self, first="com.example.a", second="com.example.b", module_first="minos-a", module_second="minos-b"):
        self.java(module_first, first, "A", [f"{second}.B"])
        self.java(module_second, second, "B", [f"{first}.A"])

    def run_rule(self, known=None, external=()):
        return _MODULE.check_package_cycles(self.root, self.modules, external, known or {})

    def refused(self, *fragments, known=None, external=()):
        with self.assertRaises(RuntimeError) as raised:
            self.run_rule(known, external)
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_an_acyclic_tree_passes_with_an_empty_table(self):
        self.java("minos-a", "com.example.a", "A", ["com.example.b.B"])
        self.java("minos-b", "com.example.b", "B")
        self.assertEqual((0, 2), self.run_rule())

    def test_a_new_cycle_is_refused_with_a_witness_edge_in_each_direction(self):
        self.cycle()
        self.refused("A8", "com.example.a", "com.example.b", "A.java", "B.java")

    def test_a_known_cycle_unchanged_is_accepted(self):
        self.cycle()
        known = {"a-b": (frozenset({"com.example.a", "com.example.b"}), "test")}
        self.assertEqual((1, 2), self.run_rule(known))

    def test_a_known_cycle_that_grows_is_refused(self):
        self.cycle()
        self.java("minos-b", "com.example.c", "C", ["com.example.a.A"])
        self.java("minos-a", "com.example.a", "A2", ["com.example.c.C"])
        known = {"a-b": (frozenset({"com.example.a", "com.example.b"}), "test")}
        self.refused("com.example.c", known=known)

    def test_a_known_cycle_that_was_lifted_but_is_still_listed_is_refused(self):
        self.java("minos-a", "com.example.a", "A", ["com.example.b.B"])
        self.java("minos-b", "com.example.b", "B")
        known = {"a-b": (frozenset({"com.example.a", "com.example.b"}), "test")}
        self.refused("a-b", "no longer", known=known)

    def test_a_known_cycle_that_shrinks_is_refused(self):
        self.cycle()
        known = {"abc": (frozenset({"com.example.a", "com.example.b", "com.example.c"}), "test")}
        self.refused("abc", known=known)

    def test_wildcard_and_fully_qualified_references_are_edges(self):
        self.java("minos-a", "com.example.a", "A", ["com.example.b.*"])
        self.java("minos-b", "com.example.b", "B")
        directory = self.root / "minos-b" / "src" / "main" / "java" / "com" / "example" / "b"
        (directory / "B.java").write_text("package com.example.b;\nclass B { com.example.a.A a; }\n", encoding="utf-8")
        self.refused("com.example.a", "com.example.b")

    def test_a_cycle_closed_by_a_wildcard_following_another_import_on_its_line_is_refused(self):
        # AUD-ARC-17: Java does not tie an import to the start of a line.
        self.java("minos-a", "com.example.a", "A", [second_on_its_line("com.example.b.*")])
        self.java("minos-b", "com.example.b", "B", ["com.example.a.A"])
        self.refused("A8", "com.example.a", "com.example.b")

    def test_a_cycle_closed_by_a_name_cut_around_its_dots_is_refused(self):
        # AUD-ARC-17: blanks, line breaks and comments may surround the dots of a name.
        self.java("minos-b", "com.example.b", "B", ["com.example.a.A"])
        source = self.root / "minos-a" / "src" / "main" / "java" / "com" / "example" / "a" / "A.java"
        for use, name, text in (("import", "com.example.b.B", "import {};\nclass A {{}}"),
                                ("wildcard", "com.example.b.*", "import {};\nclass A {{}}"),
                                ("qualified name", "com.example.b.B", "class A {{ {} b; }}")):
            for form, written in cut_around_dots(name).items():
                with self.subTest(use=use, form=form):
                    self.java("minos-a", "com.example.a", "A")
                    source.write_text("package com.example.a;\n" + text.format(written) + "\n", encoding="utf-8")
                    self.refused("A8", "com.example.a", "com.example.b")

    def test_imports_in_comments_and_test_sources_do_not_count(self):
        self.java("minos-a", "com.example.a", "A", ["com.example.b.B"])
        self.java("minos-b", "com.example.b", "B")
        self.java("minos-b", "com.example.b", "BTest", ["com.example.a.A"], source="test")
        directory = self.root / "minos-b" / "src" / "main" / "java" / "com" / "example" / "b"
        (directory / "B.java").write_text("package com.example.b;\n// import com.example.a.A;\nclass B {}\n",
                                          encoding="utf-8")
        self.assertEqual((0, 2), self.run_rule())

    def test_a_cycle_in_a_source_root_outside_the_reactor_is_refused(self):
        self.java("plugin", "com.plugin.x", "X", ["com.plugin.y.Y"])
        self.java("plugin", "com.plugin.y", "Y", ["com.plugin.x.X"])
        self.refused("com.plugin.x", "com.plugin.y", external=("plugin",))

    def test_a_package_is_not_an_edge_to_itself(self):
        self.java("minos-a", "com.example.a", "A", ["com.example.a.Other"])
        self.java("minos-a", "com.example.a", "Other")
        self.assertEqual((0, 1), self.run_rule())

    def test_the_real_repository_has_no_package_cycle_and_no_excuse_for_one(self):
        self.assertEqual({}, PACKAGES.KNOWN_PACKAGE_CYCLES)
        with contextlib.redirect_stdout(io.StringIO()):
            cycles, packages = _MODULE.check_package_cycles()
        self.assertEqual(0, cycles)
        self.assertGreater(packages, 45)


class UnreferencedProductionTypeRuleTest(unittest.TestCase):
    """A15: a public production type is named by production, exempted by rule, or listed with a reason."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="minos-a16-"))
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.write("minos-engine", "main", "com.minos.a", "Used", "public final class Used { }")
        self.write("minos-application", "main", "com.minos.b", "Caller",
                   "import com.minos.a.Used;\n\nfinal class Caller { Used used; }")

    def write(self, module, scope, package, name, body):
        directory = self.root / module / "src" / scope / "java" / Path(*package.split("."))
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{name}.java").write_text(f"package {package};\n\n{body}\n", encoding="utf-8")

    def check(self, declared=None):
        return _MODULE.check_unreferenced_production_types(self.root, {} if declared is None else declared)

    def test_clean_tree_is_accepted(self):
        self.assertEqual(0, self.check())

    def test_a_package_wildcard_following_another_import_on_its_line_references_the_type(self):
        self.write("minos-application", "main", "com.minos.b", "Caller",
                   f"import {second_on_its_line('com.minos.a.*')};\n\nfinal class Caller {{ Used used; }}")
        self.assertEqual(0, self.check())

    def test_a_name_cut_around_its_dots_references_the_type(self):
        for use, name, body in (("import", "com.minos.a.Used", "import {};\n\nfinal class Caller {{ Used used; }}"),
                                ("wildcard", "com.minos.a.*", "import {};\n\nfinal class Caller {{ Used used; }}"),
                                ("qualified name", "com.minos.a.Used", "final class Caller {{ {} used; }}")):
            for form, written in cut_around_dots(name).items():
                with self.subTest(use=use, form=form):
                    self.write("minos-application", "main", "com.minos.b", "Caller", body.format(written))
                    self.assertEqual(0, self.check())

    def test_public_class_without_caller_is_refused(self):
        self.write("minos-engine", "main", "com.minos.a", "Orphan", "public final class Orphan { }")
        with self.assertRaisesRegex(RuntimeError, r"A15 .*minos-engine/com\.minos\.a\.Orphan"):
            self.check()

    def test_class_named_only_by_a_test_is_refused(self):
        self.write("minos-engine", "main", "com.minos.a", "Orphan", "public final class Orphan { }")
        self.write("minos-engine", "test", "com.minos.a", "OrphanTest", "class OrphanTest { Orphan orphan; }")
        with self.assertRaisesRegex(RuntimeError, r"A15 .*Orphan"):
            self.check()

    def test_name_in_a_comment_or_string_does_not_count(self):
        self.write("minos-engine", "main", "com.minos.a", "Orphan", "public final class Orphan { }")
        self.write("minos-engine", "main", "com.minos.a", "Talker",
                   "final class Talker { // Orphan\n String s = \"Orphan\"; }")
        with self.assertRaisesRegex(RuntimeError, r"A15 .*Orphan"):
            self.check()

    def test_service_registered_class_is_accepted(self):
        self.write("minos-engine", "main", "com.minos.a", "Plugin", "public final class Plugin { }")
        services = self.root / "minos-engine" / "src" / "main" / "resources" / "META-INF" / "services"
        services.mkdir(parents=True)
        (services / "com.minos.a.Spi").write_text("# provider\ncom.minos.a.Plugin\n", encoding="utf-8")
        self.assertEqual(0, self.check())

    def test_main_class_is_accepted(self):
        self.write("minos-cli", "main", "com.minos.c", "Launcher",
                   "public final class Launcher { public static void main(String[] args) { } }")
        self.assertEqual(0, self.check())

    def test_listed_class_is_accepted(self):
        self.write("minos-nexus", "main", "com.minos.n", "Surface", "public final class Surface { }")
        self.assertEqual(1, self.check({"minos-nexus/com.minos.n.Surface": "public surface"}))

    def test_stale_entry_is_refused(self):
        with self.assertRaisesRegex(RuntimeError, r"A15 .*no longer exist.*com\.minos\.a\.Gone"):
            self.check({"minos-engine/com.minos.a.Gone": "deleted class"})

    def test_entry_whose_type_gained_a_caller_is_refused(self):
        with self.assertRaisesRegex(RuntimeError, r"A15 .*production now names.*com\.minos\.a\.Used"):
            self.check({"minos-engine/com.minos.a.Used": "was unused"})

    def test_entry_without_reason_is_refused(self):
        self.write("minos-nexus", "main", "com.minos.n", "Surface", "public final class Surface { }")
        with self.assertRaisesRegex(RuntimeError, r"A15 .*reason.*com\.minos\.n\.Surface"):
            self.check({"minos-nexus/com.minos.n.Surface": "  "})

    def test_repository_list_matches_the_head(self):
        self.assertEqual(len(PACKAGES.UNREFERENCED_PRODUCTION_TYPES), _MODULE.check_unreferenced_production_types())

    def test_the_nexus_semantic_signal_stays_declared_as_not_qualified(self):
        entry = "minos-nexus/com.minos.nexus.NexusSemanticSignalService"
        self.assertIn("not qualified by a client", PACKAGES.UNREFERENCED_PRODUCTION_TYPES[entry])
        deprived = {key: reason for key, reason in PACKAGES.UNREFERENCED_PRODUCTION_TYPES.items() if key != entry}
        with self.assertRaisesRegex(RuntimeError, r"A15 .*com\.minos\.nexus\.NexusSemanticSignalService"):
            _MODULE.check_unreferenced_production_types(declared=deprived)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Self-test of every rule of check-module-boundaries.py: A2 (POM, policy, sources), A3, A7, A8 and A9.

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
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

from importlib import util as importlib_util
from pathlib import Path
from unittest import mock

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


POM_NAMESPACE = "http://maven.apache.org/POM/4.0.0"
MODULES = _MODULE.MODULES


def pom_text(artifact: str, dependencies=(), build: str = "", profiles: str = "", management: str = "") -> str:
    """A module POM. `dependencies` are (artifactId, scope, groupId) triples; groupId defaults to com.minos."""
    declared = "".join(
        f"<dependency><groupId>{group or 'com.minos'}</groupId><artifactId>{name}</artifactId>"
        + (f"<scope>{scope}</scope>" if scope else "") + "</dependency>"
        for name, scope, group in dependencies)
    return (f'<project xmlns="{POM_NAMESPACE}"><modelVersion>4.0.0</modelVersion>'
            f"<parent><groupId>com.minos</groupId><artifactId>minos-parent</artifactId><version>1</version></parent>"
            f"<artifactId>{artifact}</artifactId>{build}{management}<dependencies>{declared}</dependencies>"
            f"{profiles}</project>")


def write_root_pom(root: Path, group: str = "com.minos") -> None:
    (root / "pom.xml").write_text(f'<project xmlns="{POM_NAMESPACE}"><modelVersion>4.0.0</modelVersion>'
                                  f"<groupId>{group}</groupId></project>", encoding="utf-8")


def element(text: str) -> ET.Element:
    return ET.fromstring(text)


def scoped_graph(**dependencies: dict) -> dict[str, dict[str, str]]:
    """Every governed module with no internal dependency, except the ones given (name with _ for -)."""
    graph = {module: {} for module in MODULES}
    for name, deps in dependencies.items():
        graph[name.replace("_", "-")] = dict(deps)
    return graph


class HexagonalRuleTest(unittest.TestCase):
    """A2 / ADR 0042: one refused and one accepted case per branch of check_hexagonal_boundaries."""

    def refused(self, graph, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_hexagonal_boundaries(graph)
        self.assertIn("A2 hexagonal boundary violated", str(raised.exception))
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_the_application_layer_may_not_depend_on_an_adapter(self):
        self.refused(scoped_graph(minos_application={"minos-storage-local": "compile"}),
                     "minos-application -> minos-storage-local")
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_application={"minos-engine": "compile"}))

    def test_a_surface_may_not_depend_on_an_adapter_in_any_scope(self):
        for scope in ("compile", "runtime", "test"):
            with self.subTest(scope=scope):
                self.refused(scoped_graph(minos_cli={"minos-provider-scip": scope}),
                             "minos-cli -> minos-provider-scip")
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_cli={"minos-application": "compile"}))

    def test_a_surface_sees_the_composition_root_at_runtime_or_in_tests_only(self):
        for module in ("minos-cli", "minos-api", "minos-mcp", "minos-nexus"):
            with self.subTest(module=module):
                self.refused(scoped_graph(**{module: {"minos-bootstrap": "compile"}}), module, "minos-bootstrap")
                for scope in ("runtime", "test"):
                    _MODULE.check_hexagonal_boundaries(scoped_graph(**{module: {"minos-bootstrap": scope}}))

    def test_an_adapter_never_reaches_a_higher_layer(self):
        for adapter in sorted(_MODULE.ADAPTER_MODULES):
            for upper in ("minos-application", "minos-bootstrap", "minos-app", "minos-cli", "minos-mcp"):
                with self.subTest(adapter=adapter, upper=upper):
                    self.refused(scoped_graph(**{adapter: {upper: "compile"}}), f"{adapter} -> {upper}")
            _MODULE.check_hexagonal_boundaries(scoped_graph(**{adapter: {"minos-engine": "compile"}}))

    def test_the_composition_root_reaches_the_optional_backend_in_tests_only(self):
        self.refused(scoped_graph(minos_bootstrap={"minos-storage-postgresql": "compile"}),
                     "minos-bootstrap -> minos-storage-postgresql")
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_bootstrap={"minos-storage-postgresql": "test"}))
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_bootstrap={"minos-storage-local": "compile"}))

    def test_the_composition_root_sits_below_the_surfaces_and_the_assembly(self):
        self.refused(scoped_graph(minos_bootstrap={"minos-cli": "test"}), "minos-bootstrap -> minos-cli")
        self.refused(scoped_graph(minos_bootstrap={"minos-app": "test"}), "minos-bootstrap -> minos-app")

    def test_the_domain_and_the_engine_know_no_adapter(self):
        self.refused(scoped_graph(minos_engine={"minos-runtime-local": "compile"}), "minos-engine -> minos-runtime-local")
        self.refused(scoped_graph(minos_domain={"minos-integration-git": "test"}), "minos-domain -> minos-integration-git")

    def test_the_assembly_may_depend_on_every_module(self):
        everything = {module: "compile" for module in MODULES if module != "minos-app"}
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_app=everything))


class DependencyPolicyTest(unittest.TestCase):

    def refused(self, graph, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_dependency_policy(graph)
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_an_edge_outside_the_allowed_table_is_refused(self):
        self.refused(scoped_graph(minos_mcp={"minos-nexus": "compile"}), "minos-mcp", "forbidden MINOS dependencies",
                     "minos-nexus")

    def test_the_nexus_surface_may_not_depend_on_the_engine_again(self):
        # AUD-ARC-01, lot 5: the NEXUS export goes through minos-application; the edge to the engine is gone.
        self.refused(scoped_graph(minos_nexus={"minos-engine": "compile"}), "minos-nexus",
                     "forbidden MINOS dependencies", "minos-engine")

    def test_a_declared_subset_of_the_allowed_table_is_accepted(self):
        _MODULE.check_dependency_policy(scoped_graph(minos_runtime_local={"minos-engine": "compile"}))

    def test_a_graph_with_nothing_declared_is_accepted(self):
        _MODULE.check_dependency_policy(scoped_graph())

    def test_the_domain_must_stay_free_of_internal_dependencies(self):
        with mock.patch.dict(_MODULE.ALLOWED_DEPENDENCIES, {"minos-domain": frozenset({"minos-engine"})}):
            self.refused(scoped_graph(minos_domain={"minos-engine": "compile"}), "minos-domain must remain")

    def test_the_engine_may_only_depend_on_the_domain(self):
        with mock.patch.dict(_MODULE.ALLOWED_DEPENDENCIES, {"minos-engine": frozenset({"minos-domain", "minos-nexus"})}):
            self.refused(scoped_graph(minos_engine={"minos-nexus": "compile"}), "minos-engine may depend only")

    def test_a_cycle_between_modules_is_refused(self):
        allowed = {"minos-nexus": frozenset({"minos-cli"}), "minos-cli": frozenset({"minos-nexus"})}
        with mock.patch.dict(_MODULE.ALLOWED_DEPENDENCIES, allowed):
            self.refused(scoped_graph(minos_nexus={"minos-cli": "compile"}, minos_cli={"minos-nexus": "compile"}),
                         "internal Maven dependency cycle")

    def test_an_acyclic_graph_is_accepted(self):
        _MODULE.check_dependency_policy(scoped_graph(
            minos_engine={"minos-domain": "compile"}, minos_application={"minos-engine": "compile"},
            minos_cli={"minos-application": "compile"}))

    def test_the_table_must_cover_every_reactor_module_exactly(self):
        without_one = {k: v for k, v in _MODULE.ALLOWED_DEPENDENCIES.items() if k != "minos-mcp"}
        with mock.patch.object(_MODULE, "ALLOWED_DEPENDENCIES", without_one):
            self.refused(scoped_graph(), "must cover every reactor module")


class InternalDependencyRecognitionTest(unittest.TestCase):
    """E02: an internal dependency is recognised by its artifact, with the group read from the root POM."""

    def dependencies(self, text, group="com.minos"):
        return _MODULE.minos_dependencies("minos-x", element(text), group)

    def test_dependencies_with_the_root_group_are_recognised_with_their_scope(self):
        text = pom_text("minos-x", [("minos-engine", None, None), ("minos-domain", "test", None)])
        self.assertEqual({"minos-engine": "compile", "minos-domain": "test"}, self.dependencies(text))

    def test_the_strongest_scope_of_a_module_declared_twice_wins(self):
        text = pom_text("minos-x", [("minos-engine", "test", None), ("minos-engine", "compile", None)])
        self.assertEqual({"minos-engine": "compile"}, self.dependencies(text))

    def test_an_unresolved_group_is_refused(self):
        text = pom_text("minos-x", [("minos-engine", None, "${project.groupId}")])
        with self.assertRaises(RuntimeError) as raised:
            self.dependencies(text)
        self.assertIn("minos-x", str(raised.exception))
        self.assertIn("minos-engine", str(raised.exception))

    def test_a_look_alike_group_is_refused_instead_of_ignored_as_third_party(self):
        text = pom_text("minos-x", [("minos-engine", None, "com.minos.fake")])
        with self.assertRaises(RuntimeError) as raised:
            self.dependencies(text)
        self.assertIn("com.minos.fake", str(raised.exception))

    def test_a_third_party_artifact_is_not_internal(self):
        text = pom_text("minos-x", [("jackson-databind", None, "com.fasterxml.jackson.core")])
        self.assertEqual({}, self.dependencies(text))

    def test_an_unknown_internal_artifact_and_a_self_dependency_are_refused(self):
        with self.assertRaises(RuntimeError):
            self.dependencies(pom_text("minos-x", [("minos-unknown", None, None)]), group="com.minos")
        with self.assertRaises(RuntimeError):
            _MODULE.minos_dependencies("minos-engine", element(pom_text("minos-engine", [("minos-engine", None, None)])),
                                       "com.minos")

    def test_the_group_comes_from_the_root_pom(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text(f'<project xmlns="{POM_NAMESPACE}"><groupId>org.example</groupId></project>',
                                          encoding="utf-8")
            self.assertEqual("org.example", _MODULE.repository_group_id(root))
            (root / "pom.xml").write_text(f'<project xmlns="{POM_NAMESPACE}"></project>', encoding="utf-8")
            with self.assertRaises(RuntimeError):
                _MODULE.repository_group_id(root)

    def test_the_real_root_group_is_the_one_the_modules_use(self):
        self.assertEqual("com.minos", _MODULE.repository_group_id())


class HiddenDependencyRuleTest(unittest.TestCase):

    def refused(self, text, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_no_hidden_internal_dependencies("minos-x", element(text), "com.minos")
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def profile(self, body):
        return f"<profiles><profile><id>p</id>{body}</profile></profiles>"

    def internal(self):
        return "<dependency><groupId>com.minos</groupId><artifactId>minos-engine</artifactId></dependency>"

    def third_party(self):
        return "<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>"

    def test_an_internal_dependency_in_a_profile_is_refused(self):
        self.refused(pom_text("minos-x", profiles=self.profile(f"<dependencies>{self.internal()}</dependencies>")),
                     "minos-engine", "<profile>")

    def test_an_internal_dependency_in_dependency_management_is_refused(self):
        self.refused(pom_text("minos-x", management=f"<dependencyManagement><dependencies>{self.internal()}"
                                                    f"</dependencies></dependencyManagement>"),
                     "minos-engine", "<dependencyManagement>")

    def test_an_internal_dependency_in_profile_dependency_management_is_refused(self):
        body = f"<dependencyManagement><dependencies>{self.internal()}</dependencies></dependencyManagement>"
        self.refused(pom_text("minos-x", profiles=self.profile(body)), "minos-engine", "<profile> <dependencyManagement>")

    def test_an_unresolved_group_hidden_in_a_profile_is_refused(self):
        hidden = ("<dependency><groupId>${project.groupId}</groupId><artifactId>minos-engine</artifactId></dependency>")
        self.refused(pom_text("minos-x", profiles=self.profile(f"<dependencies>{hidden}</dependencies>")),
                     "minos-engine")

    def test_third_party_dependencies_at_the_same_places_are_accepted(self):
        for text in (pom_text("minos-x", profiles=self.profile(f"<dependencies>{self.third_party()}</dependencies>")),
                     pom_text("minos-x", management=f"<dependencyManagement><dependencies>{self.third_party()}"
                                                    f"</dependencies></dependencyManagement>")):
            _MODULE.check_no_hidden_internal_dependencies("minos-x", element(text), "com.minos")


class PomLayoutRuleTest(unittest.TestCase):

    def refused(self, text, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_pom_layout("minos-x", element(text))
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def compiler(self, configuration):
        return ("<plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                f"<configuration>{configuration}</configuration></plugin></plugins>")

    def in_profile(self, build_body):
        return f"<profiles><profile><id>p</id><build>{build_body}</build></profile></profiles>"

    def test_a_custom_source_directory_is_refused_at_the_root_and_in_a_profile(self):
        for tag in ("sourceDirectory", "testSourceDirectory"):
            with self.subTest(tag=tag, where="root"):
                self.refused(pom_text("minos-x", build=f"<build><{tag}>x</{tag}></build>"), tag)
            with self.subTest(tag=tag, where="profile"):
                self.refused(pom_text("minos-x", profiles=self.in_profile(f"<{tag}>x</{tag}>")), tag, "profile")

    def test_compiler_includes_and_excludes_are_refused_at_the_root_and_in_a_profile(self):
        for tag in ("includes", "excludes"):
            configuration = f"<{tag}><{tag[:-1]}>**/A.java</{tag[:-1]}></{tag}>"
            with self.subTest(tag=tag, where="root"):
                self.refused(pom_text("minos-x", build=f"<build>{self.compiler(configuration)}</build>"), tag)
            with self.subTest(tag=tag, where="profile"):
                self.refused(pom_text("minos-x", profiles=self.in_profile(self.compiler(configuration))), tag, "profile")

    def test_plugins_in_a_profile_are_accepted(self):
        _MODULE.check_pom_layout("minos-x", element(pom_text(
            "minos-x", profiles=self.in_profile("<plugins><plugin><artifactId>surefire</artifactId></plugin></plugins>"))))

    def test_a_pom_without_build_is_accepted(self):
        _MODULE.check_pom_layout("minos-x", element(pom_text("minos-x")))

    def test_a_compiler_configuration_without_filters_is_accepted(self):
        _MODULE.check_pom_layout("minos-x", element(pom_text(
            "minos-x", build=f"<build>{self.compiler('<release>24</release>')}</build>")))


class SourceBoundaryRuleTest(unittest.TestCase):
    """A2: the POM rules do not see sources, so a source naming an adapter class is refused."""

    ADAPTER_CLASS = "com.minos.storage.local.store.FileStore"

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        write_root_pom(self.root)
        self.write("minos-storage-local", "com.minos.storage.local.store", "FileStore", "public final class FileStore { "
                   "public static FileStore create() { return new FileStore(); } }")

    def write(self, module, package, name, body, imports=()):
        directory = self.root / module / "src" / "main" / "java" / Path(*package.split("."))
        directory.mkdir(parents=True, exist_ok=True)
        lines = "".join(f"import {line};\n" for line in imports)
        (directory / f"{name}.java").write_text(f"package {package};\n\n{lines}\n{body}\n", encoding="utf-8")

    def refused(self, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_source_boundaries(self.root)
        self.assertIn("A2 source boundary violated", str(raised.exception))
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_a_single_import_is_refused(self):
        self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { FileStore s; }", [self.ADAPTER_CLASS])
        self.refused("minos-cli/src/main/java/com/minos/cli/Cmd.java", self.ADAPTER_CLASS, "minos-storage-local")

    def test_a_static_import_is_refused(self):
        self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { }", [f"static {self.ADAPTER_CLASS}.create"])
        self.refused(self.ADAPTER_CLASS)

    def test_a_wildcard_import_of_a_package_holding_adapter_classes_is_refused(self):
        self.write("minos-api", "com.minos.api", "Facade", "class Facade { }", ["com.minos.storage.local.store.*"])
        self.refused("com.minos.storage.local.store.*")

    def test_a_fully_qualified_name_is_refused(self):
        self.write("minos-mcp", "com.minos.mcp", "Tool", f"class Tool {{ Object o = {self.ADAPTER_CLASS}.create(); }}")
        self.refused(self.ADAPTER_CLASS)

    def test_a_simple_name_from_a_package_shared_with_an_adapter_is_refused(self):
        self.write("minos-nexus", "com.minos.storage.local.store", "Intruder", "class Intruder { FileStore s; }")
        self.refused("minos-nexus")

    def test_a_mention_in_a_comment_or_a_string_literal_is_accepted(self):
        self.write("minos-cli", "com.minos.cli", "Cmd",
                   f'class Cmd {{ // {self.ADAPTER_CLASS}\n String s = "{self.ADAPTER_CLASS}"; /* FileStore */ }}')
        _MODULE.check_source_boundaries(self.root)

    def test_the_adapters_and_the_composition_root_may_name_adapter_classes(self):
        self.write("minos-bootstrap", "com.minos.bootstrap", "Composer", "class Composer { FileStore s; }",
                   [self.ADAPTER_CLASS])
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Scip", "class Scip { FileStore s; }",
                   [self.ADAPTER_CLASS])
        _MODULE.check_source_boundaries(self.root)

    def test_a_source_that_names_no_adapter_is_accepted(self):
        self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { }", ["java.util.List"])
        _MODULE.check_source_boundaries(self.root)


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


class GeneratedDocumentRuleTest(unittest.TestCase):

    GRAPH = {module: frozenset() for module in MODULES}

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.document = Path(directory.name) / "docs" / "module-dependencies.md"

    def test_a_missing_document_is_refused(self):
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_or_write_dependency_document(self.GRAPH, False, self.document)
        self.assertIn("missing", str(raised.exception))

    def test_a_stale_document_is_refused_and_a_regenerated_one_is_accepted(self):
        self.document.parent.mkdir(parents=True)
        self.document.write_text("stale\n", encoding="utf-8")
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_or_write_dependency_document(self.GRAPH, False, self.document)
        self.assertIn("stale", str(raised.exception))
        with contextlib.redirect_stdout(io.StringIO()):
            _MODULE.check_or_write_dependency_document(self.GRAPH, True, self.document)
        _MODULE.check_or_write_dependency_document(self.GRAPH, False, self.document)

    def test_the_document_shows_every_module_and_each_direct_edge(self):
        graph = dict(self.GRAPH, **{"minos-engine": frozenset({"minos-domain"})})
        text = _MODULE.render_dependency_document(graph)
        self.assertIn("minos_engine --> minos_domain", text)
        self.assertIn("| `minos-engine` | `minos-domain` |", text)


class PomRulesOnRealPomsTest(unittest.TestCase):
    """Witness mutations: the rules must also hold on the real POMs, not only on toy trees."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        shutil.copy(_MODULE.ROOT / "pom.xml", self.root / "pom.xml")
        for module in MODULES:
            (self.root / module).mkdir()
            shutil.copy(_MODULE.ROOT / module / "pom.xml", self.root / module / "pom.xml")

    def edit(self, module):
        ET.register_namespace("", POM_NAMESPACE)
        path = self.root / module / "pom.xml"
        tree = ET.parse(path)
        return path, tree, tree.getroot().find(f"{{{POM_NAMESPACE}}}dependencies")

    def add_dependency(self, module, artifact, scope=None):
        path, tree, dependencies = self.edit(module)
        dependency = ET.SubElement(dependencies, f"{{{POM_NAMESPACE}}}dependency")
        for tag, value in (("groupId", "com.minos"), ("artifactId", artifact), ("version", "1"), ("scope", scope)):
            if value:
                ET.SubElement(dependency, f"{{{POM_NAMESPACE}}}{tag}").text = value
        tree.write(path, encoding="utf-8", xml_declaration=True)

    def remove_dependency(self, module, artifact):
        path, tree, dependencies = self.edit(module)
        for dependency in list(dependencies):
            if dependency.findtext(f"{{{POM_NAMESPACE}}}artifactId") == artifact:
                dependencies.remove(dependency)
        tree.write(path, encoding="utf-8", xml_declaration=True)

    def rules(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return _MODULE.check_pom_rules(self.root)

    def refused(self, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            self.rules()
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_the_real_poms_pass(self):
        scoped = self.rules()
        self.assertEqual(set(MODULES), set(scoped))
        self.assertIn("minos-engine", scoped["minos-cli"])

    def test_a_surface_that_starts_depending_on_an_adapter_is_refused(self):
        self.add_dependency("minos-cli", "minos-storage-local")
        self.refused("A2 hexagonal boundary violated", "minos-cli -> minos-storage-local")

    def test_the_application_that_starts_depending_on_an_adapter_is_refused(self):
        self.add_dependency("minos-application", "minos-provider-scip")
        self.refused("minos-application -> minos-provider-scip")

    def test_a_surface_that_compiles_against_the_composition_root_is_refused(self):
        self.add_dependency("minos-api", "minos-bootstrap", "compile")
        self.refused("minos-api -> minos-bootstrap")

    def test_removing_a_permitted_dependency_is_accepted(self):
        self.remove_dependency("minos-runtime-local", "minos-domain")
        scoped = self.rules()
        self.assertNotIn("minos-domain", scoped["minos-runtime-local"])


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
        self.assertEqual({}, _MODULE.KNOWN_PACKAGE_CYCLES)
        with contextlib.redirect_stdout(io.StringIO()):
            cycles, packages = _MODULE.check_package_cycles()
        self.assertEqual(0, cycles)
        self.assertGreater(packages, 45)


ARC42 = """# Section 5

## 5.2 Détail des containers

### minos-domain
- **Responsabilité** : le noyau.
- **Dépendances** : aucune.

### minos-engine
- **Responsabilité** : les ports.
- **Dépendances** : `minos-domain`, `jackson 2.22`.

### minos-cli (optionnel)
- **Dépendances** : `minos-engine`, `minos-domain`.

### minos-app
- **Dépendances** : tous les modules.
"""


class ArchitectureDocumentationRuleTest(unittest.TestCase):
    """A9: the dependency lists of the current architecture document are checked against the POMs."""

    MODULES = ("minos-domain", "minos-engine", "minos-cli", "minos-app")
    GRAPH = {"minos-domain": frozenset(), "minos-engine": frozenset({"minos-domain"}),
             "minos-cli": frozenset({"minos-engine", "minos-domain"}),
             "minos-app": frozenset({"minos-domain", "minos-engine", "minos-cli"})}

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.document = self.root / "arc42.md"

    def check(self, text=ARC42, graph=None):
        self.document.write_text(text, encoding="utf-8")
        return _MODULE.check_architecture_documentation(graph or self.GRAPH, self.document, self.MODULES)

    def refused(self, text=ARC42, graph=None, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            self.check(text, graph)
        self.assertIn("A9", str(raised.exception))
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_a_document_that_matches_the_poms_passes_and_counts_its_sections(self):
        self.assertEqual(4, self.check())

    def test_a_dependency_the_pom_does_not_declare_is_refused_with_the_expected_line(self):
        wrong = ARC42.replace("`minos-engine`, `minos-domain`.", "`minos-engine`, `minos-domain`, `minos-app`.")
        self.refused(wrong, None, "minos-cli", "minos-app", "`minos-domain`, `minos-engine`")

    def test_a_dependency_the_pom_declares_but_the_document_omits_is_refused(self):
        self.refused(ARC42.replace("`minos-engine`, `minos-domain`.", "`minos-engine`."), None, "minos-cli", "minos-domain")

    def test_third_party_tokens_and_the_optional_suffix_are_ignored(self):
        self.assertEqual(4, self.check())

    def test_a_module_without_a_section_is_refused(self):
        self.refused(ARC42.replace("### minos-engine\n- **Responsabilité** : les ports.\n"
                                   "- **Dépendances** : `minos-domain`, `jackson 2.22`.\n", ""), None, "minos-engine")

    def test_a_section_without_a_dependencies_line_is_refused(self):
        self.refused(ARC42.replace("- **Dépendances** : `minos-domain`, `jackson 2.22`.\n", ""), None, "minos-engine")

    def test_a_section_for_a_module_that_does_not_exist_is_refused(self):
        self.refused(ARC42 + "\n### minos-ghost\n- **Dépendances** : `minos-domain`.\n", None, "minos-ghost")

    def test_all_the_modules_is_accepted_for_the_assembly_only(self):
        self.refused(ARC42.replace("`minos-engine`, `minos-domain`.", "tous les modules."), None, "minos-cli")

    def test_all_the_modules_requires_the_assembly_to_depend_on_them_all(self):
        graph = dict(self.GRAPH, **{"minos-app": frozenset({"minos-domain"})})
        self.refused(ARC42, graph, "minos-app")

    def test_a_missing_document_is_refused(self):
        with self.assertRaises(RuntimeError):
            _MODULE.check_architecture_documentation(self.GRAPH, self.root / "absent.md", self.MODULES)

    def test_the_current_document_matches_the_real_poms(self):
        scoped = _MODULE.check_pom_rules()
        graph = {module: frozenset(deps) for module, deps in scoped.items()}
        self.assertEqual(len(MODULES), _MODULE.check_architecture_documentation(graph))


class RuntimeLocalExportRuleTest(unittest.TestCase):
    """A10 — outside minos-runtime-local, only the exported types of its guarded packages may be named."""

    PREFIX = "com.minos.runtime.local"
    EXPORTED = frozenset({"process.CommandLocator", "sandbox.WorkerResourceContainment"})

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        write_root_pom(self.root)
        self.write("minos-runtime-local", f"{self.PREFIX}.process", "CommandLocator", "public final class CommandLocator { }")
        self.write("minos-runtime-local", f"{self.PREFIX}.process", "ProviderProcessEnvironment",
                   "public final class ProviderProcessEnvironment { }")
        self.write("minos-runtime-local", f"{self.PREFIX}.sandbox", "WorkerResourceContainment",
                   "public record WorkerResourceContainment() { public enum Disposition { NONE } }")
        self.write("minos-runtime-local", f"{self.PREFIX}.sandbox", "LinuxCgroupJob", "public final class LinuxCgroupJob { }")
        self.write("minos-runtime-local", f"{self.PREFIX}.distributed", "BundleStore", "public final class BundleStore { }")
        # Both exported types are named by another module: the ratchet has nothing to remove.
        self.write("minos-bootstrap", "com.minos.bootstrap", "Wiring", "class Wiring { }",
                   [f"{self.PREFIX}.process.CommandLocator", f"{self.PREFIX}.sandbox.WorkerResourceContainment"])

    def write(self, module, package, name, body, imports=(), kind="main"):
        directory = self.root / module / "src" / kind / "java" / Path(*package.split("."))
        directory.mkdir(parents=True, exist_ok=True)
        lines = "".join(f"import {line};\n" for line in imports)
        (directory / f"{name}.java").write_text(f"package {package};\n\n{lines}\n{body}\n", encoding="utf-8")

    def check(self, exported=None):
        return _MODULE.check_runtime_local_exports(self.root, exported=self.EXPORTED if exported is None else exported)

    def refused(self, *fragments, exported=None):
        with self.assertRaises(RuntimeError) as raised:
            self.check(exported)
        self.assertIn("A10 runtime-local export", str(raised.exception))
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_an_exported_type_and_its_nested_type_are_accepted(self):
        self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { }",
                   [f"{self.PREFIX}.process.CommandLocator", f"{self.PREFIX}.sandbox.WorkerResourceContainment.Disposition",
                    f"static {self.PREFIX}.process.CommandLocator.find"])
        self.assertEqual(2, self.check())

    def test_a_single_import_of_a_type_that_is_not_exported_is_refused(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Leak", "class Leak { }",
                   [f"{self.PREFIX}.process.ProviderProcessEnvironment"])
        self.refused("minos-provider-scip/src/main/java/com/minos/adapter/scip/Leak.java",
                     f"{self.PREFIX}.process.ProviderProcessEnvironment")

    def test_a_static_import_of_a_member_of_a_type_that_is_not_exported_is_refused(self):
        self.write("minos-bootstrap", "com.minos.bootstrap", "Leak", "class Leak { }",
                   [f"static {self.PREFIX}.process.ProviderProcessEnvironment.sanitize"])
        self.refused(f"{self.PREFIX}.process.ProviderProcessEnvironment")

    def test_a_wildcard_import_of_a_guarded_package_is_refused(self):
        self.write("minos-bootstrap", "com.minos.bootstrap", "Leak", "class Leak { }", [f"{self.PREFIX}.sandbox.*"])
        self.refused(f"{self.PREFIX}.sandbox.*")

    def test_a_fully_qualified_name_of_a_type_that_is_not_exported_is_refused(self):
        self.write("minos-cli", "com.minos.cli", "Leak",
                   f"class Leak {{ Object job = {self.PREFIX}.sandbox.LinuxCgroupJob.class; }}")
        self.refused(f"{self.PREFIX}.sandbox.LinuxCgroupJob")

    def test_a_test_source_is_held_to_the_same_list(self):
        self.write("minos-cli", "com.minos.cli", "LeakTest", "class LeakTest { }",
                   [f"{self.PREFIX}.sandbox.LinuxCgroupJob"], kind="test")
        self.refused("minos-cli/src/test/java/com/minos/cli/LeakTest.java", f"{self.PREFIX}.sandbox.LinuxCgroupJob")

    def test_a_reserved_member_is_refused_in_a_source_that_uses_the_module(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Leak",
                   "class Leak { void run(Object executor) { executor.executeSandboxed(null, null); } }",
                   [f"{self.PREFIX}.process.CommandLocator"])
        self.refused("reserved member executeSandboxed")

    def test_a_reserved_word_in_a_source_that_does_not_use_the_module_is_accepted(self):
        self.write("minos-cli", "com.minos.cli", "Unrelated", "class Unrelated { void executeSandboxed() { } }")
        self.assertEqual(2, self.check())

    def test_a_mention_in_a_comment_or_a_string_literal_is_accepted(self):
        self.write("minos-cli", "com.minos.cli", "Cmd",
                   f'class Cmd {{ // {self.PREFIX}.sandbox.LinuxCgroupJob\n'
                   f' String s = "{self.PREFIX}.process.ProviderProcessEnvironment"; }}')
        self.assertEqual(2, self.check())

    def test_the_module_itself_and_its_unguarded_packages_are_not_restricted(self):
        self.write("minos-runtime-local", self.PREFIX, "Worker", "class Worker { }",
                   [f"{self.PREFIX}.sandbox.LinuxCgroupJob", f"{self.PREFIX}.process.ProviderProcessEnvironment"])
        self.write("minos-bootstrap", "com.minos.bootstrap", "Remote", "class Remote { }",
                   [f"{self.PREFIX}.distributed.BundleStore"])
        self.assertEqual(2, self.check())

    def test_the_acl_package_is_guarded_and_exports_nothing(self):
        # SH-08: the platform access control of private storage is reached through ServiceLoader only.
        self.write("minos-runtime-local", f"{self.PREFIX}.acl", "WindowsIcacls", "public final class WindowsIcacls { }")
        self.write("minos-runtime-local", f"{self.PREFIX}.sandbox", "Grants", "final class Grants { }",
                   [f"{self.PREFIX}.acl.WindowsIcacls"])
        self.assertEqual(2, self.check())
        self.write("minos-engine", "com.minos.io", "Leak", "final class Leak { }", [f"{self.PREFIX}.acl.WindowsIcacls"])
        self.refused("minos-engine/src/main/java/com/minos/io/Leak.java", f"{self.PREFIX}.acl.WindowsIcacls")

    def test_an_export_entry_that_names_no_existing_type_is_refused(self):
        self.refused("does not exist", "process.Gone", exported=self.EXPORTED | {"process.Gone"})

    def test_an_export_entry_no_other_module_names_any_more_is_refused(self):
        self.write("minos-runtime-local", f"{self.PREFIX}.workspace", "Markers", "public final class Markers { }")
        self.refused("ratchet", "workspace.Markers", exported=self.EXPORTED | {"workspace.Markers"})

    def test_the_real_tree_names_only_exported_types(self):
        self.assertEqual(len(_MODULE.RUNTIME_LOCAL_EXPORTED_TYPES), _MODULE.check_runtime_local_exports())


class ServiceLoaderClassLoaderTest(unittest.TestCase):
    """A16 — production ServiceLoader.load calls pass an explicit class loader (AUD-ARC-04)."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def source(self, scope: str, name: str, body: str) -> None:
        directory = self.root / "minos-a" / "src" / scope / "java" / "com" / "example"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{name}.java").write_text(f"package com.example;\n\nclass {name} {{\n{body}\n}}\n",
                                                encoding="utf-8")

    def check(self):
        return _MODULE.check_service_loader_class_loaders(self.root, ("minos-a",))

    def test_a_one_argument_call_in_production_is_refused_with_file_and_line(self):
        self.source("main", "Implicit", "    Object o = ServiceLoader.load(Port.class);")
        with self.assertRaises(RuntimeError) as raised:
            self.check()
        self.assertIn("A16", str(raised.exception))
        self.assertIn("minos-a/src/main/java/com/example/Implicit.java:4", str(raised.exception))

    def test_a_two_argument_call_is_accepted(self):
        self.source("main", "Explicit",
                    "    Object o = ServiceLoader.load(Port.class,\n            Port.class.getClassLoader());")
        self.assertEqual(1, self.check())

    def test_nested_arguments_are_not_counted_as_a_loader(self):
        self.source("main", "Nested", "    Object o = ServiceLoader.load(pick(Port.class, Other.class));")
        with self.assertRaises(RuntimeError):
            self.check()

    def test_comments_literals_and_tests_do_not_count(self):
        self.source("main", "Quiet", "    // ServiceLoader.load(Port.class)\n    String s = \"ServiceLoader.load(X.class)\";")
        self.source("test", "InTest", "    Object o = ServiceLoader.load(Port.class);")
        self.assertEqual(0, self.check())

    def test_the_real_tree_passes_a_loader_at_every_site(self):
        self.assertEqual(5, _MODULE.check_service_loader_class_loaders())


class RatchetTreeTestCase(unittest.TestCase):
    """A temporary reactor tree (root pom.xml with groupId com.minos) for the A11-A14 ratchets."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        write_root_pom(self.root)

    def write(self, module, package, name, body, imports=(), kind="main"):
        directory = self.root / module / "src" / kind / "java" / Path(*package.split("."))
        directory.mkdir(parents=True, exist_ok=True)
        lines = "".join(f"import {line};\n" for line in imports)
        (directory / f"{name}.java").write_text(f"package {package};\n\n{lines}\n{body}\n", encoding="utf-8")

    def refused(self, action, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            action()
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))
        return str(raised.exception)


class EngineTypesNamedBySurfaceRuleTest(RatchetTreeTestCase):
    """A11 — each surface names only the minos-engine types of its list (AUD-ARC-01, ADR 0058 section 3)."""

    LISTED = {"minos-cli": {"query.SymbolResult": 1}}

    def setUp(self):
        super().setUp()
        self.write("minos-engine", "com.minos.query", "SymbolResult", "public record SymbolResult() { }")
        self.write("minos-engine", "com.minos.hosted", "HostedRole", "public enum HostedRole { OWNER }")
        self.write("minos-cli", "com.minos.cli", "Search", "final class Search { SymbolResult r; }",
                   ["com.minos.query.SymbolResult"])

    def check(self, listed=None):
        return _MODULE.check_engine_types_named_by_surfaces(self.root, listed=self.LISTED if listed is None else listed)

    def test_a_second_file_naming_a_listed_type_is_refused_with_both_counts_and_files(self):
        # AUD-ARC-01: the ratchet counts the files that use a type, not only the type.
        self.write("minos-cli", "com.minos.cli.command", "Again", "final class Again { SymbolResult r; }",
                   ["com.minos.query.SymbolResult"])
        message = self.refused(self.check, "A11", "minos-cli", "query.SymbolResult", "listed for 1 file(s)",
                               "used by 2", "minos-cli/src/main/java/com/minos/cli/Search.java",
                               "minos-cli/src/main/java/com/minos/cli/command/Again.java")
        self.assertNotIn("no longer named", message)

    def test_a_count_above_the_observed_files_is_refused_with_the_count_to_write(self):
        listed = {"minos-cli": {"query.SymbolResult": 2}}
        self.refused(lambda: self.check(listed), "A11", "query.SymbolResult", "listed for 2 file(s)", "used by 1",
                     "write 1")

    def test_moving_a_use_to_another_file_keeps_the_count(self):
        self.write("minos-cli", "com.minos.cli", "Search", "final class Search { }")
        self.write("minos-cli", "com.minos.cli", "Moved", "final class Moved { SymbolResult r; }",
                   ["com.minos.query.SymbolResult"])
        self.assertEqual(1, self.check()["minos-cli"])

    def test_a_conforming_tree_is_accepted_with_the_count_per_surface(self):
        self.assertEqual({"minos-api": 0, "minos-cli": 1, "minos-mcp": 0, "minos-nexus": 0}, self.check())

    def test_an_imported_type_outside_the_list_is_refused_naming_surface_file_and_type(self):
        self.write("minos-cli", "com.minos.cli", "Leak", "final class Leak { HostedRole r; }",
                   ["com.minos.hosted.HostedRole"])
        self.refused(self.check, "A11", "minos-cli", "minos-cli/src/main/java/com/minos/cli/Leak.java",
                     "com.minos.hosted.HostedRole")

    def test_a_qualified_name_outside_the_list_is_refused(self):
        self.write("minos-mcp", "com.minos.mcp", "Leak", "final class Leak { Object r = com.minos.hosted.HostedRole.OWNER; }")
        self.refused(self.check, "A11", "minos-mcp", "com.minos.hosted.HostedRole")

    def test_a_nested_type_counts_as_its_listed_top_level_type(self):
        self.write("minos-cli", "com.minos.cli", "Nested", "final class Nested { }", ["com.minos.query.SymbolResult.Kind"])
        self.assertEqual(1, self.check({"minos-cli": {"query.SymbolResult": 2}})["minos-cli"])

    def test_a_wildcard_import_of_an_engine_package_is_refused(self):
        self.write("minos-api", "com.minos.api", "Wide", "final class Wide { }", ["com.minos.hosted.*"])
        self.refused(self.check, "A11", "wildcard", "com.minos.hosted.*")

    def test_a_dead_entry_is_refused(self):
        listed = {"minos-cli": {"query.SymbolResult": 1, "hosted.HostedRole": 1}}
        self.refused(lambda: self.check(listed), "A11", "hosted.HostedRole", "no longer named")

    def test_an_entry_naming_no_existing_type_is_refused(self):
        listed = {"minos-cli": {"query.SymbolResult": 1, "query.Gone": 1}}
        self.refused(lambda: self.check(listed), "A11", "query.Gone", "names no production type")

    def test_a_count_that_is_not_a_positive_integer_is_refused(self):
        listed = {"minos-cli": {"query.SymbolResult": 0}}
        self.refused(lambda: self.check(listed), "A11", "query.SymbolResult", "positive")

    def test_comments_literals_and_test_sources_do_not_count(self):
        self.write("minos-cli", "com.minos.cli", "Quiet",
                   'final class Quiet { // com.minos.hosted.HostedRole\n String s = "com.minos.hosted.HostedRole"; }')
        self.write("minos-cli", "com.minos.cli", "QuietTest", "final class QuietTest { }",
                   ["com.minos.hosted.HostedRole"], kind="test")
        self.assertEqual(1, self.check()["minos-cli"])

    def test_a_type_removed_by_the_first_lot_is_refused_again_against_the_real_cli_list(self):
        # AUD-ARC-01, lot 1: the remote indexing left the CLI; naming its engine port again fails A11.
        cli = _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
        imports = []
        for entry in sorted(set(cli) | {"remote.RemoteIndexingRuntime"}):
            package, name = entry.rsplit(".", 1)
            self.write("minos-engine", "com.minos." + package, name, f"public interface {name} {{ }}")
            imports.append(f"com.minos.{entry}")
        self.write("minos-cli", "com.minos.cli", "Search", "final class Search { }",
                   [name for name in imports if name != "com.minos.remote.RemoteIndexingRuntime"])
        listed = {"minos-cli": {entry: 1 for entry in cli}}
        self.assertEqual(len(cli), self.check(listed)["minos-cli"])

        self.write("minos-cli", "com.minos.cli.operations", "RemoteLeak", "final class RemoteLeak { }",
                   ["com.minos.remote.RemoteIndexingRuntime"])
        message = self.refused(lambda: self.check(listed), "A11", "minos-cli",
                               "minos-cli/src/main/java/com/minos/cli/operations/RemoteLeak.java",
                               "com.minos.remote.RemoteIndexingRuntime")
        self.assertNotIn("no longer named", message)

    def test_the_real_tree_names_only_listed_engine_types(self):
        counts = _MODULE.check_engine_types_named_by_surfaces()
        self.assertEqual({surface: len(types) for surface, types in _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE.items()},
                         counts)

    def test_a_new_cli_file_importing_a_listed_type_is_refused_against_the_real_cli_list(self):
        # A type ratchet lets a new CLI file import a type already listed; the file count refuses it.
        cli = _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
        self.assertEqual(1, cli["runtime.MinosVersion"])
        self.write("minos-engine", "com.minos.runtime", "MinosVersion", "public final class MinosVersion { }")
        self.write("minos-cli", "com.minos.cli", "MinosLauncher", "final class MinosLauncher { }",
                   ["com.minos.runtime.MinosVersion"])
        listed = {"minos-cli": {"runtime.MinosVersion": 1}}
        self.write("minos-cli", "com.minos.cli", "Search", "final class Search { }")
        self.assertEqual(1, self.check(listed)["minos-cli"])
        self.write("minos-cli", "com.minos.cli.command", "NewCommand", "final class NewCommand { }",
                   ["com.minos.runtime.MinosVersion"])
        self.refused(lambda: self.check(listed), "A11", "runtime.MinosVersion", "used by 2",
                     "minos-cli/src/main/java/com/minos/cli/command/NewCommand.java")

    def test_no_surface_uses_the_transverse_core_moved_to_the_domain(self):
        # AUD-ARC-01, lot 6: these types live in minos-domain; a surface uses them without an A11 entry.
        moved = {"diagnostics.PublicErrorMessages", "registry.DegradedEntry", "registry.ProjectPathMapping",
                 "registry.UnreadableRegistryException"}
        for surface, types in _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE.items():
            self.assertFalse(moved & set(types), surface)

    def test_the_cli_no_longer_uses_the_host_mechanisms_of_the_engine(self):
        # AUD-ARC-01, lot 2: minos doctor reads them through HostDiagnosticsService.
        cli = _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
        self.assertNotIn("io.PrivateLocalStorage", cli)
        self.assertNotIn("runtime.WorkerSandboxProbe", cli)

    def test_the_surfaces_read_git_through_the_application_and_keep_only_the_published_read_models(self):
        # AUD-ARC-01, lot 4: Git goes through ProjectGitQuery, the API symbol query is built from the application, and
        # the only query.* entries are the read models the ProjectSymbolQuery port returns (ADR 0057, point 6).
        published = {"query.RelationshipResult", "query.SymbolResult", "query.UsageResult"}
        for surface, types in _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE.items():
            self.assertNotIn("git.GitIntelligence", types, surface)
            self.assertLessEqual({name for name in types if name.startswith("query.")}, published, surface)
        api = _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE["minos-api"]
        self.assertNotIn("registry.ProjectRegistry", api)
        self.assertNotIn("store.CodeKnowledgeSnapshotStore", api)

    def test_no_surface_uses_the_hosted_control_plane_of_the_engine(self):
        # AUD-ARC-01, lot 3: the surfaces reach the team control plane through TeamControlPlane.
        for surface, types in _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE.items():
            self.assertEqual([], [entry for entry in types if entry.startswith("hosted.")], surface)

    def test_the_nexus_export_no_longer_uses_the_engine(self):
        # AUD-ARC-01, lot 5: the export goes through KnowledgeExportUseCase, and the CLI passes the application.
        self.assertEqual({}, _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE["minos-nexus"])
        cli = _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
        self.assertNotIn("registry.ProjectRegistry", cli)
        self.assertNotIn("store.CodeKnowledgeSnapshotStore", cli)

    def test_an_engine_type_back_in_nexus_is_refused_against_the_real_list(self):
        self.write("minos-engine", "com.minos.store", "CodeKnowledgeSnapshot", "public record CodeKnowledgeSnapshot() { }")
        self.write("minos-nexus", "com.minos.nexus", "NexusExportService", "final class NexusExportService { }",
                   ["com.minos.store.CodeKnowledgeSnapshot"])
        listed = dict(self.LISTED, **{"minos-nexus": _MODULE.ENGINE_TYPES_NAMED_BY_SURFACE["minos-nexus"]})
        self.refused(lambda: self.check(listed), "A11", "minos-nexus",
                     "minos-nexus/src/main/java/com/minos/nexus/NexusExportService.java",
                     "com.minos.store.CodeKnowledgeSnapshot")


class MinosApplicationAccessorRuleTest(RatchetTreeTestCase):
    """A11 — a MinosApplication accessor called by a surface uses the engine type it returns (AUD-ARC-01)."""

    APPLICATION = """public final class MinosApplication {
    public static MinosApplication open(java.nio.file.Path home) { return null; }
    public String storageBackendId() { return ""; }
    public CodeKnowledgeSnapshotStore snapshotStore() { return null; }
    public GitIntelligence gitIntelligence() { return null; }
    public Optional<HostedControlPlaneService> hostedControlPlaneService() { return Optional.empty(); }
    public SnapshotStager snapshotStager() { return null; }
    public IndexerRegistry indexerRegistry(String providerOverride) { return null; }
    private GitIntelligence hidden() { return null; }
}"""

    def setUp(self):
        super().setUp()
        for package, name in (("store", "CodeKnowledgeSnapshotStore"), ("git", "GitIntelligence"),
                              ("hosted", "HostedControlPlaneService"), ("orchestration", "IndexingRuntimePorts"),
                              ("orchestration", "IndexerRegistry")):
            self.write("minos-engine", f"com.minos.{package}", name, f"public interface {name} {{ }}")
        self.write("minos-application", "com.minos.application", "MinosApplication", self.APPLICATION,
                   ["com.minos.store.CodeKnowledgeSnapshotStore", "com.minos.git.GitIntelligence",
                    "com.minos.hosted.HostedControlPlaneService",
                    "com.minos.orchestration.IndexingRuntimePorts.SnapshotStager",
                    "com.minos.orchestration.IndexerRegistry", "java.util.Optional"])

    def surface(self, module, name, body, imports=()):
        self.write(module, f"com.minos.{module.removeprefix('minos-')}", name, body, imports)

    def check(self, listed):
        return _MODULE.check_engine_types_named_by_surfaces(self.root, listed=listed)

    def test_the_accessors_returning_an_engine_type_are_read_from_the_application_source(self):
        self.assertEqual({"snapshotStore": (0, "store.CodeKnowledgeSnapshotStore"),
                          "gitIntelligence": (0, "git.GitIntelligence"),
                          "hostedControlPlaneService": (0, "hosted.HostedControlPlaneService"),
                          "snapshotStager": (0, "orchestration.IndexingRuntimePorts"),
                          "indexerRegistry": (1, "orchestration.IndexerRegistry")},
                         _MODULE.minos_application_accessors(self.root, "com.minos"))

    def test_an_accessor_called_without_naming_the_type_is_refused(self):
        self.surface("minos-api", "Reader", "final class Reader { Reader(MinosApplication app) { use(app.snapshotStore()); } }",
                     ["com.minos.application.MinosApplication"])
        self.refused(lambda: self.check({}), "A11", "minos-api", "minos-api/src/main/java/com/minos/api/Reader.java",
                     "com.minos.store.CodeKnowledgeSnapshotStore")

    def test_an_accessor_call_is_accepted_when_the_type_is_listed_for_the_file(self):
        self.surface("minos-api", "Reader", "final class Reader { Reader(MinosApplication app) { use(app.snapshotStore()); } }",
                     ["com.minos.application.MinosApplication"])
        self.assertEqual(1, self.check({"minos-api": {"store.CodeKnowledgeSnapshotStore": 1}})["minos-api"])

    def test_replacing_the_import_by_var_keeps_the_count(self):
        listed = {"minos-cli": {"git.GitIntelligence": 1}}
        self.surface("minos-cli", "Activity", "final class Activity { void r(MinosApplication app) { "
                     "GitIntelligence g = app.gitIntelligence(); } }",
                     ["com.minos.application.MinosApplication", "com.minos.git.GitIntelligence"])
        self.assertEqual(1, self.check(listed)["minos-cli"])
        self.surface("minos-cli", "Activity", "final class Activity { void r(MinosApplication app) { "
                     "var g = app.gitIntelligence(); } }", ["com.minos.application.MinosApplication"])
        self.assertEqual(1, self.check(listed)["minos-cli"])

    def test_a_method_reference_counts_as_a_use(self):
        self.surface("minos-cli", "Runner", "final class Runner { Object s = defer(MinosApplication::gitIntelligence); }",
                     ["com.minos.application.MinosApplication"])
        self.refused(lambda: self.check({}), "A11", "minos-cli", "com.minos.git.GitIntelligence")

    def test_a_homonym_with_another_arity_is_ignored(self):
        self.surface("minos-cli", "Runner", "final class Runner { void r(Builder b) { b.gitIntelligence(() -> null); "
                     "b.snapshotStore(1, 2); } }")
        self.assertEqual(0, self.check({})["minos-cli"])

    def test_an_accessor_with_a_parameter_is_matched_by_its_arity(self):
        self.surface("minos-mcp", "Registry", "final class Registry { Object r(MinosApplication app) { "
                     "return app.indexerRegistry(\"scip\"); } }", ["com.minos.application.MinosApplication"])
        self.refused(lambda: self.check({}), "A11", "minos-mcp", "com.minos.orchestration.IndexerRegistry")

    def test_a_generic_return_and_a_nested_return_resolve_to_their_engine_type(self):
        self.surface("minos-cli", "Team", "final class Team { Object t(MinosApplication app) { "
                     "return app.hostedControlPlaneService().orElseThrow(); } "
                     "Object s(MinosApplication app) { return app.snapshotStager(); } }",
                     ["com.minos.application.MinosApplication"])
        listed = {"minos-cli": {"hosted.HostedControlPlaneService": 1, "orchestration.IndexingRuntimePorts": 1}}
        self.assertEqual(2, self.check(listed)["minos-cli"])

    def test_a_static_method_a_private_method_and_a_jdk_return_are_not_accessors(self):
        accessors = _MODULE.minos_application_accessors(self.root, "com.minos")
        self.assertNotIn("open", accessors)
        self.assertNotIn("hidden", accessors)
        self.assertNotIn("storageBackendId", accessors)

    def test_an_accessor_call_in_a_comment_a_literal_or_a_test_does_not_count(self):
        self.surface("minos-cli", "Quiet", 'final class Quiet { // app.snapshotStore()\n String s = "app.gitIntelligence()"; }')
        self.write("minos-cli", "com.minos.cli", "QuietTest", "final class QuietTest { Object o = app.snapshotStore(); }",
                   kind="test")
        self.assertEqual(0, self.check({})["minos-cli"])

    def test_a_missing_application_source_fails_closed(self):
        (self.root / "minos-application" / "src" / "main" / "java" / "com" / "minos" / "application"
         / "MinosApplication.java").unlink()
        self.write("minos-application", "com.minos.application", "Other", "final class Other { }")
        self.refused(lambda: self.check({}), "A11", "MinosApplication")

    def test_the_real_application_exposes_the_accessors_the_surfaces_call(self):
        accessors = _MODULE.minos_application_accessors()
        for name, engine_type in (("projectRegistry", "registry.ProjectRegistry"),
                                  ("snapshotStore", "store.CodeKnowledgeSnapshotStore"),
                                  ("gitIntelligence", "git.GitIntelligence"),
                                  ("hostedControlPlaneService", "hosted.HostedControlPlaneService"),
                                  ("snapshotStager", "orchestration.IndexingRuntimePorts")):
            self.assertEqual((0, engine_type), accessors[name])


class ProcessLauncherRuleTest(RatchetTreeTestCase):
    """A12 — in the domain, the engine and the application, only listed classes launch a process (AUD-ARC-05)."""

    LISTED = {"minos-engine/io.PrivateLocalStorage": 1}

    def setUp(self):
        super().setUp()
        self.write("minos-engine", "com.minos.io", "PrivateLocalStorage",
                   'public final class PrivateLocalStorage { Object p = new ProcessBuilder("icacls.exe")'
                   '.redirectOutput(ProcessBuilder.Redirect.DISCARD); }')

    def check(self, listed=None):
        return _MODULE.check_process_launchers(self.root, listed=self.LISTED if listed is None else listed)

    def test_the_listed_launcher_is_accepted(self):
        self.assertEqual(1, self.check())

    def test_a_further_site_in_the_listed_class_is_refused_with_both_counts(self):
        # AUD-ARC-05: a third icacls call in PrivateLocalStorage passed the class ratchet.
        self.write("minos-engine", "com.minos.io", "PrivateLocalStorage",
                   'public final class PrivateLocalStorage { Object p = new ProcessBuilder("icacls.exe"); '
                   'Object q = new ProcessBuilder("icacls.exe", "/save"); }')
        self.refused(self.check, "A12", "minos-engine/io.PrivateLocalStorage", "listed with 1 launch site(s)",
                     "has 2")

    def test_a_count_above_the_observed_sites_is_refused_with_the_count_to_write(self):
        self.refused(lambda: self.check({"minos-engine/io.PrivateLocalStorage": 2}), "A12", "listed with 2",
                     "has 1", "write 1")

    def test_a_constructor_reference_a_pipeline_and_runtime_exec_are_sites_a_redirect_is_not(self):
        self.write("minos-engine", "com.minos.io", "PrivateLocalStorage",
                   "public final class PrivateLocalStorage { Object f = ProcessBuilder::new; "
                   "Object g = ProcessBuilder.startPipeline(java.util.List.of()); "
                   "Object r = ProcessBuilder.Redirect.DISCARD; "
                   'void e() throws Exception { Runtime.getRuntime().exec(new String[] {"x"}); } }')
        self.assertEqual(1, self.check({"minos-engine/io.PrivateLocalStorage": 3}))

    def test_a_class_naming_only_the_redirect_is_still_a_launcher(self):
        self.write("minos-engine", "com.minos.io", "Redirects",
                   "final class Redirects { Object r = ProcessBuilder.Redirect.DISCARD; }")
        self.refused(self.check, "A12", "minos-engine/io.Redirects")
        self.assertEqual(2, self.check({**self.LISTED, "minos-engine/io.Redirects": 0}))

    def test_a_negative_count_is_refused(self):
        self.refused(lambda: self.check({"minos-engine/io.PrivateLocalStorage": -1}), "A12", "not a count")

    def test_a_process_builder_in_an_unlisted_engine_class_is_refused(self):
        self.write("minos-engine", "com.minos.io", "Second", 'final class Second { Object p = new ProcessBuilder("x"); }')
        self.refused(self.check, "A12", "minos-engine/src/main/java/com/minos/io/Second.java", "minos-engine/io.Second")

    def test_runtime_exec_in_the_application_is_refused(self):
        self.write("minos-application", "com.minos.application", "Exec",
                   'final class Exec { void r() throws Exception { Runtime.getRuntime().exec(new String[] {"x"}); } }')
        self.refused(self.check, "A12", "minos-application/application.Exec")

    def test_an_entry_that_launches_nothing_any_more_is_refused(self):
        self.write("minos-engine", "com.minos.io", "PrivateLocalStorage", "public final class PrivateLocalStorage { }")
        self.refused(self.check, "A12", "minos-engine/io.PrivateLocalStorage", "launches no process any more")

    def test_a_mention_in_a_comment_or_literal_and_the_runtime_adapter_are_accepted(self):
        self.write("minos-domain", "com.minos.domain", "Doc",
                   'final class Doc { // new ProcessBuilder()\n String s = "Runtime.getRuntime().exec"; }')
        self.write("minos-runtime-local", "com.minos.runtime.local", "Runner",
                   'final class Runner { Object p = new ProcessBuilder("x"); }')
        self.assertEqual(1, self.check())

    def test_the_real_tree_has_only_the_listed_launchers(self):
        self.assertEqual(len(_MODULE.PROCESS_LAUNCHERS), _MODULE.check_process_launchers())

    def test_the_real_tree_launches_no_process_in_the_inner_layers(self):
        # SH-08: the icacls launches of private storage live in minos-runtime-local.
        self.assertEqual({}, dict(_MODULE.PROCESS_LAUNCHERS))

    def test_icacls_back_in_private_storage_is_refused_against_the_real_empty_list(self):
        self.write("minos-engine", "com.minos.io", "PrivateLocalStorage",
                   'public final class PrivateLocalStorage { Object p = new ProcessBuilder("icacls.exe"); }')
        self.refused(lambda: self.check(dict(_MODULE.PROCESS_LAUNCHERS)), "A12",
                     "minos-engine/io.PrivateLocalStorage", "is not in PROCESS_LAUNCHERS")


class AdapterTypesNamedByAdapterRuleTest(RatchetTreeTestCase):
    """A13 — an adapter names another adapter's types only through the list of the couple (AUD-ARC-08)."""

    COUPLE = ("minos-storage-postgresql", "minos-storage-local")
    LISTED = {COUPLE: {"storage.local.store.SnapshotCodec": 1}}

    def setUp(self):
        super().setUp()
        self.write("minos-storage-local", "com.minos.storage.local.store", "SnapshotCodec", "public interface SnapshotCodec { }")
        self.write("minos-storage-local", "com.minos.storage.local.store", "SnapshotIntegrityService",
                   "public final class SnapshotIntegrityService { }")
        self.write("minos-storage-postgresql", "com.minos.storage.postgresql", "Store", "final class Store { SnapshotCodec c; }",
                   ["com.minos.storage.local.store.SnapshotCodec"])

    def check(self, listed=None):
        return _MODULE.check_adapter_types_named_by_adapters(self.root, listed=self.LISTED if listed is None else listed)

    def test_a_conforming_tree_is_accepted(self):
        self.assertEqual(1, self.check())

    def test_an_imported_type_outside_the_list_is_refused_naming_both_modules(self):
        self.write("minos-storage-postgresql", "com.minos.storage.postgresql", "Leak",
                   "final class Leak { SnapshotIntegrityService s; }",
                   ["com.minos.storage.local.store.SnapshotIntegrityService"])
        self.refused(self.check, "A13", "minos-storage-postgresql -> minos-storage-local",
                     "com.minos.storage.local.store.SnapshotIntegrityService")

    def test_a_qualified_name_without_import_is_refused_like_an_import(self):
        self.write("minos-storage-postgresql", "com.minos.storage.postgresql", "Leak",
                   "final class Leak { Object s = new com.minos.storage.local.store.SnapshotIntegrityService(); }")
        self.refused(self.check, "A13", "minos-storage-postgresql -> minos-storage-local",
                     "com.minos.storage.local.store.SnapshotIntegrityService")

    def test_a_couple_absent_from_the_table_is_refused(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Leak", "final class Leak { SnapshotCodec c; }",
                   ["com.minos.storage.local.store.SnapshotCodec"])
        self.refused(self.check, "A13", "minos-provider-scip -> minos-storage-local")

    def test_a_dead_entry_is_refused(self):
        listed = {self.COUPLE: {"storage.local.store.SnapshotCodec": 1, "storage.local.store.SnapshotIntegrityService": 1}}
        self.refused(lambda: self.check(listed), "A13", "storage.local.store.SnapshotIntegrityService", "no longer named")

    def test_a_second_file_naming_a_listed_type_is_refused_with_both_counts(self):
        # AUD-ARC-08: a new adapter file naming an already listed type passed the type ratchet.
        self.write("minos-storage-postgresql", "com.minos.storage.postgresql.snapshot", "Payload",
                   "final class Payload { SnapshotCodec c; }", ["com.minos.storage.local.store.SnapshotCodec"])
        self.refused(self.check, "A13", "minos-storage-postgresql -> minos-storage-local",
                     "storage.local.store.SnapshotCodec", "listed for 1 file(s)", "used by 2",
                     "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/snapshot/Payload.java")

    def test_a_count_above_the_observed_files_is_refused(self):
        listed = {self.COUPLE: {"storage.local.store.SnapshotCodec": 3}}
        self.refused(lambda: self.check(listed), "A13", "listed for 3 file(s)", "used by 1", "write 1")

    def test_a_new_scip_file_naming_the_listed_command_locator_is_refused_against_the_real_list(self):
        couple = ("minos-provider-scip", "minos-runtime-local")
        listed = {couple: {"runtime.local.process.CommandLocator": 1}}
        self.write("minos-runtime-local", "com.minos.runtime.local.process", "CommandLocator",
                   "public final class CommandLocator { }")
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Plan", "final class Plan { CommandLocator l; }",
                   ["com.minos.runtime.local.process.CommandLocator"])
        self.write("minos-storage-postgresql", "com.minos.storage.postgresql", "Store", "final class Store { }")
        self.assertEqual(1, self.check(listed))
        self.write("minos-provider-scip", "com.minos.adapter.scip.go", "GoPlan", "final class GoPlan { CommandLocator l; }",
                   ["com.minos.runtime.local.process.CommandLocator"])
        self.refused(lambda: self.check(listed), "A13", "minos-provider-scip -> minos-runtime-local", "used by 2")
        self.assertEqual(10, _MODULE.ADAPTER_TYPES_NAMED_BY_ADAPTER[couple]["runtime.local.process.CommandLocator"])

    def test_a_table_key_that_is_not_a_couple_of_adapters_is_refused(self):
        listed = {**self.LISTED, ("minos-cli", "minos-storage-local"): {}}
        self.refused(lambda: self.check(listed), "A13", "minos-cli -> minos-storage-local", "not a couple of adapters")

    def test_the_real_tree_names_only_listed_adapter_types(self):
        self.assertEqual(sum(len(types) for types in _MODULE.ADAPTER_TYPES_NAMED_BY_ADAPTER.values()),
                         _MODULE.check_adapter_types_named_by_adapters())


class PackageRootRuleTest(RatchetTreeTestCase):
    """A14 — a module creates no new package root (AUD-ARC-12, ADR 0068)."""

    MODULES = ("minos-engine", "minos-cli")
    LISTED = {"minos-engine": frozenset({"io"}), "minos-cli": frozenset({"cli"})}

    def setUp(self):
        super().setUp()
        self.write("minos-engine", "com.minos.io", "Sha256", "public final class Sha256 { }")
        self.write("minos-cli", "com.minos.cli", "Main", "public final class Main { }")

    def check(self, listed=None):
        return _MODULE.check_package_roots(self.root, listed=self.LISTED if listed is None else listed,
                                           modules=self.MODULES)

    def test_a_conforming_tree_is_accepted(self):
        self.assertEqual(2, self.check())

    def test_a_new_root_in_the_engine_is_refused_naming_module_and_root(self):
        self.write("minos-engine", "com.minos.nouveau", "Thing", "final class Thing { }")
        self.refused(self.check, "A14", "minos-engine", "com.minos.nouveau")

    def test_a_sub_package_of_a_listed_root_is_accepted(self):
        self.write("minos-engine", "com.minos.io.lease", "Lease", "final class Lease { }")
        self.assertEqual(2, self.check())

    def test_a_listed_root_without_source_is_refused(self):
        listed = dict(self.LISTED, **{"minos-engine": frozenset({"io", "remote"})})
        self.refused(lambda: self.check(listed), "A14", "com.minos.remote", "holds no production source")

    def test_a_package_outside_the_group_is_refused(self):
        self.write("minos-cli", "org.example", "Stray", "final class Stray { }")
        self.refused(self.check, "A14", "minos-cli", "<none>")

    def test_the_real_tree_creates_no_new_root(self):
        self.assertEqual(sum(len(roots) for roots in _MODULE.PACKAGE_ROOTS_BY_MODULE.values()),
                         _MODULE.check_package_roots())


class SnapshotReadPortRuleTest(RatchetTreeTestCase):
    """A18 — only the core names the in-memory index; the deprecated view stays in its closed list (AUD-ARC-07)."""

    MODULES = ("minos-engine", "minos-storage-local", "minos-storage-postgresql", "minos-application")
    LISTED = frozenset({
        "minos-storage-local/storage.local.store.FileSymbolSnapshotStore",
        "minos-storage-postgresql/storage.postgresql.snapshot.PostgresCodeKnowledgeSnapshotStore",
        "minos-application/impact.ImpactAnalysisService",
    })

    def setUp(self):
        super().setUp()
        self.write("minos-engine", "com.minos.store", "InMemoryCodeKnowledgeStore",
                   "public final class InMemoryCodeKnowledgeStore implements CodeKnowledgeStore { }")
        self.write("minos-engine", "com.minos.store", "SnapshotQueryView",
                   "public record SnapshotQueryView(InMemoryCodeKnowledgeStore queryStore) { }")
        self.write("minos-engine", "com.minos.query", "SymbolQueryService",
                   "public final class SymbolQueryService { CodeKnowledgeReader reader; }",
                   ["com.minos.store.CodeKnowledgeReader"])
        self.write("minos-storage-local", "com.minos.storage.local.store", "FileSymbolSnapshotStore",
                   "public final class FileSymbolSnapshotStore {\n"
                   "    public Optional<SnapshotQueryView> loadActiveQueryView(UUID id) { return null; }\n}",
                   ["com.minos.store.SnapshotQueryView"])
        self.write("minos-storage-postgresql", "com.minos.storage.postgresql.snapshot",
                   "PostgresCodeKnowledgeSnapshotStore",
                   "public final class PostgresCodeKnowledgeSnapshotStore {\n"
                   "    public Optional<SnapshotQueryView> loadActiveQueryView(UUID id) { return null; }\n}",
                   ["com.minos.store.SnapshotQueryView"])
        self.write("minos-application", "com.minos.impact", "ImpactAnalysisService",
                   "public final class ImpactAnalysisService {\n"
                   "    public Object analyze(SnapshotQueryView view) { return view.readView(); }\n}",
                   ["com.minos.store.SnapshotQueryView"])
        self.write("minos-application", "com.minos.application", "ProjectQueryService",
                   "public final class ProjectQueryService { SnapshotReadView view; CodeKnowledgeReader reader; }",
                   ["com.minos.store.SnapshotReadView", "com.minos.store.CodeKnowledgeReader"])

    def check(self, listed=None):
        return _MODULE.check_snapshot_read_port(self.root, listed=self.LISTED if listed is None else listed,
                                                modules=self.MODULES)

    def use_case(self, name, body, imports=()):
        self.write("minos-application", "com.minos.application", name, body, imports)

    def test_a_conforming_tree_is_accepted_with_the_closed_list_count(self):
        self.assertEqual(3, self.check())

    def test_an_imported_memory_index_outside_the_core_is_refused_naming_class_and_type(self):
        self.use_case("Leak", "final class Leak { InMemoryCodeKnowledgeStore store; }",
                      ["com.minos.store.InMemoryCodeKnowledgeStore"])
        self.refused(self.check, "A18", "minos-application/src/main/java/com/minos/application/Leak.java",
                     "InMemoryCodeKnowledgeStore")

    def test_a_static_import_of_the_memory_index_is_refused(self):
        self.use_case("Weight", "final class Weight { long w = symbolEdgeIndexWeightBytes(1L); }",
                      ["static com.minos.store.InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes"])
        self.refused(self.check, "A18", "Weight.java", "InMemoryCodeKnowledgeStore")

    def test_a_wildcard_import_of_the_core_package_with_the_memory_index_is_refused(self):
        self.use_case("Wide", "final class Wide { InMemoryCodeKnowledgeStore store; }", ["com.minos.store.*"])
        self.refused(self.check, "A18", "Wide.java", "InMemoryCodeKnowledgeStore")

    def test_a_nested_type_of_the_memory_index_is_refused(self):
        self.use_case("Edges", "final class Edges { SymbolEdgeIndex edges; }",
                      ["com.minos.store.InMemoryCodeKnowledgeStore.SymbolEdgeIndex"])
        self.refused(self.check, "A18", "Edges.java", "InMemoryCodeKnowledgeStore")

    def test_a_qualified_name_of_the_memory_index_is_refused(self):
        self.use_case("Qualified", "final class Qualified { com.minos.store.InMemoryCodeKnowledgeStore store; }")
        self.refused(self.check, "A18", "Qualified.java", "InMemoryCodeKnowledgeStore")

    def test_another_engine_package_may_not_name_the_memory_index(self):
        self.write("minos-engine", "com.minos.query", "Shortcut", "final class Shortcut { InMemoryCodeKnowledgeStore s; }",
                   ["com.minos.store.InMemoryCodeKnowledgeStore"])
        self.refused(self.check, "A18", "minos-engine/src/main/java/com/minos/query/Shortcut.java")

    def test_the_deprecated_view_named_outside_the_closed_list_is_refused(self):
        self.use_case("Legacy", "final class Legacy { SnapshotQueryView view; }", ["com.minos.store.SnapshotQueryView"])
        self.refused(self.check, "A18", "Legacy.java", "SnapshotQueryView")

    def test_a_chained_call_reaching_the_memory_index_without_any_name_is_refused(self):
        self.use_case("Chained", "final class Chained {\n    Object store(Object snapshots, Object id) {\n"
                      "        var store = snapshots.loadActiveQueryView(id).orElseThrow().queryStore();\n"
                      "        return store;\n    }\n}")
        message = self.refused(self.check, "A18", "Chained.java:", "loadActiveQueryView(")
        self.assertIn("queryStore()", message)

    def test_the_write_port_named_by_the_application_is_refused(self):
        self.use_case("Writer", "final class Writer { CodeKnowledgeStore store; }", ["com.minos.store.CodeKnowledgeStore"])
        self.refused(self.check, "A18", "Writer.java", "CodeKnowledgeStore")

    def test_the_write_port_stays_allowed_outside_the_application(self):
        self.write("minos-storage-local", "com.minos.storage.local.store", "Ingest",
                   "final class Ingest { CodeKnowledgeStore store; }", ["com.minos.store.CodeKnowledgeStore"])
        self.assertEqual(3, self.check())

    def test_an_entry_whose_class_is_gone_is_refused(self):
        (self.root / "minos-application/src/main/java/com/minos/impact/ImpactAnalysisService.java").unlink()
        self.refused(self.check, "A18", "minos-application/impact.ImpactAnalysisService", "remove")

    def test_an_entry_whose_class_no_longer_uses_the_deprecated_view_is_refused(self):
        self.write("minos-application", "com.minos.impact", "ImpactAnalysisService",
                   "public final class ImpactAnalysisService { SnapshotReadView view; }",
                   ["com.minos.store.SnapshotReadView"])
        self.refused(self.check, "A18", "minos-application/impact.ImpactAnalysisService", "remove")

    def test_comments_literals_and_test_sources_do_not_count(self):
        self.use_case("Quiet", 'final class Quiet {\n    // InMemoryCodeKnowledgeStore, SnapshotQueryView, queryStore()\n'
                      '    String s = "loadActiveQueryView(id) CodeKnowledgeStore";\n}')
        self.write("minos-application", "com.minos.application", "QuietTest",
                   "final class QuietTest { InMemoryCodeKnowledgeStore store; SnapshotQueryView view; }",
                   ["com.minos.store.InMemoryCodeKnowledgeStore"], kind="test")
        self.assertEqual(3, self.check())

    def test_the_real_tree_keeps_the_memory_index_in_the_core(self):
        self.assertEqual(len(_MODULE.LEGACY_SNAPSHOT_VIEW_USERS), _MODULE.check_snapshot_read_port())


class RuntimeServiceEdgeTest(RatchetTreeTestCase):
    """AUD-ARC-11 — execution edges by service are rendered and checked in the generated document."""

    GRAPH = {module: frozenset() for module in MODULES}

    def setUp(self):
        super().setUp()
        self.write("minos-cli", "com.minos.cli", "McpLaunchRoute", "public interface McpLaunchRoute { }")
        self.write("minos-app", "com.minos.app", "Route", "public final class Route implements McpLaunchRoute { }")
        services = self.root / "minos-app" / "src" / "main" / "resources" / "META-INF" / "services"
        services.mkdir(parents=True)
        (services / "com.minos.cli.McpLaunchRoute").write_text("# route\ncom.minos.app.Route\n", encoding="utf-8")
        (services / "javax.annotation.processing.Processor").write_text("com.minos.app.Route\n", encoding="utf-8")
        self.document = self.root / "docs" / "module-dependencies.md"

    def edges(self):
        return _MODULE.runtime_service_edges(self.root)

    def test_a_service_declared_by_one_module_and_provided_by_another_is_an_edge(self):
        self.assertEqual({("minos-cli", "minos-app"): ("com.minos.cli.McpLaunchRoute",)}, self.edges())
        text = _MODULE.render_dependency_document(self.GRAPH, self.edges())
        self.assertIn("minos_cli -.-> minos_app", text)
        self.assertIn("| `minos-cli` | `minos-app` | `com.minos.cli.McpLaunchRoute` |", text)

    def test_an_implementation_foreign_to_the_registering_module_is_refused(self):
        services = self.root / "minos-app" / "src" / "main" / "resources" / "META-INF" / "services"
        (services / "com.minos.cli.McpLaunchRoute").write_text("com.minos.app.Missing\n", encoding="utf-8")
        self.refused(self.edges, "com.minos.app.Missing", "not a production type of minos-app")

    def test_a_document_without_the_edge_is_refused_naming_the_edge(self):
        edges = self.edges()
        with contextlib.redirect_stdout(io.StringIO()):
            _MODULE.check_or_write_dependency_document(self.GRAPH, True, self.document, runtime_edges=edges)
        _MODULE.check_or_write_dependency_document(self.GRAPH, False, self.document, runtime_edges=edges)
        text = self.document.read_text(encoding="utf-8")
        self.document.write_text(text.replace("| `minos-cli` | `minos-app` | `com.minos.cli.McpLaunchRoute` |\n", ""),
                                 encoding="utf-8")
        self.refused(lambda: _MODULE.check_or_write_dependency_document(self.GRAPH, False, self.document,
                                                                        runtime_edges=edges),
                     "misses the execution edge", "minos-cli -> minos-app")

    def test_the_real_tree_documents_the_cli_to_assembly_edge(self):
        edges = _MODULE.runtime_service_edges()
        self.assertIn(("minos-cli", "minos-app"), edges)
        self.assertIn(_MODULE.runtime_edge_row("minos-cli", "minos-app", edges[("minos-cli", "minos-app")]),
                      _MODULE.GENERATED_DEPENDENCY_DOC.read_text(encoding="utf-8").splitlines())


class ArchitectureTestPolicyRuleTest(unittest.TestCase):
    """A17 — ALLOWED_DEPENDENCIES and the ALLOWED table of ModuleArchitectureTest stay identical (ADR 0058)."""

    ALLOWED = {"minos-a": frozenset(), "minos-b": frozenset({"minos-a"})}

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.test = Path(directory.name) / "ModuleArchitectureTest.java"

    def write(self, entries):
        body = ",\n".join(f'            Map.entry("{module}", Set.of({", ".join(chr(34) + d + chr(34) for d in deps)}))'
                          for module, deps in entries)
        self.test.write_text("class ModuleArchitectureTest {\n    private static final Map<String, Set<String>> ALLOWED = "
                             f"Map.ofEntries(\n{body});\n}}\n", encoding="utf-8")

    def check(self):
        return _MODULE.check_architecture_test_policy(self.test, self.ALLOWED)

    def test_identical_tables_are_accepted(self):
        self.write([("minos-a", []), ("minos-b", ["minos-a"])])
        self.assertEqual(2, self.check())

    def test_an_extra_edge_in_the_java_table_is_refused(self):
        self.write([("minos-a", ["minos-b"]), ("minos-b", ["minos-a"])])
        with self.assertRaisesRegex(RuntimeError, r"A17 .*minos-a.*minos-b"):
            self.check()

    def test_a_module_missing_from_the_java_table_is_refused(self):
        self.write([("minos-b", ["minos-a"])])
        with self.assertRaisesRegex(RuntimeError, r"A17 .*minos-a"):
            self.check()

    def test_an_unreadable_table_is_refused_instead_of_passing(self):
        self.test.write_text("class ModuleArchitectureTest { }\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, r"A17 .*ALLOWED"):
            self.check()

    def test_the_real_tables_are_identical(self):
        self.assertEqual(len(_MODULE.ALLOWED_DEPENDENCIES), _MODULE.check_architecture_test_policy())


class EveryRuleHasATest(unittest.TestCase):

    def test_every_check_function_of_the_script_is_referenced_by_a_test(self):
        source = Path(__file__).read_text(encoding="utf-8")
        names = sorted(name for name in dir(_MODULE) if name.startswith("check_") and callable(getattr(_MODULE, name)))
        self.assertGreaterEqual(len(names), 10, names)
        untested = [name for name in names if f"_MODULE.{name}" not in source and f"{name} = _MODULE" not in source]
        self.assertEqual([], untested, "rules of check-module-boundaries.py without any test")


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


    def test_main_runs_the_runtime_local_export_rule(self):
        calls: list[str] = []
        code, error = self.run_main_with(check_runtime_local_exports=self.spy(calls, "exports"))
        self.assertEqual(code, 1)
        self.assertEqual(calls, ["exports"])
        self.assertIn("spy exports failed", error)

    def test_main_runs_the_ratchet_rules_a11_to_a14(self):
        for name in ("check_engine_types_named_by_surfaces", "check_process_launchers",
                     "check_adapter_types_named_by_adapters", "check_package_roots",
                     "check_architecture_test_policy", "check_snapshot_read_port"):
            with self.subTest(rule=name):
                calls: list[str] = []
                code, error = self.run_main_with(**{name: self.spy(calls, name)})
                self.assertEqual(code, 1)
                self.assertEqual(calls, [name])
                self.assertIn(f"spy {name} failed", error)

    def test_main_runs_the_unreferenced_production_type_rule(self):
        calls: list[str] = []
        code, error = self.run_main_with(check_unreferenced_production_types=self.spy(calls, "unreferenced"))
        self.assertEqual(code, 1)
        self.assertEqual(calls, ["unreferenced"])
        self.assertIn("spy unreferenced failed", error)


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
        self.assertEqual(len(_MODULE.UNREFERENCED_PRODUCTION_TYPES), _MODULE.check_unreferenced_production_types())


if __name__ == "__main__":
    unittest.main()

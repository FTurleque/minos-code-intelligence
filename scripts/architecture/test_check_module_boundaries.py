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

    def test_the_real_repository_has_exactly_the_four_known_cycles(self):
        with contextlib.redirect_stdout(io.StringIO()):
            cycles, packages = _MODULE.check_package_cycles()
        self.assertEqual(4, cycles)
        self.assertEqual(15, sum(len(packages_) for packages_, _ in _MODULE.KNOWN_PACKAGE_CYCLES.values()))
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


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Self-test of check-module-boundaries.py: the POM rules (A2 dependency policy, hexagonal families, hidden
internal dependencies, source layout), the policy source module-boundaries.json and the wiring of main().

Each case builds a throw-away tree or POM and runs one rule of the real script against it. MainWiringTest derives
every check_* function of the gate and of its rule modules, rather than copying a list: the rule modules are the
module_boundary_*.py files found on disk next to the gate, imported by name whether the gate imports them or not, and
an empty glob fails. It proves that main() runs each of them and that the error of each turns main() red (one
failing spy per rule, the other rules replaying the results of one real run). Every check_* function is also
referenced by one of the test_check_module_boundaries*.py files, which together cover A2, A3, A7 to A20. The
repository itself is checked by running the script directly, as the CI does just before these self-tests.
"""

from __future__ import annotations

import contextlib
import copy
import importlib
import io
import shutil
import sys
import tempfile
import unittest

import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

from module_boundaries_testing import (
    BOUNDARY_POLICY, MODULES, POM_NAMESPACE, RATCHETS, _MODULE, _MODULE_PATH, element, pom_text)


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
        self.refused(scoped_graph(minos_application={"minos-storage": "compile"}),
                     "minos-application -> minos-storage")
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_application={"minos-engine": "compile"}))

    def test_a_surface_may_not_depend_on_an_adapter_in_any_scope(self):
        for scope in ("compile", "runtime", "test"):
            with self.subTest(scope=scope):
                self.refused(scoped_graph(minos_cli={"minos-provider-scip": scope}),
                             "minos-cli -> minos-provider-scip")
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_cli={"minos-application": "compile"}))

    def test_a_surface_sees_the_composition_root_at_runtime_or_in_tests_only(self):
        for module in ("minos-cli", "minos-api", "minos-mcp", "minos-explorer"):
            with self.subTest(module=module):
                self.refused(scoped_graph(**{module: {"minos-bootstrap": "compile"}}), module, "minos-bootstrap")
                for scope in ("runtime", "test"):
                    _MODULE.check_hexagonal_boundaries(scoped_graph(**{module: {"minos-bootstrap": scope}}))

    def test_minos_nexus_is_a_contract_module_and_not_a_surface(self):
        self.assertIn("minos-nexus", BOUNDARY_POLICY.CONTRACT_MODULES)
        self.assertNotIn("minos-nexus", BOUNDARY_POLICY.SURFACE_MODULES)

    def test_a_contract_module_may_not_depend_on_a_surface(self):
        self.refused(scoped_graph(minos_nexus={"minos-cli": "compile"}), "minos-nexus -> minos-cli", "contract module")

    def test_a_contract_module_depends_only_on_the_domain_and_the_application(self):
        for dependency in ("minos-engine", "minos-storage", "minos-app"):
            with self.subTest(dependency=dependency):
                self.refused(scoped_graph(minos_nexus={dependency: "compile"}), f"minos-nexus -> {dependency}",
                             "contract module")
        _MODULE.check_hexagonal_boundaries(scoped_graph(
            minos_nexus={"minos-domain": "compile", "minos-application": "compile"}))

    def test_a_contract_module_sees_the_composition_root_in_tests_only(self):
        for scope in ("compile", "runtime"):
            with self.subTest(scope=scope):
                self.refused(scoped_graph(minos_nexus={"minos-bootstrap": scope}),
                             f"minos-nexus -> minos-bootstrap [{scope}]", "test scope")
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_nexus={"minos-bootstrap": "test"}))

    def test_no_surface_depends_on_another_surface_in_any_scope(self):
        for scope in ("compile", "runtime", "test"):
            with self.subTest(scope=scope):
                self.refused(scoped_graph(minos_mcp={"minos-cli": scope}), "minos-mcp -> minos-cli",
                             "another surface")

    def test_a_surface_may_depend_on_a_contract_module(self):
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_cli={"minos-nexus": "compile"}))

    def test_an_adapter_and_the_composition_root_never_reach_a_contract_module(self):
        self.refused(scoped_graph(minos_storage={"minos-nexus": "compile"}), "minos-storage -> minos-nexus")
        self.refused(scoped_graph(minos_bootstrap={"minos-nexus": "test"}), "minos-bootstrap -> minos-nexus")

    def test_an_adapter_never_reaches_a_higher_layer(self):
        for adapter in sorted(BOUNDARY_POLICY.ADAPTER_MODULES):
            for upper in ("minos-application", "minos-bootstrap", "minos-app", "minos-cli", "minos-mcp"):
                with self.subTest(adapter=adapter, upper=upper):
                    self.refused(scoped_graph(**{adapter: {upper: "compile"}}), f"{adapter} -> {upper}")
            _MODULE.check_hexagonal_boundaries(scoped_graph(**{adapter: {"minos-engine": "compile"}}))

    def test_the_composition_root_compiles_against_the_storage_module(self):
        # The PostgreSQL backend shares minos-storage with the local one; R1 keeps the composition root off it.
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_bootstrap={"minos-storage": "compile"}))

    def test_the_composition_root_sits_below_the_surfaces_and_the_assembly(self):
        self.refused(scoped_graph(minos_bootstrap={"minos-cli": "test"}), "minos-bootstrap -> minos-cli")
        self.refused(scoped_graph(minos_bootstrap={"minos-app": "test"}), "minos-bootstrap -> minos-app")

    def test_the_domain_and_the_engine_know_no_adapter(self):
        self.refused(scoped_graph(minos_engine={"minos-runtime-local": "compile"}), "minos-engine -> minos-runtime-local")
        self.refused(scoped_graph(minos_domain={"minos-integration-git": "test"}), "minos-domain -> minos-integration-git")

    def test_the_scip_provider_sees_the_storage_module_in_tests_only(self):
        # SH-06: the SCIP lifecycle receives its stores through engine ports; its tests keep the real file store.
        for scope in ("compile", "runtime", "provided"):
            with self.subTest(scope=scope):
                self.refused(scoped_graph(minos_provider_scip={"minos-storage": scope}),
                             f"minos-provider-scip -> minos-storage [{scope}]", "TEST_ONLY_DEPENDENCIES", "test")
        _MODULE.check_hexagonal_boundaries(scoped_graph(minos_provider_scip={"minos-storage": "test"}))

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

    def test_the_nexus_contract_module_may_not_depend_on_the_engine_again(self):
        # AUD-ARC-01, lot 5: the NEXUS export goes through minos-application; the edge to the engine is gone.
        self.refused(scoped_graph(minos_nexus={"minos-engine": "compile"}), "minos-nexus -> minos-engine",
                     "contract module")

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
        allowed = {"minos-runtime-local": frozenset({"minos-integration-git"}),
                   "minos-integration-git": frozenset({"minos-runtime-local"})}
        with mock.patch.dict(_MODULE.ALLOWED_DEPENDENCIES, allowed):
            self.refused(scoped_graph(minos_runtime_local={"minos-integration-git": "compile"},
                                      minos_integration_git={"minos-runtime-local": "compile"}),
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
            self.assertEqual("org.example", BOUNDARY_POLICY.repository_group_id(root))
            (root / "pom.xml").write_text(f'<project xmlns="{POM_NAMESPACE}"></project>', encoding="utf-8")
            with self.assertRaises(RuntimeError):
                BOUNDARY_POLICY.repository_group_id(root)

    def test_the_real_root_group_is_the_one_the_modules_use(self):
        self.assertEqual("com.minos", BOUNDARY_POLICY.repository_group_id())


class ApplicationTestJarScopeRuleTest(unittest.TestCase):
    """AUD-ARC-02 — the test-jar of minos-application (MinosApplicationTestAccess) is declared in test scope only."""

    @staticmethod
    def pom(artifact="minos-application", kind="test-jar", scope="test"):
        declared = (f"<dependency><groupId>com.minos</groupId><artifactId>{artifact}</artifactId>"
                    + (f"<type>{kind}</type>" if kind else "") + (f"<scope>{scope}</scope>" if scope else "")
                    + "</dependency>")
        return element(f'<project xmlns="{POM_NAMESPACE}"><artifactId>minos-cli</artifactId>'
                       f"<dependencies>{declared}</dependencies></project>")

    def check(self, pom):
        _MODULE.check_application_test_jar_scope("minos-cli", pom, "com.minos")

    def test_the_test_jar_in_test_scope_is_accepted(self):
        self.check(self.pom())

    def test_the_test_jar_in_another_scope_is_refused_with_module_type_and_scope(self):
        for scope in ("compile", None, "runtime", "provided"):
            with self.subTest(scope=scope), self.assertRaises(RuntimeError) as raised:
                self.check(self.pom(scope=scope))
            for fragment in ("minos-cli", "minos-application", "test-jar", scope or "compile"):
                self.assertIn(fragment, str(raised.exception))

    def test_the_main_jar_and_other_test_jars_are_not_concerned(self):
        self.check(self.pom(kind=None, scope=None))
        self.check(self.pom(artifact="minos-bootstrap", scope="runtime"))

    def test_the_merged_scope_of_a2_is_unchanged(self):
        pom = element(f'<project xmlns="{POM_NAMESPACE}"><artifactId>minos-cli</artifactId><dependencies>'
                      "<dependency><groupId>com.minos</groupId><artifactId>minos-application</artifactId></dependency>"
                      "<dependency><groupId>com.minos</groupId><artifactId>minos-application</artifactId>"
                      "<type>test-jar</type><scope>test</scope></dependency></dependencies></project>")
        self.check(pom)
        self.assertEqual({"minos-application": "compile"}, _MODULE.minos_dependencies("minos-cli", pom, "com.minos"))

    def test_the_real_poms_declare_the_test_jar_in_test_scope(self):
        group = BOUNDARY_POLICY.repository_group_id()
        consumers = []
        for module in MODULES:
            pom = _MODULE.parse_pom(module)
            _MODULE.check_application_test_jar_scope(module, pom, group)
            if any(dependency.findtext("m:artifactId", namespaces=_MODULE.NS) == "minos-application"
                   and dependency.findtext("m:type", namespaces=_MODULE.NS) == "test-jar"
                   for dependency in pom.findall("m:dependencies/m:dependency", _MODULE.NS)):
                consumers.append(module)
        self.assertEqual(sorted(["minos-api", "minos-app", "minos-bootstrap", "minos-cli", "minos-explorer",
                                 "minos-mcp", "minos-nexus"]), sorted(consumers))


class ApplicationTestJarContentRuleTest(unittest.TestCase):
    """AUD-SEC-12 — the test-jar of minos-application publishes MinosApplicationTestAccess alone."""

    INCLUDE = "<include>com/minos/application/MinosApplicationTestAccess.class</include>"

    @classmethod
    def pom(cls, includes=None, extra_execution="", artifact="minos-application", excludes=""):
        includes = f"<includes>{cls.INCLUDE}</includes>" if includes is None else includes
        return element(
            f'<project xmlns="{POM_NAMESPACE}"><artifactId>{artifact}</artifactId><build><plugins><plugin>'
            "<groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><executions>"
            f"<execution><goals><goal>test-jar</goal></goals><configuration>{includes}{excludes}</configuration>"
            f"</execution>{extra_execution}</executions></plugin></plugins></build></project>")

    def check(self, pom, module="minos-application"):
        _MODULE.check_application_test_jar_content(module, pom, "com.minos")

    def refused(self, pom, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            self.check(pom)
        for fragment in ("minos-application/pom.xml",) + fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_the_single_access_class_is_accepted(self):
        self.check(self.pom())

    def test_a_widened_completed_or_missing_include_is_refused_with_the_value_found(self):
        self.refused(self.pom("<includes><include>com/minos/application/**</include></includes>"),
                     "com/minos/application/**")
        self.refused(self.pom(f"<includes>{self.INCLUDE}<include>**/*.class</include></includes>"), "**/*.class")
        self.refused(self.pom(""), "no <includes>")
        self.refused(self.pom("<includes></includes>"), "empty <includes>")

    def test_excludes_are_refused(self):
        self.refused(self.pom(excludes="<excludes><exclude>**/*Test.class</exclude></excludes>"), "**/*Test.class")

    def test_a_second_or_a_missing_test_jar_execution_is_refused(self):
        self.refused(self.pom(extra_execution="<execution><id>more</id><goals><goal>test-jar</goal></goals>"
                                              "</execution>"), "2 executions")
        self.refused(element(f'<project xmlns="{POM_NAMESPACE}"><artifactId>minos-application</artifactId></project>'),
                     "0 executions")

    def test_a_test_jar_execution_in_a_profile_is_counted(self):
        pom = self.pom()
        profile = element(
            f'<profiles xmlns="{POM_NAMESPACE}"><profile><id>wide</id><build><plugins><plugin>'
            "<artifactId>maven-jar-plugin</artifactId><executions><execution><goals><goal>test-jar</goal></goals>"
            "</execution></executions></plugin></plugins></build></profile></profiles>")
        pom.append(profile)
        self.refused(pom, "2 executions")

    def test_other_modules_are_not_concerned(self):
        self.check(self.pom("<includes><include>**</include></includes>", artifact="minos-cli"), module="minos-cli")

    def test_the_real_pom_publishes_the_access_class_only(self):
        _MODULE.check_application_test_jar_content("minos-application", _MODULE.parse_pom("minos-application"),
                                                   BOUNDARY_POLICY.repository_group_id())


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

    def test_the_build_helper_plugin_is_refused_at_the_root_and_in_a_profile(self):
        # E02: build-helper add-source compiles the sources of another module without any compiler setting.
        plugin = ("<plugins><plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId>"
                  "<executions><execution><goals><goal>add-source</goal></goals><configuration><sources>"
                  "<source>../minos-storage/src/main/java</source></sources></configuration></execution></executions>"
                  "</plugin></plugins>")
        with self.subTest(where="root"):
            self.refused(pom_text("minos-x", build=f"<build>{plugin}</build>"), "build-helper-maven-plugin")
        with self.subTest(where="profile"):
            self.refused(pom_text("minos-x", profiles=self.in_profile(plugin)), "build-helper-maven-plugin", "profile")
        with self.subTest(where="pluginManagement"):
            managed = plugin.replace("<plugins>", "<pluginManagement><plugins>").replace("</plugins>", "</plugins></pluginManagement>")
            self.refused(pom_text("minos-x", build=f"<build>{managed}</build>"), "build-helper-maven-plugin")

    def test_plugins_in_a_profile_are_accepted(self):
        _MODULE.check_pom_layout("minos-x", element(pom_text(
            "minos-x", profiles=self.in_profile("<plugins><plugin><artifactId>surefire</artifactId></plugin></plugins>"))))

    def test_a_pom_without_build_is_accepted(self):
        _MODULE.check_pom_layout("minos-x", element(pom_text("minos-x")))

    def test_a_compiler_configuration_without_filters_is_accepted(self):
        _MODULE.check_pom_layout("minos-x", element(pom_text(
            "minos-x", build=f"<build>{self.compiler('<release>24</release>')}</build>")))


class PomRulesOnRealPomsTest(unittest.TestCase):
    """Witness mutations: the rules must also hold on the real POMs, not only on toy trees."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        shutil.copy(BOUNDARY_POLICY.ROOT / "pom.xml", self.root / "pom.xml")
        for module in MODULES:
            (self.root / module).mkdir()
            shutil.copy(BOUNDARY_POLICY.ROOT / module / "pom.xml", self.root / module / "pom.xml")

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
        self.add_dependency("minos-cli", "minos-storage")
        self.refused("A2 hexagonal boundary violated", "minos-cli -> minos-storage")

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


# Functions of the entry point that are not check_* rules but can fail: each gets its own wiring sub-test.
ENTRY_POINT_FUNCTIONS = {
    "runtime_service_edges": "derives the execution edges of the generated document and fails on a service "
                             "implementation outside the module that registers it",
}

# check_* functions of the gate and its rule modules that are not rules of main(): name -> reason. A ratchet, empty
# at delivery: an entry that names no check_* function, or a function main() runs, fails the self-test.
RULES_NOT_RUN_BY_MAIN: dict[str, str] = {}


# The rule modules of the gate: every file of its directory that matches this glob, found on disk.
RULE_MODULE_DIRECTORY = Path(__file__).resolve().parent
RULE_MODULE_GLOB = "module_boundary_*.py"
RULE_MODULE_PREFIX = "module_boundary_"


def rule_module_names(directory: Path = RULE_MODULE_DIRECTORY) -> list[str]:
    """The rule modules found on disk, fail-closed: an empty glob, or a module the gate has loaded that the glob does
    not find, raises instead of reducing the rules to the names of the gate script."""
    names = sorted(path.stem for path in directory.glob(RULE_MODULE_GLOB))
    if not names:
        raise AssertionError(f"no {RULE_MODULE_GLOB} file in {directory.name}: the rule modules cannot be derived")
    loaded = sorted(name for name in sys.modules if name.startswith(RULE_MODULE_PREFIX) and name not in names)
    if loaded:
        raise AssertionError(f"rule modules loaded by the gate but not found by {RULE_MODULE_GLOB}: {loaded}")
    return names


def rule_functions() -> list[str]:
    """Every check_* function of the gate and of its rule modules, derived rather than copied.

    Each rule module is imported by name: a module the gate already imports is that very module, a module it does not
    import is loaded here, so that its check_* functions are expected too.
    """
    modules = [_MODULE] + [importlib.import_module(name) for name in rule_module_names()]
    return sorted({name for module in modules for name in dir(module)
                   if name.startswith("check_") and callable(getattr(module, name))})


class RuleModulesTest(unittest.TestCase):
    """AUD-TST-14: the rule modules are found on disk, and the derivation fails rather than shrinking silently."""

    def test_the_rule_modules_are_found_on_disk(self):
        names = rule_module_names()
        for module in ("module_boundary_adapters", "module_boundary_documents", "module_boundary_packages",
                       "module_boundary_ratchets"):
            self.assertIn(module, names)
        self.assertEqual([], [name for name in sys.modules
                              if name.startswith(RULE_MODULE_PREFIX) and name not in names])
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaisesRegex(AssertionError, "cannot be derived"):
                rule_module_names(Path(empty))
        with mock.patch.dict(sys.modules, {"module_boundary_elsewhere": sys.modules[__name__]}):
            with self.assertRaisesRegex(AssertionError, "module_boundary_elsewhere"):
                rule_module_names()


class EveryRuleHasATest(unittest.TestCase):

    def test_every_check_function_of_the_script_is_referenced_by_a_test(self):
        # The rules are spread over the gate and the modules it imports; their tests over the test files.
        source = "\n".join(path.read_text(encoding="utf-8")
                           for path in sorted(Path(__file__).resolve().parent.glob("test_check_module_boundaries*.py")))
        names = rule_functions()
        self.assertGreaterEqual(len(names), 10, names)
        untested = [name for name in names if f"_MODULE.{name}" not in source and f"{name} = _MODULE" not in source]
        self.assertEqual([], untested, "rules of check-module-boundaries.py without any test")


class MainWiringTest(unittest.TestCase):
    """main() runs every check_* function of the gate and its rule modules, and the error of each turns it red.

    The expected rules are every check_* function (rule_functions), not the calls main() happens to make: a call
    removed from run_rules, a rule added to a rule module and called by nobody, a rule module that the gate does not
    import or reaches only as a module, or an error swallowed on the way to main() fails the sub-test named after the
    function: main() can only run a rule bound to a global name of the gate script. One real run of main() records
    the result of every rule and which rules were running when it was called. Then, for each rule, main() runs again
    with that rule replaced by a spy that raises: the rules that call it (check_pom_rules for the POM rules) run for
    real, every other rule replays its recorded result without reading the repository again. The self-test thus costs one run of the gate.
    """

    @staticmethod
    def run_main_with(spies):
        originals = {name: getattr(_MODULE, name) for name in spies}
        argv = sys.argv
        try:
            for name, spy in spies.items():
                setattr(_MODULE, name, spy)
            sys.argv = [str(_MODULE_PATH)]
            with contextlib.redirect_stdout(io.StringIO()) as output, \
                    contextlib.redirect_stderr(io.StringIO()) as error:
                code = _MODULE.main()
            return code, output.getvalue(), error.getvalue()
        finally:
            sys.argv = argv
            for name, original in originals.items():
                setattr(_MODULE, name, original)

    @classmethod
    def setUpClass(cls):
        cls.expected = [name for name in rule_functions() if name not in RULES_NOT_RUN_BY_MAIN]
        cls.expected += sorted(ENTRY_POINT_FUNCTIONS)
        watched = cls.expected + sorted(RULES_NOT_RUN_BY_MAIN)
        # main() can only reach a function through a global name of the gate script.
        cls.reachable = [name for name in watched if callable(getattr(_MODULE, name, None))]
        cls.results: dict[str, list] = {}
        cls.callers: dict[str, set[str]] = {}
        running: list[str] = []

        def recorder(name, function):
            def spy(*arguments, **keywords):
                cls.callers.setdefault(name, set()).update(running)
                running.append(name)
                try:
                    result = function(*arguments, **keywords)
                finally:
                    running.pop()
                cls.results.setdefault(name, []).append(result)
                return result
            return spy

        cls.code, cls.output, cls.error = cls.run_main_with(
            {name: recorder(name, getattr(_MODULE, name)) for name in cls.reachable})

    @classmethod
    def replay(cls, name):
        recorded = iter(cls.results.get(name, ()))

        def spy(*arguments, **keywords):
            return copy.deepcopy(next(recorded))
        return spy

    @staticmethod
    def failing(name):
        def spy(*arguments, **keywords):
            raise RuntimeError(f"spy {name} failed")
        return spy

    def test_the_real_run_passes_and_calls_every_rule(self):
        self.assertEqual(0, self.code, self.error)
        self.assertIn("M21 MODULE BOUNDARY CONSISTENCY SUCCESS", self.output)
        self.assertGreaterEqual(len(self.expected), 27, self.expected)
        self.assertEqual([], [name for name in self.expected if name not in self.results],
                         "rules that main() does not run")

    def test_the_error_of_every_rule_turns_main_red(self):
        for name in self.expected:
            with self.subTest(rule=name):
                self.assertIn(name, self.reachable,
                              f"{name} is not a global name of check-module-boundaries.py: main() cannot run it")
                self.assertIn(name, self.results, f"{name} is not run by main()")
                spies = {other: self.replay(other) for other in self.reachable
                         if other != name and other not in self.callers[name]}
                spies[name] = self.failing(name)
                code, output, error = self.run_main_with(spies)
                self.assertEqual(1, code, f"the error of {name} does not reach main()")
                self.assertIn(f"M21 MODULE BOUNDARY CONSISTENCY FAILED: spy {name} failed", error)
                self.assertNotIn("SUCCESS", output)

    def test_the_lists_of_exceptions_are_closed(self):
        rules = rule_functions()
        for name, reason in RULES_NOT_RUN_BY_MAIN.items():
            with self.subTest(exempted=name):
                self.assertIn(name, rules, f"{name} is no check_* function of the gate or its rule modules")
                self.assertTrue(reason.strip(), f"{name} has no reason")
                self.assertNotIn(name, self.results, f"{name} is run by main(): remove its exemption")
        for name, reason in ENTRY_POINT_FUNCTIONS.items():
            with self.subTest(entry_point=name):
                self.assertFalse(name.startswith("check_"), f"{name} is a rule: it is derived, not listed")
                self.assertTrue(reason.strip(), f"{name} has no reason")


class PolicySourceTest(unittest.TestCase):
    """AUD-ARC-04: the shared boundary policy is read from scripts/architecture/module-boundaries.json, fail-closed."""

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.path = self.root / "scripts" / "architecture" / "module-boundaries.json"
        self.path.parent.mkdir(parents=True)
        self.real = BOUNDARY_POLICY.POLICY_FILE.read_text(encoding="utf-8")

    def write(self, text: str) -> None:
        self.path.write_text(text, encoding="utf-8")

    def mutated(self, change) -> None:
        import json
        policy = json.loads(self.real)
        change(policy)
        self.write(json.dumps(policy))

    def refused(self, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            BOUNDARY_POLICY.load_policy(self.path, self.root)
        message = str(raised.exception)
        self.assertIn("scripts/architecture/module-boundaries.json", message)
        self.assertNotIn(str(self.root), message, "the message names the file relatively to the repository")
        for fragment in fragments:
            self.assertIn(fragment, message)

    def test_the_real_policy_is_the_source_of_the_guard_tables(self):
        policy = BOUNDARY_POLICY.load_policy()
        self.assertEqual(BOUNDARY_POLICY.MODULES, policy.modules)
        self.assertEqual(dict(_MODULE.ALLOWED_DEPENDENCIES), policy.allowed_dependencies)
        self.assertEqual(RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE, policy.engine_types_named_by_surface)
        self.assertEqual(BOUNDARY_POLICY.CONTRACT_MODULES, frozenset({"minos-nexus"}))
        self.assertIn("minos-cli", BOUNDARY_POLICY.SURFACE_MODULES)

    def test_a_missing_file_is_refused(self):
        self.refused("missing")

    def test_an_unparsable_file_is_refused(self):
        self.write("{ not json")
        self.refused("not valid JSON")

    def test_an_unknown_key_is_refused(self):
        self.mutated(lambda policy: policy.__setitem__("alowedDependencies", policy.pop("allowedDependencies")))
        self.refused("unknown key", "alowedDependencies")

    def test_a_duplicated_key_is_refused(self):
        self.write(self.real.replace('"version": 1,', '"version": 1,\n  "version": 1,', 1))
        self.refused("duplicated key", "version")

    def test_a_missing_section_is_refused(self):
        self.mutated(lambda policy: policy.pop("testOnlyDependencies"))
        self.refused("missing section", "testOnlyDependencies")

    def test_an_unknown_family_is_refused(self):
        self.mutated(lambda policy: policy["modules"][0].__setitem__("family", "kernel"))
        self.refused("unknown family", "kernel")

    def test_an_unknown_module_in_an_edge_is_refused(self):
        self.mutated(lambda policy: policy["allowedDependencies"]["minos-cli"].append("minos-inconnu"))
        self.refused("minos-inconnu", "allowedDependencies.minos-cli")

    def test_a_debt_entry_without_reason_is_refused(self):
        self.mutated(lambda policy: policy["engineTypesNamedBySurface"]["minos-cli"]["runtime.MinosVersion"]
                     .__setitem__("reason", " "))
        self.refused("reason", "runtime.MinosVersion")

    def test_an_engine_debt_keyed_by_an_adapter_is_refused(self):
        self.mutated(lambda policy: policy["engineTypesUsedBySurfaceBytecode"].__setitem__("minos-storage", {}))
        self.refused("minos-storage", "neither a surface nor a contract module")

    def test_an_edge_removed_from_the_file_fails_the_dependency_policy(self):
        self.mutated(lambda policy: policy["allowedDependencies"]["minos-cli"].remove("minos-nexus"))
        policy = BOUNDARY_POLICY.load_policy(self.path, self.root)
        with mock.patch.object(_MODULE, "ALLOWED_DEPENDENCIES", policy.allowed_dependencies):
            with self.assertRaises(RuntimeError) as raised:
                _MODULE.check_dependency_policy(scoped_graph(minos_cli={"minos-nexus": "compile"}))
        self.assertIn("minos-cli", str(raised.exception))
        self.assertIn("minos-nexus", str(raised.exception))


if __name__ == "__main__":
    unittest.main()

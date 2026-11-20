#!/usr/bin/env python3
"""Self-test of check-module-boundaries.py: the POM rules (A2 dependency policy, hexagonal families, hidden
internal dependencies, source layout), the policy source module-boundaries.json and the wiring of main().

Each case builds a throw-away tree or POM and runs one rule of the real script against it. main() is checked to
run every rule (failing spies), and every check_* function of the script is referenced by one of the
test_check_module_boundaries*.py files, which together cover A2, A3, A7 to A20. The repository itself is checked by
running the script directly, as the CI does just before these self-tests.
"""

from __future__ import annotations

import contextlib
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
    ADAPTERS, BOUNDARY_POLICY, DOCUMENTS, MODULES, PACKAGES, POM_NAMESPACE, RATCHETS, _MODULE, _MODULE_PATH, element,
    pom_text)


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


class EveryRuleHasATest(unittest.TestCase):

    def test_every_check_function_of_the_script_is_referenced_by_a_test(self):
        # The rules are spread over the gate and the modules it imports; their tests over the test files.
        source = "\n".join(path.read_text(encoding="utf-8")
                           for path in sorted(Path(__file__).resolve().parent.glob("test_check_module_boundaries*.py")))
        modules = (_MODULE, BOUNDARY_POLICY, ADAPTERS, RATCHETS, PACKAGES, DOCUMENTS)
        names = sorted({name for module in modules for name in dir(module)
                        if name.startswith("check_") and callable(getattr(module, name))})
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
        for name in ("check_engine_types_named_by_surfaces", "check_minos_application_accessors",
                     "check_process_launchers",
                     "check_adapter_types_named_by_adapters", "check_package_roots",
                     "check_architecture_test_policy", "check_snapshot_read_port",
                     "check_postgresql_backend_isolation", "check_storage_package_isolation"):
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

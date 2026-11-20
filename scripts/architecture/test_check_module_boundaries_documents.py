#!/usr/bin/env python3
"""Self-test of the documentation rules of check-module-boundaries.py: A9 (arc42 block view), A17 (ArchUnit policy
reading) and the generated module dependency document with its execution edges by service.

Each case builds a throw-away document or tree and runs one rule of the real script against it.
"""

from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from module_boundaries_testing import BOUNDARY_POLICY, DOCUMENTS, MODULES, RatchetTreeTestCase, _MODULE


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
        text = DOCUMENTS.render_dependency_document(graph)
        self.assertIn("minos_engine --> minos_domain", text)
        self.assertIn("| `minos-engine` | `minos-domain` |", text)


ARC42 = """# Section 5

## 5.2 Détail des containers

### minos-domain
- **Responsabilité** : le noyau.
- **Dépendances** : aucune.
- **Sources** : `minos-domain/src/main/java/`.

### minos-engine
- **Responsabilité** : les ports.
- **Dépendances** : `minos-domain`, `jackson 2.22`.
- **Sources** : `minos-engine/src/main/java/`.

### minos-cli (optionnel)
- **Dépendances** : `minos-engine`, `minos-domain`.
- **Sources** : `minos-cli/src/main/java/`.

### minos-app
- **Dépendances** : tous les modules.
- **Sources** : `minos-app/src/main/java/`.
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
        self.assertEqual(len(MODULES), _MODULE.check_architecture_documentation(scoped))


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
        return DOCUMENTS.runtime_service_edges(self.root)

    def test_a_service_declared_by_one_module_and_provided_by_another_is_an_edge(self):
        self.assertEqual({("minos-cli", "minos-app"): ("com.minos.cli.McpLaunchRoute",)}, self.edges())
        text = DOCUMENTS.render_dependency_document(self.GRAPH, self.edges())
        self.assertIn("minos_cli -.-> minos_app", text)
        self.assertIn("| `minos-cli` | `minos-app` | `com.minos.cli.McpLaunchRoute` |", text)

    def test_an_implementation_foreign_to_the_registering_module_is_refused(self):
        services = self.root / "minos-app" / "src" / "main" / "resources" / "META-INF" / "services"
        (services / "com.minos.cli.McpLaunchRoute").write_text("com.minos.app.Missing\n", encoding="utf-8")
        self.refused(self.edges, "com.minos.app.Missing", "not a production type of minos-app")

    def test_the_assembly_registers_an_implementation_of_a_module_it_depends_on(self):
        # D7 (SH-02): the assembly declares the optional PostgreSQL backend; the edge goes to the providing module.
        self.write("minos-engine", "com.minos.storage", "StorageBackendProvider", "public interface StorageBackendProvider { }")
        self.write("minos-storage", "com.minos.storage.postgresql", "PostgresStorageBackendProvider",
                   "public final class PostgresStorageBackendProvider implements StorageBackendProvider { }")
        services = self.root / "minos-app" / "src" / "main" / "resources" / "META-INF" / "services"
        (services / "com.minos.storage.StorageBackendProvider").write_text(
            "com.minos.storage.postgresql.PostgresStorageBackendProvider\n", encoding="utf-8")
        self.assertEqual(("com.minos.storage.StorageBackendProvider",), self.edges()[("minos-engine", "minos-storage")])

    def test_a_module_other_than_the_assembly_registering_a_foreign_implementation_is_refused(self):
        self.write("minos-engine", "com.minos.storage", "StorageBackendProvider", "public interface StorageBackendProvider { }")
        self.write("minos-storage", "com.minos.storage.postgresql", "PostgresStorageBackendProvider",
                   "public final class PostgresStorageBackendProvider implements StorageBackendProvider { }")
        services = self.root / "minos-bootstrap" / "src" / "main" / "resources" / "META-INF" / "services"
        services.mkdir(parents=True)
        (services / "com.minos.storage.StorageBackendProvider").write_text(
            "com.minos.storage.postgresql.PostgresStorageBackendProvider\n", encoding="utf-8")
        self.refused(self.edges, "minos-bootstrap", "not a production type of minos-bootstrap")

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
        edges = DOCUMENTS.runtime_service_edges()
        self.assertIn(("minos-cli", "minos-app"), edges)
        self.assertIn(DOCUMENTS.runtime_edge_row("minos-cli", "minos-app", edges[("minos-cli", "minos-app")]),
                      DOCUMENTS.GENERATED_DEPENDENCY_DOC.read_text(encoding="utf-8").splitlines())


class ArchitectureTestPolicyRuleTest(unittest.TestCase):
    """A17 — ModuleArchitectureTest reads module-boundaries.json and declares no policy table again (ADR 0058)."""

    READING = ("class ModuleArchitectureTest {\n"
               "    // The policy: scripts/architecture/module-boundaries.json, read by ModuleBoundaryPolicy.\n"
               "    private static final ModuleBoundaryPolicy POLICY = ModuleBoundaryPolicy.load(Path.of(\"\"));\n"
               "    private static final Map<String, Set<String>> ALLOWED = POLICY.allowed();\n}\n")

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.test = Path(directory.name) / "ModuleArchitectureTest.java"
        self.reader = Path(directory.name) / "ModuleBoundaryPolicy.java"
        self.reader.write_text('class ModuleBoundaryPolicy { static final Path FILE = Path.of("scripts", '
                               '"architecture", "module-boundaries.json"); }\n', encoding="utf-8")

    def check(self):
        return _MODULE.check_architecture_test_policy(self.test, self.reader)

    def test_a_test_that_reads_the_policy_is_accepted(self):
        self.test.write_text(self.READING, encoding="utf-8")
        self.assertEqual(len(_MODULE.ALLOWED_DEPENDENCIES), self.check())

    def test_a_copied_allowed_table_is_refused(self):
        self.test.write_text(self.READING.replace("POLICY.allowed();", 'Map.ofEntries(\n'
                             '            Map.entry("minos-domain", Set.of()));'), encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, r"A17 .*ModuleArchitectureTest.java.*ALLOWED"):
            self.check()

    def test_a_copied_module_set_is_refused(self):
        self.test.write_text(self.READING.replace(
            "}\n", '    private static final Set<String> SURFACES = Set.of("minos-cli", "minos-api");\n}\n'),
            encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, r"A17 .*SURFACES"):
            self.check()

    def test_a_test_that_no_longer_reads_the_policy_is_refused(self):
        self.test.write_text("class ModuleArchitectureTest {\n    private static final Map<String, Set<String>> ALLOWED = "
                             "Map.of();\n}\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, r"A17 .*no longer reads"):
            self.check()

    def test_a_reader_that_no_longer_names_the_file_is_refused(self):
        self.test.write_text(self.READING, encoding="utf-8")
        self.reader.write_text('class ModuleBoundaryPolicy { static final Path FILE = Path.of("policy.json"); }\n',
                               encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, r"A17 .*module-boundaries.json"):
            self.check()

    def test_a_missing_test_is_refused(self):
        with self.assertRaisesRegex(RuntimeError, r"A17 .*missing"):
            self.check()

    def test_the_real_test_reads_the_single_source(self):
        self.assertEqual(len(_MODULE.ALLOWED_DEPENDENCIES), _MODULE.check_architecture_test_policy())


class DocumentedScopeSourcesAndInterfacesTest(unittest.TestCase):
    """AUD-ARC-07 — A9 reads the scope of each dependency, the package roots and the interfaces of a module section."""

    EXPLORER = {"minos-application": "compile", "minos-bootstrap": "runtime", "minos-domain": "compile",
                "minos-engine": "test"}

    def problem(self, line, expected=None):
        return DOCUMENTS.documentation_problem("minos-explorer", [line], expected or self.EXPLORER, MODULES)

    def test_a_line_with_the_scopes_of_the_pom_is_accepted(self):
        self.assertIsNone(self.problem(
            "`minos-application`, `minos-bootstrap` (runtime), `minos-domain`, `minos-engine` (test)"))

    def test_a_missing_scope_is_refused_naming_the_dependency_and_the_expected_scope(self):
        problem = self.problem("`minos-application`, `minos-bootstrap` (runtime), `minos-domain`, `minos-engine`")
        self.assertIn("minos-engine documented with scope none", problem)
        self.assertIn("expected scope: test", problem)

    def test_a_wrong_scope_is_refused(self):
        problem = self.problem("`minos-application`, `minos-bootstrap` (test), `minos-domain`, `minos-engine` (test)")
        self.assertIn("minos-bootstrap documented with scope test", problem)

    def test_a_scope_written_for_a_compile_dependency_is_refused(self):
        problem = self.problem("`minos-application` (test), `minos-bootstrap` (runtime), `minos-domain`, "
                               "`minos-engine` (test)")
        self.assertIn("minos-application documented with scope test", problem)
        self.assertIn("compile", problem)

    def test_a_scope_parenthesis_may_carry_free_text_after_the_scope(self):
        self.assertIsNone(self.problem("`minos-application`, `minos-bootstrap` (runtime, par ServiceLoader), "
                                       "`minos-domain`, `minos-engine` (test : jar et test-jar)"))

    def test_a_see_also_parenthesis_after_a_compile_dependency_is_not_a_scope(self):
        self.assertIsNone(DOCUMENTS.documentation_problem(
            "minos-bootstrap", ["`minos-domain`, `minos-storage` (voir [`module-dependencies.md`](x.md))."],
            {"minos-domain": "compile", "minos-storage": "compile"}, MODULES))

    def test_the_generated_document_writes_the_scope_outside_compilation(self):
        scoped = {module: {} for module in MODULES}
        scoped["minos-explorer"] = dict(self.EXPLORER)
        text = DOCUMENTS.render_dependency_document(scoped)
        self.assertIn("| `minos-explorer` | `minos-application`, `minos-bootstrap` (runtime), `minos-domain`, "
                      "`minos-engine` (test) |", text)
        self.assertIn("minos_explorer -->|test| minos_engine", text)
        self.assertIn("minos_explorer --> minos_application", text)

    def test_a_package_root_omitted_from_the_sources_line_is_refused(self):
        problem = DOCUMENTS.sources_problem("minos-engine", ["`minos-engine/src/main/java/com/minos/store/`, "
                                                           "`com/minos/query/`."], frozenset({"store", "query", "io"}))
        self.assertIn("minos-engine", problem)
        self.assertIn("omits the package root(s) io", problem)

    def test_a_package_root_of_another_module_is_refused(self):
        problem = DOCUMENTS.sources_problem("minos-domain", ["`com/minos/domain/`, `com/minos/store/`."],
                                          frozenset({"domain"}))
        self.assertIn("store the module does not own", problem)

    def test_a_section_without_a_sources_line_is_refused(self):
        self.assertIn("expected one 'Sources' line", DOCUMENTS.sources_problem("minos-api", [], frozenset({"api"})))

    def test_a_sources_line_with_the_source_directory_only_is_accepted(self):
        self.assertIsNone(DOCUMENTS.sources_problem("minos-application", ["`minos-application/src/main/java/com/minos/`."],
                                                  frozenset({"application", "context"})))

    OWNERS = {"SemanticVectorStore": {"minos-domain"}, "StorageBackend": {"minos-engine"},
              "IndexerProvider": {"minos-engine"}}

    def test_a_port_attributed_to_the_wrong_module_is_refused_naming_its_real_module(self):
        line = ("implémente `StorageBackend`, `SemanticVectorStore`, définies dans `minos-engine`. Le module ne dépend "
                "pas de `minos-application`.")
        problem = DOCUMENTS.interfaces_problem("minos-storage", [line], self.OWNERS, [])
        self.assertIn("SemanticVectorStore is declared by minos-domain", problem)
        self.assertNotIn("StorageBackend", problem)

    def test_a_module_named_outside_the_defined_in_clause_does_not_count(self):
        problem = DOCUMENTS.interfaces_problem("minos-storage", ["implémente `SemanticVectorStore`. Voir `minos-domain`."],
                                             self.OWNERS, [])
        self.assertIn("SemanticVectorStore is declared by minos-domain", problem)

    def test_none_is_refused_for_a_module_that_declares_a_public_interface(self):
        problem = DOCUMENTS.interfaces_problem("minos-domain", ["aucune (modèle passif)."], self.OWNERS,
                                             ["SemanticVectorStore"])
        self.assertIn("SemanticVectorStore", problem)
        self.assertIsNone(DOCUMENTS.interfaces_problem("minos-domain", ["aucune."], self.OWNERS, []))

    def test_a_type_no_module_declares_is_refused(self):
        problem = DOCUMENTS.interfaces_problem("minos-engine", ["`StorageBackend`, `GoneType`."], self.OWNERS, [])
        self.assertIn("GoneType is no production type of the reactor", problem)

    def test_a_section_without_an_interfaces_line_is_accepted(self):
        self.assertIsNone(DOCUMENTS.interfaces_problem("minos-api", [], self.OWNERS, ["MinosApi"]))

    def test_the_real_reactor_owners_and_public_interfaces_are_read(self):
        owners = DOCUMENTS.reactor_type_owners(BOUNDARY_POLICY.ROOT, MODULES)
        self.assertEqual({"minos-domain"}, owners["SemanticVectorStore"])
        self.assertIn("SemanticVectorStore", DOCUMENTS.public_interfaces(BOUNDARY_POLICY.ROOT, "minos-domain"))


if __name__ == "__main__":
    unittest.main()

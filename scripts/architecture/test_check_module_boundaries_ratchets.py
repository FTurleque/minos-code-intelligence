#!/usr/bin/env python3
"""Self-test of the ratchet rules of check-module-boundaries.py: A11 (minos-engine types named by surfaces and
MinosApplication accessors), A12 process launchers, A13 adapter types named by adapters, and the measured share of
concrete minos-engine code.

Each case builds a throw-away source tree and runs one rule of the real script against it: a new use refused, a stale
entry refused, and the real tree checked against its tables.
"""

from __future__ import annotations

import sys
import unittest

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from module_boundaries_testing import RATCHETS, RatchetTreeTestCase, _MODULE


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
        self.assertEqual({"minos-api": 0, "minos-cli": 1, "minos-explorer": 0, "minos-mcp": 0, "minos-nexus": 0}, self.check())

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
        cli = RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
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
        self.assertEqual({surface: len(types) for surface, types in RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE.items()},
                         counts)

    def test_a_new_cli_file_importing_a_listed_type_is_refused_against_the_real_cli_list(self):
        # A type ratchet lets a new CLI file import a type already listed; the file count refuses it.
        cli = RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
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
        for surface, types in RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE.items():
            self.assertFalse(moved & set(types), surface)

    def test_the_cli_no_longer_uses_the_host_mechanisms_of_the_engine(self):
        # AUD-ARC-01, lot 2: minos doctor reads them through HostDiagnosticsService.
        cli = RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
        self.assertNotIn("io.PrivateLocalStorage", cli)
        self.assertNotIn("runtime.WorkerSandboxProbe", cli)

    def test_the_surfaces_read_git_through_the_application_and_keep_only_the_published_read_models(self):
        # AUD-ARC-01, lot 4: Git goes through ProjectGitQuery, the API symbol query is built from the application, and
        # the only query.* entries are the read models the ProjectSymbolQuery port returns (ADR 0057, point 6).
        published = {"query.RelationshipResult", "query.SymbolResult", "query.UsageResult"}
        for surface, types in RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE.items():
            self.assertNotIn("git.GitIntelligence", types, surface)
            self.assertLessEqual({name for name in types if name.startswith("query.")}, published, surface)
        api = RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-api"]
        self.assertNotIn("registry.ProjectRegistry", api)
        self.assertNotIn("store.CodeKnowledgeSnapshotStore", api)

    def test_no_surface_uses_the_hosted_control_plane_of_the_engine(self):
        # AUD-ARC-01, lot 3: the surfaces reach the team control plane through TeamControlPlane.
        for surface, types in RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE.items():
            self.assertEqual([], [entry for entry in types if entry.startswith("hosted.")], surface)

    def test_the_nexus_export_no_longer_uses_the_engine(self):
        # AUD-ARC-01, lot 5: the export goes through KnowledgeExportUseCase, and the CLI passes the application.
        self.assertEqual({}, RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-nexus"])
        cli = RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"]
        self.assertNotIn("registry.ProjectRegistry", cli)
        self.assertNotIn("store.CodeKnowledgeSnapshotStore", cli)

    def test_an_engine_type_back_in_nexus_is_refused_against_the_real_list(self):
        self.write("minos-engine", "com.minos.store", "CodeKnowledgeSnapshot", "public record CodeKnowledgeSnapshot() { }")
        self.write("minos-nexus", "com.minos.nexus", "NexusExportService", "final class NexusExportService { }",
                   ["com.minos.store.CodeKnowledgeSnapshot"])
        listed = dict(self.LISTED, **{"minos-nexus": RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-nexus"]})
        self.refused(lambda: self.check(listed), "A11", "minos-nexus",
                     "minos-nexus/src/main/java/com/minos/nexus/NexusExportService.java",
                     "com.minos.store.CodeKnowledgeSnapshot")


class MinosApplicationTreeTestCase(RatchetTreeTestCase):
    """A temporary tree with a MinosApplication whose public accessors return engine types (fixture, no test)."""

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

    APPLICATION_IMPORTS = ("com.minos.store.CodeKnowledgeSnapshotStore", "com.minos.git.GitIntelligence",
                           "com.minos.hosted.HostedControlPlaneService",
                           "com.minos.orchestration.IndexingRuntimePorts.SnapshotStager",
                           "com.minos.orchestration.IndexerRegistry", "java.util.Optional")

    def setUp(self):
        super().setUp()
        for package, name in (("store", "CodeKnowledgeSnapshotStore"), ("git", "GitIntelligence"),
                              ("hosted", "HostedControlPlaneService"), ("orchestration", "IndexingRuntimePorts"),
                              ("orchestration", "IndexerRegistry")):
            self.write("minos-engine", f"com.minos.{package}", name, f"public interface {name} {{ }}")
        self.write("minos-application", "com.minos.application", "MinosApplication", self.APPLICATION,
                   self.APPLICATION_IMPORTS)

    def surface(self, module, name, body, imports=()):
        self.write(module, f"com.minos.{module.removeprefix('minos-')}", name, body, imports)


class MinosApplicationAccessorRuleTest(MinosApplicationTreeTestCase):
    """A11 — a MinosApplication accessor called by a surface uses the engine type it returns (AUD-ARC-01)."""

    def check(self, listed):
        return _MODULE.check_engine_types_named_by_surfaces(self.root, listed=listed)

    def test_the_accessors_returning_an_engine_type_are_read_from_the_application_source(self):
        self.assertEqual({"snapshotStore": (0, "store.CodeKnowledgeSnapshotStore"),
                          "gitIntelligence": (0, "git.GitIntelligence"),
                          "hostedControlPlaneService": (0, "hosted.HostedControlPlaneService"),
                          "snapshotStager": (0, "orchestration.IndexingRuntimePorts"),
                          "indexerRegistry": (1, "orchestration.IndexerRegistry")},
                         RATCHETS.minos_application_accessors(self.root, "com.minos"))

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
        accessors = RATCHETS.minos_application_accessors(self.root, "com.minos")
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

    def test_the_real_application_exposes_no_engine_accessor(self):
        # AUD-ARC-02: the ports and the composition root are package-private; the tests of the other modules read
        # them through MinosApplicationTestAccess, published by the test-jar of minos-application.
        self.assertEqual({}, RATCHETS.minos_application_accessors())
        self.assertEqual([], RATCHETS.public_composition_root_methods())


class MinosApplicationAccessorRatchetTest(MinosApplicationTreeTestCase):
    """A11 — the public MinosApplication accessors returning an engine type are a closed list (AUD-ARC-01)."""

    LISTED = {"snapshotStore": "store.CodeKnowledgeSnapshotStore", "gitIntelligence": "git.GitIntelligence",
              "hostedControlPlaneService": "hosted.HostedControlPlaneService",
              "snapshotStager": "orchestration.IndexingRuntimePorts",
              "indexerRegistry": "orchestration.IndexerRegistry"}

    def check_accessors(self, listed):
        return _MODULE.check_minos_application_accessors(self.root, "com.minos", listed=listed)

    def test_the_exact_list_is_accepted(self):
        self.assertEqual(5, self.check_accessors(self.LISTED))

    def test_an_accessor_outside_the_list_is_refused_with_its_name_and_type(self):
        listed = {name: engine_type for name, engine_type in self.LISTED.items() if name != "gitIntelligence"}
        self.refused(lambda: self.check_accessors(listed), "A11", "MinosApplication.gitIntelligence",
                     "git.GitIntelligence", "application facade")

    def test_a_different_returned_type_is_refused(self):
        listed = dict(self.LISTED, snapshotStore="registry.ProjectRegistry")
        self.refused(lambda: self.check_accessors(listed), "A11", "MinosApplication.snapshotStore",
                     "store.CodeKnowledgeSnapshotStore", "registry.ProjectRegistry")

    def test_an_entry_without_a_public_accessor_is_refused(self):
        listed = dict(self.LISTED, scipArtifactImporter="orchestration.ScipArtifactImporter")
        self.refused(lambda: self.check_accessors(listed), "A11", "scipArtifactImporter", "remove it")

    def test_a_surface_reaching_the_composition_root_is_refused(self):
        self.surface("minos-cli", "Bootstrap", "final class Bootstrap { Object s(MinosApplication app) { "
                     "return app.compositionRoot().gitIntelligence(); } }", ["com.minos.application.MinosApplication"])
        self.refused(lambda: self.check_accessors(self.LISTED), "A11",
                     "minos-cli/src/main/java/com/minos/cli/Bootstrap.java", "composition root")
        self.surface("minos-cli", "Bootstrap", "final class Bootstrap { Object s() { "
                     "return MinosApplicationComposers.resolve(); } }",
                     ["com.minos.application.MinosApplicationComposers"])
        self.refused(lambda: self.check_accessors(self.LISTED), "A11", "Bootstrap.java")

    def test_the_composition_root_in_a_comment_a_literal_or_a_test_is_ignored(self):
        self.surface("minos-mcp", "Quiet", 'final class Quiet { // app.compositionRoot()\n '
                     'String s = "MinosApplicationComposers"; }')
        self.write("minos-mcp", "com.minos.mcp", "QuietTest", "final class QuietTest { Object o = app.compositionRoot(); }",
                   kind="test")
        self.assertEqual(5, self.check_accessors(self.LISTED))

    def test_the_real_list_matches_the_real_application(self):
        self.assertEqual(len(RATCHETS.MINOS_APPLICATION_ENGINE_ACCESSORS),
                         _MODULE.check_minos_application_accessors())
        self.assertEqual(0, len(RATCHETS.MINOS_APPLICATION_ENGINE_ACCESSORS))

    def test_a_public_composition_root_is_refused_and_a_package_private_one_accepted(self):
        # AUD-ARC-02: the composition root hands out engine ports under an application type.
        application = self.APPLICATION.replace(
            "    private GitIntelligence hidden()",
            "    public MinosApplicationComposer compositionRoot() { return null; }\n    private GitIntelligence hidden()")
        self.write("minos-application", "com.minos.application", "MinosApplication", application,
                   self.APPLICATION_IMPORTS)
        self.refused(lambda: self.check_accessors(self.LISTED), "A11", "MinosApplication.compositionRoot",
                     "MinosApplicationComposer")
        self.write("minos-application", "com.minos.application", "MinosApplication",
                   application.replace("public MinosApplicationComposer", "MinosApplicationComposer"),
                   self.APPLICATION_IMPORTS)
        self.assertEqual(5, self.check_accessors(self.LISTED))

    def test_an_engine_accessor_reintroduced_with_an_empty_table_is_refused(self):
        self.write("minos-engine", "com.minos.registry", "ProjectRegistry", "public interface ProjectRegistry { }")
        self.write("minos-application", "com.minos.application", "MinosApplication",
                   "public final class MinosApplication {\n"
                   "    public ProjectRegistry projectRegistry() { return null; }\n}",
                   ["com.minos.registry.ProjectRegistry"])
        self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.projectRegistry",
                     "registry.ProjectRegistry")

    def application_with(self, members, imports=("com.minos.registry.ProjectRegistry",)):
        self.write("minos-engine", "com.minos.registry", "ProjectRegistry", "public interface ProjectRegistry { }")
        self.write("minos-application", "com.minos.application", "MinosApplication",
                   "public final class MinosApplication {\n" + members + "\n}", list(imports))

    def test_a_public_signature_taking_an_engine_type_is_refused(self):
        # AUD-ARC-13: the facade neither returns nor takes an engine type.
        variants = (
            ("    public void leak(ProjectRegistry registry) { }", "MinosApplication.leak", "registry",
             "registry.ProjectRegistry", ("com.minos.registry.ProjectRegistry",)),
            ("    public static MinosApplication of(com.minos.registry.ProjectRegistry registry) { return null; }",
             "MinosApplication.of", "registry", "registry.ProjectRegistry", ()),
            ("    public ProjectIndexingUseCase projectIndexing(UnaryOperator<IndexerExecutor> decorator) { return null; }",
             "MinosApplication.projectIndexing", "decorator", "orchestration.IndexingRuntimePorts",
             ("com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor", "java.util.function.UnaryOperator")),
            ("    public MinosApplication(String home, @Deprecated final ProjectRegistry registry) { }",
             "MinosApplication.MinosApplication", "registry", "registry.ProjectRegistry",
             ("com.minos.registry.ProjectRegistry",)),
            ("    public void wire(MinosApplicationComposer root) { }", "MinosApplication.wire", "root",
             "MinosApplicationComposer", ()),
            # AUD-ARC-17: a type parameter section hides neither the signature nor the types of its bounds.
            ("    public <T> void leakGeneric(ProjectRegistry r) { }", "MinosApplication.leakGeneric", "parameter r",
             "registry.ProjectRegistry", ("com.minos.registry.ProjectRegistry",)),
            ("    public static <T> MinosApplication of(T t, ProjectRegistry r) { return null; }",
             "MinosApplication.of", "parameter r", "registry.ProjectRegistry",
             ("com.minos.registry.ProjectRegistry",)),
            ("    public <T extends ProjectRegistry> void leakBound(T r) { }", "MinosApplication.leakBound",
             "parameter r", "registry.ProjectRegistry", ("com.minos.registry.ProjectRegistry",)),
            # No parameter: the bound counts as returned.
            ("    public <T extends java.util.Map<String, ProjectRegistry>> T nested() { return null; }",
             "MinosApplication.nested", "accessor returning", "registry.ProjectRegistry",
             ("com.minos.registry.ProjectRegistry",)),
            ("    public <T> MinosApplication(ProjectRegistry r) { }", "MinosApplication.MinosApplication",
             "parameter r", "registry.ProjectRegistry", ("com.minos.registry.ProjectRegistry",)),
            # A constructor has no return type: its bound is reported even without a parameter.
            ("    public <T extends ProjectRegistry> MinosApplication() { }", "MinosApplication.MinosApplication",
             "parameter <type parameters>", "registry.ProjectRegistry", ("com.minos.registry.ProjectRegistry",)),
        )
        for members, method, parameter, engine_type, imports in variants:
            with self.subTest(members=members):
                self.application_with(members, imports)
                self.refused(lambda: self.check_accessors({}), "A11", method, parameter, engine_type,
                             "package-private")

    def test_a_generic_method_returning_an_engine_type_is_refused(self):
        # AUD-ARC-17: the return type of a generic method is read like any other.
        self.application_with("    public <T> ProjectRegistry leakRet() { return null; }")
        self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.leakRet",
                     "new public accessor returning registry.ProjectRegistry")

    def test_a_public_static_method_returning_an_engine_type_is_refused(self):
        # AUD-ARC-17: a static method is no accessor of the table, and may not return an engine type either.
        variants = (
            ("    public static ProjectRegistry leakStatic() { return null; }", "MinosApplication.leakStatic"),
            ("    public static <T> ProjectRegistry leakStaticGeneric() { return null; }",
             "MinosApplication.leakStaticGeneric"),
            ("    public static <T extends ProjectRegistry> T leakStaticBound() { return null; }",
             "MinosApplication.leakStaticBound"),
        )
        for members, method in variants:
            with self.subTest(method=method):
                self.application_with(members)
                self.refused(lambda: self.check_accessors({}), "A11", method,
                             "public static method returning registry.ProjectRegistry", "package-private")
                self.assertEqual({}, RATCHETS.minos_application_accessors(self.root, "com.minos"))

    def test_a_public_static_composition_root_is_refused(self):
        for members in ("    public static MinosApplicationComposer root() { return null; }",
                        "    public static <T> MinosApplicationComposer root() { return null; }",
                        "    public static <T extends MinosApplicationComposer> T root() { return null; }"):
            with self.subTest(members=members):
                self.application_with(members)
                self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.root",
                             "returning MinosApplicationComposer, the composition root")

    def test_a_wildcard_import_of_an_engine_package_in_the_facade_is_refused(self):
        # AUD-ARC-17: a simple name imported by wildcard resolves to nothing, so the import itself is refused.
        self.application_with("    public void e(ProjectRegistry r) { }", ["com.minos.registry.*"])
        self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.java",
                     "imports com.minos.registry.*")
        self.application_with("    public void name(String value) { }", ["java.util.*"])
        self.assertEqual(0, self.check_accessors({}))

    def test_a_generic_signature_without_an_engine_type_is_accepted(self):
        self.application_with("    public <T extends Comparable<T>> java.util.List<T> sorted(java.util.List<T> values) "
                              "{ return values; }\n"
                              "    public static <T> java.util.List<T> none() { return null; }\n"
                              "    public <T> MinosApplication(T value) { }")
        self.assertEqual(0, self.check_accessors({}))
        self.assertEqual([], RATCHETS.minos_application_engine_parameters(self.root, "com.minos"))
        self.assertEqual({}, RATCHETS.minos_application_accessors(self.root, "com.minos"))

    def test_a_type_parameter_section_written_without_a_space_is_read(self):
        for members in ("    public<T> void leak(ProjectRegistry r) { }",
                        "    public<T> ProjectRegistry leak() { return null; }",
                        "    public static<T extends ProjectRegistry> void leak(T r) { }"):
            with self.subTest(members=members):
                self.application_with(members)
                self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.leak",
                             "registry.ProjectRegistry")

    def test_annotation_arguments_do_not_close_the_type_parameter_section(self):
        # Braces and '>' inside the arguments of an annotation belong to the annotation.
        self.application_with('    public <@Tag({"a", "b"}) T> void m(T value) { }\n'
                              '    public <T extends java.util.List<@Tag(v = {1, 2}) String>> void n(T value) { }')
        self.assertEqual(0, self.check_accessors({}))
        self.application_with("    public <@Tag(2 > 1) T> void leak(ProjectRegistry r) { }")
        self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.leak", "parameter r",
                     "registry.ProjectRegistry")

    def test_an_unbalanced_type_parameter_section_fails_closed(self):
        self.application_with("    public <T extends Comparable<T> void broken() { }")
        self.refused(lambda: self.check_accessors({}), "A11 unbalanced MinosApplication type parameters")

    def test_a_package_private_signature_or_a_non_engine_parameter_is_accepted(self):
        self.application_with("    void leak(ProjectRegistry registry) { }\n"
                              "    public void name(String value, java.util.List<String> more) { }\n"
                              "    // public void quiet(ProjectRegistry registry) { }")
        self.assertEqual(0, self.check_accessors({}))

    def test_the_builder_keeps_its_engine_parameters(self):
        self.application_with("    public static Builder builder() { return new Builder(); }\n"
                              "    public static final class Builder {\n"
                              "        public Builder projectRegistry(ProjectRegistry value) { return this; }\n"
                              "    }")
        self.assertEqual(0, self.check_accessors({}))
        self.assertEqual([], RATCHETS.minos_application_engine_parameters(self.root, "com.minos"))

    def test_the_real_application_takes_no_engine_type(self):
        self.assertEqual([], RATCHETS.minos_application_engine_parameters())

    def test_an_entry_added_to_the_empty_table_is_refused(self):
        self.write("minos-engine", "com.minos.registry", "ProjectRegistry", "public interface ProjectRegistry { }")
        self.write("minos-application", "com.minos.application", "MinosApplication",
                   "public final class MinosApplication {\n"
                   "    ProjectRegistry projectRegistry() { return null; }\n}",
                   ["com.minos.registry.ProjectRegistry"])
        self.assertEqual(0, self.check_accessors({}))
        self.refused(lambda: self.check_accessors({"projectRegistry": "registry.ProjectRegistry"}), "A11",
                     "projectRegistry", "remove it")

    def test_the_cli_no_longer_names_the_project_path_port(self):
        self.assertNotIn("registry.ProjectPathMappings", RATCHETS.ENGINE_TYPES_NAMED_BY_SURFACE["minos-cli"])


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

    def test_runtime_exec_through_a_variable_a_parameter_or_a_method_reference_is_refused(self):
        # AUD-ARC-05: `Runtime r = Runtime.getRuntime(); r.exec(...)` passed A12.
        bodies = {
            "Local": 'final class Local { void r() throws Exception { Runtime r = Runtime.getRuntime(); r.exec("x"); } }',
            "Inferred": 'final class Inferred { void r() throws Exception { var rt = Runtime.getRuntime();\n'
                        ' rt\n  .exec(new String[] {"x"}); } }',
            "Field": 'final class Field { private final Runtime runtime = Runtime.getRuntime();\n'
                     ' void r() throws Exception { this.runtime.exec("x"); } }',
            "Parameter": 'final class Parameter { void r(java.lang.Runtime host) throws Exception { host.exec("x"); } }',
            "Reference": 'final class Reference { Object f = Runtime.getRuntime()::exec; }',
        }
        for name, body in bodies.items():
            with self.subTest(launcher=name):
                self.write("minos-engine", "com.minos.io", name, body)
                self.refused(self.check, "A12", f"minos-engine/io.{name}", "is not in PROCESS_LAUNCHERS")
                self.assertEqual(2, self.check({**self.LISTED, f"minos-engine/io.{name}": 1}))
                (self.root / "minos-engine" / "src" / "main" / "java" / "com" / "minos" / "io" / f"{name}.java").unlink()

    def test_runtime_queries_without_exec_are_not_launchers(self):
        self.write("minos-engine", "com.minos.io", "Sizing",
                   "final class Sizing { int c = Runtime.getRuntime().availableProcessors(); "
                   "long m() { Runtime r = Runtime.getRuntime(); return r.maxMemory(); } "
                   "void x(Executor exec) { exec.execute(null); } }")
        self.assertEqual(1, self.check())

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
        self.assertEqual(len(RATCHETS.PROCESS_LAUNCHERS), _MODULE.check_process_launchers())

    def test_the_real_tree_launches_no_process_in_the_inner_layers(self):
        # SH-08: the icacls launches of private storage live in minos-runtime-local.
        self.assertEqual({}, dict(RATCHETS.PROCESS_LAUNCHERS))

    def test_icacls_back_in_private_storage_is_refused_against_the_real_empty_list(self):
        self.write("minos-engine", "com.minos.io", "PrivateLocalStorage",
                   'public final class PrivateLocalStorage { Object p = new ProcessBuilder("icacls.exe"); }')
        self.refused(lambda: self.check(dict(RATCHETS.PROCESS_LAUNCHERS)), "A12",
                     "minos-engine/io.PrivateLocalStorage", "is not in PROCESS_LAUNCHERS")


class EngineConcreteCodeMeasureTest(RatchetTreeTestCase):
    """AUD-ARC-05 — the share of concrete code of minos-engine is measured (ADR 0068, Mise en oeuvre), never ratcheted."""

    def test_each_file_counts_for_its_first_top_level_declaration_with_all_its_lines(self):
        self.write("minos-engine", "com.minos.io", "Store", "public final class Store {\n    int a;\n}")
        self.write("minos-engine", "com.minos.io", "Annotated",
                   '@SuppressWarnings("x") public abstract class Annotated { interface Nested { } }')
        self.write("minos-engine", "com.minos.io", "Port", "/** class in a comment */\npublic interface Port { }")
        self.write("minos-engine", "com.minos.io", "Row", "public record Row(int a) { static class Inner { } }")
        self.write("minos-engine", "com.minos.io", "Kind", "enum Kind { A }")
        self.write("minos-engine", "com.minos.io", "package-info", "")
        self.write("minos-engine", "com.minos.io", "Odd", "// nothing declared")
        self.write("minos-engine", "com.minos.io", "Ignored", "final class Ignored { }", kind="test")
        self.write("minos-domain", "com.minos.domain", "Elsewhere", "final class Elsewhere { }")
        measured = RATCHETS.engine_concrete_code(self.root)
        self.assertEqual({"class": (2, 10), "interface": (1, 5), "record": (1, 4), "enum": (1, 4), "info": (1, 4),
                          "unclassified": (1, 4)}, measured)

    def test_a_module_without_sources_measures_nothing(self):
        self.assertEqual({}, RATCHETS.engine_concrete_code(self.root))

    def test_the_real_engine_has_no_unclassified_file(self):
        measured = RATCHETS.engine_concrete_code()
        self.assertNotIn("unclassified", measured)
        self.assertGreater(measured["class"][0], 0)


class AdapterTypesNamedByAdapterRuleTest(RatchetTreeTestCase):
    """A13 — an adapter names another adapter's types only through the list of the couple (AUD-ARC-08)."""

    COUPLE = ("minos-provider-scip", "minos-storage")
    LISTED = {COUPLE: {"storage.local.store.SnapshotCodec": 1}}

    def setUp(self):
        super().setUp()
        self.write("minos-storage", "com.minos.storage.local.store", "SnapshotCodec", "public interface SnapshotCodec { }")
        self.write("minos-storage", "com.minos.storage.local.store", "SnapshotIntegrityService",
                   "public final class SnapshotIntegrityService { }")
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Store", "final class Store { SnapshotCodec c; }",
                   ["com.minos.storage.local.store.SnapshotCodec"])

    def check(self, listed=None, reasons=None):
        listed = self.LISTED if listed is None else listed
        reasons = {couple: "reviewed coupling" for couple in listed} if reasons is None else reasons
        return _MODULE.check_adapter_types_named_by_adapters(self.root, listed=listed, reasons=reasons)

    def test_a_conforming_tree_is_accepted(self):
        self.assertEqual(1, self.check())

    def test_an_imported_type_outside_the_list_is_refused_naming_both_modules(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Leak",
                   "final class Leak { SnapshotIntegrityService s; }",
                   ["com.minos.storage.local.store.SnapshotIntegrityService"])
        self.refused(self.check, "A13", "minos-provider-scip -> minos-storage",
                     "com.minos.storage.local.store.SnapshotIntegrityService")

    def test_a_qualified_name_without_import_is_refused_like_an_import(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Leak",
                   "final class Leak { Object s = new com.minos.storage.local.store.SnapshotIntegrityService(); }")
        self.refused(self.check, "A13", "minos-provider-scip -> minos-storage",
                     "com.minos.storage.local.store.SnapshotIntegrityService")

    def test_a_couple_absent_from_the_table_is_refused(self):
        self.write("minos-integration-git", "com.minos.integration.git", "Leak", "final class Leak { SnapshotCodec c; }",
                   ["com.minos.storage.local.store.SnapshotCodec"])
        self.refused(self.check, "A13", "minos-integration-git -> minos-storage")

    def test_a_dead_entry_is_refused(self):
        listed = {self.COUPLE: {"storage.local.store.SnapshotCodec": 1, "storage.local.store.SnapshotIntegrityService": 1}}
        self.refused(lambda: self.check(listed), "A13", "storage.local.store.SnapshotIntegrityService", "no longer named")

    def test_a_second_file_naming_a_listed_type_is_refused_with_both_counts(self):
        # AUD-ARC-08: a new adapter file naming an already listed type passed the type ratchet.
        self.write("minos-provider-scip", "com.minos.adapter.scip.runtime", "Payload",
                   "final class Payload { SnapshotCodec c; }", ["com.minos.storage.local.store.SnapshotCodec"])
        self.refused(self.check, "A13", "minos-provider-scip -> minos-storage",
                     "storage.local.store.SnapshotCodec", "listed for 1 file(s)", "used by 2",
                     "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/Payload.java")

    def test_a_count_above_the_observed_files_is_refused(self):
        listed = {self.COUPLE: {"storage.local.store.SnapshotCodec": 3}}
        self.refused(lambda: self.check(listed), "A13", "listed for 3 file(s)", "used by 1", "write 1")

    def test_a_new_scip_file_naming_the_listed_command_locator_is_refused_against_the_real_list(self):
        couple = ("minos-provider-scip", "minos-runtime-local")
        listed = {couple: {"runtime.local.process.CommandLocator": 1}, **self.LISTED}
        self.write("minos-runtime-local", "com.minos.runtime.local.process", "CommandLocator",
                   "public final class CommandLocator { }")
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Plan", "final class Plan { CommandLocator l; }",
                   ["com.minos.runtime.local.process.CommandLocator"])
        self.assertEqual(2, self.check(listed))
        self.write("minos-provider-scip", "com.minos.adapter.scip.go", "GoPlan", "final class GoPlan { CommandLocator l; }",
                   ["com.minos.runtime.local.process.CommandLocator"])
        self.refused(lambda: self.check(listed), "A13", "minos-provider-scip -> minos-runtime-local", "used by 2")
        self.assertEqual(10, RATCHETS.ADAPTER_TYPES_NAMED_BY_ADAPTER[couple]["runtime.local.process.CommandLocator"])

    def test_a_table_key_that_is_not_a_couple_of_adapters_is_refused(self):
        listed = {**self.LISTED, ("minos-cli", "minos-storage"): {}}
        self.refused(lambda: self.check(listed), "A13", "minos-cli -> minos-storage", "not a couple of adapters")

    def test_the_real_tree_names_only_listed_adapter_types(self):
        self.assertEqual(sum(len(types) for types in RATCHETS.ADAPTER_TYPES_NAMED_BY_ADAPTER.values()),
                         _MODULE.check_adapter_types_named_by_adapters())

    def test_a_file_store_created_again_by_the_scip_lifecycle_is_refused_without_a_storage_couple(self):
        # SH-06: the real table has no minos-provider-scip -> minos-storage couple any more.
        self.assertNotIn(self.COUPLE, RATCHETS.ADAPTER_TYPES_NAMED_BY_ADAPTER)
        self.write("minos-storage", "com.minos.storage.local.store", "FileSymbolSnapshotStore",
                   "public final class FileSymbolSnapshotStore { }")
        self.write("minos-provider-scip", "com.minos.adapter.scip.runtime", "ScipProjectSnapshotLifecycle",
                   "public final class ScipProjectSnapshotLifecycle { Object s = new FileSymbolSnapshotStore(); }",
                   ["com.minos.storage.local.store.FileSymbolSnapshotStore"])
        self.refused(lambda: self.check(listed={}, reasons={}), "A13", "minos-provider-scip -> minos-storage",
                     "storage.local.store.FileSymbolSnapshotStore",
                     "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipProjectSnapshotLifecycle.java")

    def test_a_listed_couple_without_a_reason_is_refused(self):
        self.refused(lambda: self.check(reasons={}), "A13", "ADAPTER_COUPLING_REASONS",
                     "minos-provider-scip -> minos-storage", "no written reason")

    def test_a_blank_reason_is_refused(self):
        self.refused(lambda: self.check(reasons={self.COUPLE: "  "}), "A13",
                     "minos-provider-scip -> minos-storage", "no written reason")

    def test_a_reason_without_a_listed_couple_is_refused(self):
        reasons = {self.COUPLE: "reviewed coupling", ("minos-integration-git", "minos-storage"): "stale"}
        self.refused(lambda: self.check(reasons=reasons), "A13", "minos-integration-git -> minos-storage",
                     "remove the reason")

    def test_every_real_couple_has_a_written_reason(self):
        self.assertEqual(set(RATCHETS.ADAPTER_TYPES_NAMED_BY_ADAPTER), set(RATCHETS.ADAPTER_COUPLING_REASONS))
        for couple, reason in RATCHETS.ADAPTER_COUPLING_REASONS.items():
            with self.subTest(couple=couple):
                self.assertTrue(reason.strip())


if __name__ == "__main__":
    unittest.main()

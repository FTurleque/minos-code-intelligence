#!/usr/bin/env python3
"""Self-test of the ratchet rules of check-module-boundaries.py: A11 (minos-engine types named by surfaces), A12
process launchers, A13 adapter types named by adapters, and the measured share of concrete minos-engine code.
The A11 reading of the MinosApplication facade is test_check_module_boundaries_facade.py.

Each case builds a throw-away source tree and runs one rule of the real script against it: a new use refused, a stale
entry refused, and the real tree checked against its tables.
"""

from __future__ import annotations

import sys
import unittest

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from module_boundaries_testing import (
    RATCHETS, RatchetTreeTestCase, _MODULE, annotated_before_the_last_part, cut_around_dots, escaped,
    second_on_its_line)


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

    PORTS = "com.minos.orchestration.IndexingRuntimePorts"

    def write_ports(self):
        self.write("minos-engine", "com.minos.orchestration", "IndexingRuntimePorts",
                   "public final class IndexingRuntimePorts { public interface IndexerExecutor { } }")

    def test_a_wildcard_import_of_an_engine_type_is_refused_in_a_surface_and_a_contract_module(self):
        # AUD-ARC-17: the member types brought in by the wildcard are written by their simple name only.
        self.write_ports()
        for module, target in (("minos-cli", self.PORTS), ("minos-nexus", self.PORTS),
                               ("minos-api", f"{self.PORTS}.IndexerExecutor")):
            package = "com.minos." + module.removeprefix("minos-")
            source = f"{module}/src/main/java/{package.replace('.', '/')}/Wide.java"
            with self.subTest(module=module, target=target):
                self.write(module, package, "Wide", "final class Wide { IndexerExecutor e; }", [f"{target}.*"])
                self.refused(self.check, "A11", f"{module}: wildcard import of a minos-engine package or type",
                             f"{source} -> {target}.*", f"uses {self.PORTS}, absent from ENGINE_TYPES_NAMED_BY_SURFACE")
                (self.root / source).unlink()

    def test_a_wildcard_import_of_a_listed_engine_type_is_refused_without_asking_to_remove_the_entry(self):
        self.write_ports()
        self.write("minos-cli", "com.minos.cli", "Wide", "final class Wide { IndexerExecutor e; }", [f"{self.PORTS}.*"])
        listed = {"minos-cli": {"query.SymbolResult": 1, "orchestration.IndexingRuntimePorts": 1}}
        message = self.refused(lambda: self.check(listed), "A11", "wildcard", f"Wide.java -> {self.PORTS}.*")
        self.assertNotIn("no longer named", message)
        self.assertNotIn("listed for", message)

    def test_wildcard_imports_outside_the_engine_and_static_ones_are_not_refused(self):
        self.write("minos-domain", "com.minos.domain", "Kind", "public final class Kind { public enum Level { LOW } }")
        self.write("minos-cli", "com.minos.cli", "Wide", "final class Wide { }",
                   ["java.util.Map.*", "com.minos.domain.Kind.*", "static com.minos.query.SymbolResult.*"])
        # The static wildcard names members of the listed type: a use of it, counted.
        self.assertEqual(1, self.check({"minos-cli": {"query.SymbolResult": 2}})["minos-cli"])

    def test_a_wildcard_import_following_another_on_its_line_is_refused(self):
        self.write("minos-cli", "com.minos.cli", "Wide", "final class Wide { HostedRole r; }",
                   [second_on_its_line("com.minos.hosted.*")])
        self.refused(self.check, "A11", "wildcard", "Wide.java -> com.minos.hosted.*")

    def test_a_name_cut_around_its_dots_is_read_in_a_surface(self):
        # AUD-ARC-17: blanks, line breaks and comments may surround the dots of a name.
        role = "com.minos.hosted.HostedRole"
        for form, written in cut_around_dots(role).items():
            for use, body, imports in (("import", "final class Cut { HostedRole r; }", [written]),
                                       ("qualified name", f"final class Cut {{ {written} r; }}", [])):
                with self.subTest(form=form, use=use):
                    self.write("minos-cli", "com.minos.cli", "Cut", body, imports)
                    self.refused(self.check, "A11", "Cut.java", f"uses {role}, absent from")

    def test_a_wildcard_import_cut_around_its_dots_is_refused(self):
        for form, written in cut_around_dots("com.minos.hosted.*").items():
            with self.subTest(form=form):
                self.write("minos-cli", "com.minos.cli", "Cut", "final class Cut { HostedRole r; }", [written])
                self.refused(self.check, "A11", "wildcard", "Cut.java -> com.minos.hosted.*")

    def test_a_cut_name_in_a_comment_or_a_literal_is_ignored_and_before_an_ellipsis_is_read(self):
        cut = "com . minos . hosted . HostedRole"
        self.write("minos-cli", "com.minos.cli", "Quiet", f'final class Quiet {{ // {cut}\n String s = "{cut}"; }}')
        self.assertEqual(1, self.check()["minos-cli"])
        self.write("minos-cli", "com.minos.cli", "Quiet", f"final class Quiet {{ void f({cut} ... roles) {{ }} }}")
        self.refused(self.check, "A11", "Quiet.java", "uses com.minos.hosted.HostedRole, absent from")

    def refused_bodies(self, bodies):
        for form, body in bodies.items():
            with self.subTest(form=form):
                self.write("minos-cli", "com.minos.cli", "Cut", f"final class Cut {{ {body} }}")
                self.refused(self.check, "A11", "Cut.java", "uses com.minos.hosted.HostedRole, absent from")

    def test_a_name_written_with_a_unicode_escape_is_read(self):
        # AUD-ARC-17: Java translates unicode escapes before it reads anything else.
        self.refused_bodies({
            "escaped dot": f"com.minos.hosted{escaped('.')}HostedRole r;",
            "escaped letter": f"com.minos.hosted.{escaped('H')}ostedRole r;"})

    def test_an_annotation_inside_a_qualified_name_does_not_hide_it(self):
        role = "com.minos.hosted.HostedRole"
        self.refused_bodies({form: f"{written} r;" for form, written in annotated_before_the_last_part(role).items()})

    def test_a_type_named_by_an_annotation_inside_a_name_is_still_read(self):
        self.refused_bodies({
            "annotation type": "java.util.@com.minos.hosted.HostedRole List r;",
            "annotation argument": "java.util.@Tag(com.minos.hosted.HostedRole.class) List r;"})

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

    def test_a_launcher_written_with_a_unicode_escape_is_refused(self):
        # AUD-ARC-17: an escape hides no word, and an escaped line break ends a line comment.
        bodies = {"EscapedLetter": f'Object p = new {escaped("P")}rocessBuilder("x");',
                  "EscapedBreak": f'// {escaped(chr(10))} Object p = new ProcessBuilder("x");\n'}
        for name, body in bodies.items():
            with self.subTest(launcher=name):
                self.write("minos-engine", "com.minos.io", name, f"final class {name} {{ {body} }}")
                self.refused(self.check, "A12", f"minos-engine/io.{name}", "is not in PROCESS_LAUNCHERS")

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

    def test_a_wildcard_import_of_a_type_of_another_adapter_is_refused(self):
        # AUD-ARC-17: a nested type counts as its top-level type, and the wildcard that brings it in is refused.
        codec = "com.minos.storage.local.store.SnapshotCodec"
        for target in (codec, f"{codec}.Version"):
            with self.subTest(target=target):
                self.write("minos-provider-scip", "com.minos.adapter.scip", "Store", "final class Store { }",
                           [f"{target}.*"])
                source = "minos-provider-scip/src/main/java/com/minos/adapter/scip/Store.java"
                message = self.refused(self.check, "A13", "minos-provider-scip -> minos-storage: wildcard import",
                                       f"{source} -> {target}.*")
                self.assertNotIn("no longer named", message)

    def test_a_wildcard_import_of_another_adapter_following_another_on_its_line_is_refused(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Wide", "final class Wide { }",
                   [second_on_its_line("com.minos.storage.local.store.*")])
        self.refused(self.check, "A13", "wildcard", "Wide.java -> com.minos.storage.local.store.*")

    def test_a_name_of_another_adapter_cut_around_its_dots_is_read(self):
        # AUD-ARC-17: blanks, line breaks and comments may surround the dots of a name.
        service = "com.minos.storage.local.store.SnapshotIntegrityService"
        for form, written in cut_around_dots(service).items():
            for use, body, imports in (("import", "final class Cut { SnapshotIntegrityService s; }", [written]),
                                       ("qualified name", f"final class Cut {{ {written} s; }}", [])):
                with self.subTest(form=form, use=use):
                    self.write("minos-provider-scip", "com.minos.adapter.scip", "Cut", body, imports)
                    self.refused(self.check, "A13", "minos-provider-scip -> minos-storage", service)

    def test_a_wildcard_import_of_another_adapter_cut_around_its_dots_is_refused(self):
        package = "com.minos.storage.local.store"
        for form, written in cut_around_dots(f"{package}.*").items():
            with self.subTest(form=form):
                self.write("minos-provider-scip", "com.minos.adapter.scip", "Cut", "final class Cut { }", [written])
                self.refused(self.check, "A13", "wildcard", f"Cut.java -> {package}.*")

    def test_a_name_of_another_adapter_interrupted_by_an_annotation_is_read(self):
        service = "com.minos.storage.local.store.SnapshotIntegrityService"
        for form, written in annotated_before_the_last_part(service).items():
            with self.subTest(form=form):
                self.write("minos-provider-scip", "com.minos.adapter.scip", "Cut", f"final class Cut {{ {written} s; }}")
                self.refused(self.check, "A13", "minos-provider-scip -> minos-storage", service)

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

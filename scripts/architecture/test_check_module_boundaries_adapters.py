#!/usr/bin/env python3
"""Self-test of the adapter isolation rules of check-module-boundaries.py: A2 sources, A10, A14, A16, A18, A19, A20,
R1/R2 and the storage packages.

Each case builds a throw-away source tree and runs one rule of the real script against it: one case refused and one
accepted per rule, and the real tree checked against its tables.
"""

from __future__ import annotations

import sys
import tempfile
import unittest

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from module_boundaries_testing import (
    ADAPTERS, MODULES, RATCHETS, RatchetTreeTestCase, _MODULE, annotated_before_the_last_part, cut_around_dots,
    escaped, second_on_its_line, write_root_pom)


class SourceBoundaryRuleTest(RatchetTreeTestCase):
    """A2: the POM rules do not see sources, so a source naming an adapter class is refused."""

    ADAPTER_CLASS = "com.minos.storage.local.store.FileStore"

    def setUp(self):
        super().setUp()
        self.write("minos-storage", "com.minos.storage.local.store", "FileStore", "public final class FileStore { "
                   "public static FileStore create() { return new FileStore(); } }")

    def refused(self, *fragments):
        with self.assertRaises(RuntimeError) as raised:
            _MODULE.check_source_boundaries(self.root)
        self.assertIn("A2 source boundary violated", str(raised.exception))
        for fragment in fragments:
            self.assertIn(fragment, str(raised.exception))

    def test_a_single_import_is_refused(self):
        self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { FileStore s; }", [self.ADAPTER_CLASS])
        self.refused("minos-cli/src/main/java/com/minos/cli/Cmd.java", self.ADAPTER_CLASS, "minos-storage")

    def test_a_static_import_is_refused(self):
        self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { }", [f"static {self.ADAPTER_CLASS}.create"])
        self.refused(self.ADAPTER_CLASS)

    def test_a_wildcard_import_of_a_package_holding_adapter_classes_is_refused(self):
        self.write("minos-api", "com.minos.api", "Facade", "class Facade { }", ["com.minos.storage.local.store.*"])
        self.refused("com.minos.storage.local.store.*")

    def test_a_wildcard_import_on_the_members_of_an_adapter_class_is_refused(self):
        # AUD-ARC-17: the member types brought in by the wildcard are written by their simple name only.
        for target in (self.ADAPTER_CLASS, f"{self.ADAPTER_CLASS}.Entry"):
            with self.subTest(target=target):
                self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { Entry e; }", [f"{target}.*"])
                self.refused(f"minos-cli/src/main/java/com/minos/cli/Cmd.java -> {self.ADAPTER_CLASS} [minos-storage]")

    def test_a_wildcard_import_on_the_members_of_a_domain_type_is_accepted(self):
        self.write("minos-domain", "com.minos.domain", "Kind", "public final class Kind { public enum Level { LOW } }")
        self.write("minos-cli", "com.minos.cli", "Cmd", "class Cmd { Level l; }", ["com.minos.domain.Kind.*"])
        _MODULE.check_source_boundaries(self.root)

    def test_a_wildcard_import_following_another_on_its_line_is_refused(self):
        self.write("minos-api", "com.minos.api", "Facade", "class Facade { }",
                   [second_on_its_line("com.minos.storage.local.store.*")])
        self.refused("com.minos.storage.local.store.*")

    def test_a_name_cut_around_its_dots_is_refused(self):
        # AUD-ARC-17: blanks, line breaks and comments may surround the dots of a name.
        package = "com.minos.storage.local.store"
        for use, name, body, expected in (
                ("import", self.ADAPTER_CLASS, "class Cmd {{ FileStore s; }}", self.ADAPTER_CLASS),
                ("wildcard", f"{package}.*", "class Cmd {{ }}", f"{package}.*"),
                ("qualified name", self.ADAPTER_CLASS, "class Cmd {{ {} s; }}", self.ADAPTER_CLASS)):
            for form, written in cut_around_dots(name).items():
                with self.subTest(use=use, form=form):
                    imports = [] if use == "qualified name" else [written]
                    self.write("minos-cli", "com.minos.cli", "Cmd", body.format(written), imports)
                    self.refused(f"minos-cli/src/main/java/com/minos/cli/Cmd.java -> {expected}")

    def refused_body(self, body):
        self.write("minos-cli", "com.minos.cli", "Cmd", f"class Cmd {{ {body} }}")
        self.refused(f"minos-cli/src/main/java/com/minos/cli/Cmd.java -> {self.ADAPTER_CLASS}")

    def test_a_name_written_with_a_unicode_escape_is_refused(self):
        # AUD-ARC-17: Java translates unicode escapes before it reads anything else.
        package = "com.minos.storage.local.store"
        for form, written in (("dot", f"{package}{escaped('.')}FileStore"),
                              ("letter", f"{package}.{escaped('F')}ileStore"),
                              ("several u", f"{package}{chr(92)}uuu002eFileStore")):
            with self.subTest(form=form):
                self.refused_body(f"{written} s;")

    def test_an_escaped_line_break_ends_a_line_comment(self):
        for end in (chr(10), chr(13)):
            with self.subTest(end=ord(end)):
                self.refused_body(f"// {escaped(end)} {self.ADAPTER_CLASS} s;\n")

    def test_an_annotation_holding_an_annotated_name_is_read_once_and_in_full(self):
        # In the arguments: a name left whole, then the name the rule refuses.
        package = "com.minos.storage.local.store"
        self.refused_body(f"{package}.@Tag(v = java.util.@Other List.class) FileStore s;")
        self.refused_body(f"java.util.@Tag({package}.@Other FileStore.class) List l;")

    def test_an_annotation_whose_arguments_do_not_close_fails_the_guard(self):
        self.write("minos-cli", "com.minos.cli", "Cmd",
                   "class Cmd { com.minos.storage.local.store.@Tag(1 FileStore s; }")
        with self.assertRaisesRegex(RuntimeError, "unbalanced annotation inside a qualified name in Cmd.java"):
            _MODULE.check_source_boundaries(self.root)

    def test_an_escaped_backslash_opens_no_escape(self):
        self.write("minos-cli", "com.minos.cli", "Cmd",
                   f"class Cmd {{ // {chr(92)}{escaped(chr(10))} {self.ADAPTER_CLASS} s;\n }}")
        _MODULE.check_source_boundaries(self.root)

    def test_a_name_interrupted_by_an_annotation_is_refused(self):
        for form, written in annotated_before_the_last_part(self.ADAPTER_CLASS).items():
            with self.subTest(form=form):
                self.refused_body(f"{written} s;")

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

    def test_a_wildcard_import_following_another_on_its_line_is_refused(self):
        self.write("minos-bootstrap", "com.minos.bootstrap", "Leak", "class Leak { }",
                   [second_on_its_line(f"{self.PREFIX}.sandbox.*")])
        self.refused("Leak.java", f"{self.PREFIX}.sandbox.*")

    def test_a_name_cut_around_its_dots_is_refused(self):
        job = f"{self.PREFIX}.sandbox.LinuxCgroupJob"
        for use, name, body in (("import", job, "class Leak {{ }}"),
                                ("wildcard", f"{self.PREFIX}.sandbox.*", "class Leak {{ }}"),
                                ("qualified name", job, "class Leak {{ {} j; }}")):
            for form, written in cut_around_dots(name).items():
                with self.subTest(use=use, form=form):
                    imports = [] if use == "qualified name" else [written]
                    self.write("minos-cli", "com.minos.cli", "Leak", body.format(written), imports)
                    self.refused(f"Leak.java -> {name}")

    def test_a_name_written_with_a_unicode_escape_is_refused(self):
        job = f"{self.PREFIX}.sandbox.LinuxCgroupJob"
        self.write("minos-cli", "com.minos.cli", "Leak",
                   f"class Leak {{ {self.PREFIX}.sandbox{escaped('.')}LinuxCgroupJob j; }}")
        self.refused(f"Leak.java -> {job}")

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
        self.assertEqual(len(ADAPTERS.RUNTIME_LOCAL_EXPORTED_TYPES), _MODULE.check_runtime_local_exports())


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

    def test_a_call_written_with_a_unicode_escape_is_refused(self):
        # AUD-ARC-17: an escape hides no word from a rule.
        self.source("main", "Escaped", f"Object s = {escaped('S')}erviceLoader.load(Escaped.class);")
        with self.assertRaisesRegex(RuntimeError, "A16 .*Escaped.java"):
            self.check()

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
        self.assertEqual(6, _MODULE.check_service_loader_class_loaders())


class AssemblyClassesRuleTest(RatchetTreeTestCase):
    """A20 — the production classes of minos-app are a closed list, each with its role (AUD-ARC-05)."""

    LISTED = {"app.McpBackendRouter": "launch route", "integration.nexus.NexusExportBridgeMain": "bridge"}

    def setUp(self):
        super().setUp()
        self.write("minos-app", "com.minos.app", "McpBackendRouter", "final class McpBackendRouter { }")
        self.write("minos-app", "com.minos.integration.nexus", "NexusExportBridgeMain",
                   "public final class NexusExportBridgeMain { }")
        self.write("minos-app", "com.minos.app", "Helper", "final class Helper { }", kind="test")

    def check(self, listed=None):
        return _MODULE.check_assembly_classes(self.root, listed=self.LISTED if listed is None else listed)

    def test_the_listed_classes_are_accepted_and_test_classes_are_not_counted(self):
        self.assertEqual(2, self.check())

    def test_a_new_production_class_is_refused_with_its_name_and_the_advice(self):
        self.write("minos-app", "com.minos.app", "LaunchPolicy", "final class LaunchPolicy { }")
        self.refused(self.check, "A20", "minos-app/src/main/java/com/minos/app/LaunchPolicy.java",
                     "app.LaunchPolicy", "application module or a surface")

    def test_a_stale_entry_is_refused_with_the_request_to_remove_it(self):
        self.refused(lambda: self.check({**self.LISTED, "app.Gone": "launch route"}), "A20",
                     "ASSEMBLY_CLASSES entry app.Gone is no class of minos-app any more; remove it")

    def test_an_entry_without_a_role_is_refused(self):
        self.refused(lambda: self.check({**self.LISTED, "app.McpBackendRouter": " "}), "A20",
                     "ASSEMBLY_CLASSES entry app.McpBackendRouter has no written role")

    def test_the_real_assembly_matches_its_list(self):
        self.assertEqual(len(ADAPTERS.ASSEMBLY_CLASSES), _MODULE.check_assembly_classes())
        self.assertEqual(8, len(ADAPTERS.ASSEMBLY_CLASSES))


class EmbeddedAdapterRuleTest(RatchetTreeTestCase):
    """A19 — minos-application names a JDK infrastructure API only in listed files, and an embedded adapter class only
    from its family (AUD-ARC-01, ADR 0087)."""

    API_USERS = frozenset({"application.semantic.OllamaEmbeddingProvider", "program.analysis.JavaAstParser"})
    ADAPTERS = {"OllamaEmbeddingProvider": "learned embedding client", "JavaAstParser": "Java analyser",
                "FileProgramGraphProvider": "sidecar reader"}

    def setUp(self):
        super().setUp()
        self.write("minos-application", "com.minos.application.semantic", "OllamaEmbeddingProvider",
                   "public final class OllamaEmbeddingProvider { java.net.HttpURLConnection c; }")
        self.write("minos-application", "com.minos.program.analysis", "JavaAstParser",
                   "final class JavaAstParser { JavacTask t; }", imports=("com.sun.source.util.JavacTask",))
        self.write("minos-application", "com.minos.program.analysis", "FileProgramGraphProvider",
                   "public final class FileProgramGraphProvider { JavaAstParser parser; }")
        self.write("minos-application", "com.minos.program.analysis", "ProgramGraphService",
                   "public final class ProgramGraphService { /** Uses a FileProgramGraphProvider. */ }")

    def check(self, api_users=None, adapters=None):
        return _MODULE.check_embedded_adapters(
            self.root, api_users=self.API_USERS if api_users is None else api_users,
            adapters=self.ADAPTERS if adapters is None else adapters)

    def test_the_listed_files_and_classes_are_accepted_and_the_family_names_itself(self):
        self.assertEqual((2, 3), self.check())

    def refused_client(self, body, imports, named):
        self.write("minos-application", "com.minos.application", "RemoteClient",
                   f"final class RemoteClient {{ {body} }}", imports=imports)
        self.refused(self.check, "A19", f"RemoteClient.java names {named}", "not in INFRASTRUCTURE_API_USERS")

    def test_a_wildcard_import_of_an_infrastructure_package_is_refused(self):
        # AUD-ARC-17: the types brought in by the wildcard are written by their simple name only.
        for package in ("java.net.http", "javax.tools", "com.sun.source", "com.sun.source.tree"):
            with self.subTest(package=package):
                self.refused_client("", (f"{package}.*",), f"{package}.*")

    def test_the_http_connection_under_a_wildcard_of_java_net_is_refused(self):
        self.refused_client("HttpURLConnection c;", ("java.net.*",), "java.net.HttpURLConnection")

    def test_a_wildcard_of_java_net_without_the_http_connection_is_accepted(self):
        self.write("minos-application", "com.minos.application", "Address", "final class Address { URI u; }",
                   imports=("java.net.*",))
        self.assertEqual((2, 3), self.check())

    def test_an_infrastructure_name_written_otherwise_is_refused(self):
        for form, body in (("annotation", "javax.tools.@Ann JavaCompiler c;"),
                           ("escape", f"javax.tools{escaped('.')}JavaCompiler c;")):
            with self.subTest(form=form):
                self.refused_client(body, (), "javax.tools.JavaCompiler")

    def test_a_new_http_client_of_the_application_is_refused_with_the_file_and_the_api(self):
        self.write("minos-application", "com.minos.application", "RemoteClient",
                   "final class RemoteClient { HttpClient client; }", imports=("java.net.http.HttpClient",))
        self.refused(self.check, "A19", "minos-application/src/main/java/com/minos/application/RemoteClient.java",
                     "java.net.http.HttpClient", "not in INFRASTRUCTURE_API_USERS")

    def test_a_listed_file_that_no_longer_names_an_api_is_refused_as_stale(self):
        self.write("minos-application", "com.minos.program.analysis", "JavaAstParser", "final class JavaAstParser { }")
        self.refused(self.check, "A19", "INFRASTRUCTURE_API_USERS entry program.analysis.JavaAstParser",
                     "remove it")

    def test_an_api_mentioned_in_a_comment_or_a_literal_only_is_accepted(self):
        self.write("minos-application", "com.minos.application", "Notes",
                   '/** Not {@link javax.tools.ToolProvider}. */ final class Notes { String s = "javax.tools.X"; }')
        self.assertEqual((2, 3), self.check())

    def test_the_service_naming_an_adapter_again_is_refused_with_the_file_and_the_class(self):
        self.write("minos-application", "com.minos.program.analysis", "ProgramGraphService",
                   "public final class ProgramGraphService { Object p = new FileProgramGraphProvider(); }")
        self.refused(self.check, "A19", "program/analysis/ProgramGraphService.java", "FileProgramGraphProvider",
                     "MinosApplicationComposer")

    def test_a_qualified_name_of_an_adapter_is_refused(self):
        self.write("minos-application", "com.minos.application", "Wiring",
                   "final class Wiring { Object p = new com.minos.application.semantic.OllamaEmbeddingProvider(); }")
        self.refused(self.check, "A19", "application/Wiring.java", "OllamaEmbeddingProvider")

    def test_an_adapter_entry_without_a_class_is_refused_as_stale(self):
        self.refused(lambda: self.check(adapters={**self.ADAPTERS, "JavaTaintAnalyzer": "Java analyser"}), "A19",
                     "EMBEDDED_ADAPTER_CLASSES entry JavaTaintAnalyzer is no class of minos-application", "remove it")

    def test_an_adapter_entry_without_a_justification_is_refused(self):
        self.refused(lambda: self.check(adapters={**self.ADAPTERS, "FileProgramGraphProvider": " "}), "A19",
                     "EMBEDDED_ADAPTER_CLASSES entry FileProgramGraphProvider has no written justification")

    def without_ollama(self):
        return {name: reason for name, reason in self.ADAPTERS.items() if name != "OllamaEmbeddingProvider"}

    def test_an_api_user_missing_from_the_adapters_is_refused_with_the_class_and_the_list(self):
        self.refused(lambda: self.check(adapters=self.without_ollama()), "A19",
                     "OllamaEmbeddingProvider names java.net.HttpURLConnection",
                     "missing from EMBEDDED_ADAPTER_CLASSES")

    def test_a_stale_api_user_missing_from_the_adapters_is_refused_as_stale_only(self):
        with self.assertRaises(RuntimeError) as raised:
            self.check(api_users=self.API_USERS | {"program.analysis.Gone"})
        message = str(raised.exception)
        self.assertIn("INFRASTRUCTURE_API_USERS entry program.analysis.Gone names no infrastructure API", message)
        self.assertNotIn("missing from EMBEDDED_ADAPTER_CLASSES", message)

    def test_an_api_user_missing_from_the_adapters_is_refused_even_when_another_source_names_it(self):
        self.write("minos-application", "com.minos.application", "MinosApplicationRuntimeConfiguration",
                   "final class MinosApplicationRuntimeConfiguration { OllamaEmbeddingProvider provider; }",
                   imports=("com.minos.application.semantic.OllamaEmbeddingProvider",))
        self.refused(lambda: self.check(adapters=self.without_ollama()), "A19",
                     "OllamaEmbeddingProvider names java.net.HttpURLConnection",
                     "missing from EMBEDDED_ADAPTER_CLASSES")

    def test_every_api_user_listed_as_an_adapter_is_accepted_and_an_adapter_may_name_no_api(self):
        self.assertTrue({name.rsplit(".", 1)[-1] for name in self.API_USERS} <= set(self.ADAPTERS))
        self.assertEqual((2, 3), self.check())

    def test_the_real_tree_matches_both_tables(self):
        self.assertEqual((len(ADAPTERS.INFRASTRUCTURE_API_USERS), len(ADAPTERS.EMBEDDED_ADAPTER_CLASSES)),
                         _MODULE.check_embedded_adapters())
        self.assertEqual(10, len(ADAPTERS.INFRASTRUCTURE_API_USERS))
        self.assertEqual(17, len(ADAPTERS.EMBEDDED_ADAPTER_CLASSES))


class StoragePackageIsolationRuleTest(RatchetTreeTestCase):
    """SH-03 (E04), ADR 0068 section 4 — inside minos-storage, production and test sources: the two backends do not
    name each other, and the shared codec and path-mapping packages name neither backend, nor JDBC."""

    def setUp(self):
        super().setUp()
        self.write("minos-storage", "com.minos.storage.codec", "SnapshotCodec", "public interface SnapshotCodec { }")
        self.write("minos-storage", "com.minos.storage.home", "ProjectPathMappingStore",
                   "public final class ProjectPathMappingStore { }")
        self.write("minos-storage", "com.minos.storage.local.store", "FileSymbolSnapshotStore",
                   "public final class FileSymbolSnapshotStore { SnapshotCodec c; ProjectPathMappingStore m; }",
                   ["com.minos.storage.codec.SnapshotCodec", "com.minos.storage.home.ProjectPathMappingStore"])
        self.write("minos-storage", "com.minos.storage.postgresql.snapshot", "Store",
                   "final class Store { SnapshotCodec c; java.sql.Connection connection; }",
                   ["com.minos.storage.codec.SnapshotCodec"])

    def check(self):
        return _MODULE.check_storage_package_isolation(self.root)

    def test_a_conforming_tree_is_accepted(self):
        self.assertEqual(4, self.check())

    def test_a_postgresql_test_importing_the_local_backend_is_refused(self):
        self.write("minos-storage", "com.minos.storage.postgresql.snapshot", "StoreTest",
                   "final class StoreTest { FileSymbolSnapshotStore s; }",
                   ["com.minos.storage.local.store.FileSymbolSnapshotStore"], kind="test")
        self.refused(self.check, "postgresql -> local",
                     "minos-storage/src/test/java/com/minos/storage/postgresql/snapshot/StoreTest.java",
                     "com.minos.storage.local.store.FileSymbolSnapshotStore")

    def test_the_local_backend_naming_postgresql_by_qualified_name_is_refused(self):
        self.write("minos-storage", "com.minos.storage.local", "Leak",
                   "final class Leak { Object p = new com.minos.storage.postgresql.support.PostgresJdbcUrlPolicy(); }")
        self.refused(self.check, "local -> postgresql", "minos-storage/src/main/java/com/minos/storage/local/Leak.java",
                     "com.minos.storage.postgresql.support.PostgresJdbcUrlPolicy")

    def test_the_local_backend_naming_the_driver_is_refused(self):
        self.write("minos-storage", "com.minos.storage.local", "Driver", "final class Driver { }",
                   ["static org.postgresql.Driver.parseURL"])
        self.refused(self.check, "local -> postgresql", "org.postgresql.Driver.parseURL")

    def test_the_codec_importing_jdbc_is_refused(self):
        self.write("minos-storage", "com.minos.storage.codec", "Jdbc", "final class Jdbc { Connection c; }",
                   ["java.sql.Connection"])
        self.refused(self.check, "codec -> backends", "minos-storage/src/main/java/com/minos/storage/codec/Jdbc.java",
                     "java.sql.Connection")

    def test_a_package_below_the_storage_root_outside_minos_storage_is_refused(self):
        # AUD-ARC-12: com.minos.storage.<x> names minos-storage; only the root package itself belongs to the engine.
        self.write("minos-engine", "com.minos.storage", "StorageBackend", "public interface StorageBackend { }")
        self.assertEqual(4, self.check())
        for module, kind in (("minos-engine", "main"), ("minos-application", "main"), ("minos-bootstrap", "test")):
            with self.subTest(module=module, kind=kind):
                self.write(module, "com.minos.storage.cache", "Cache", "final class Cache { }", kind=kind)
                self.refused(self.check, "below com.minos.storage outside minos-storage",
                             f"{module}/src/{kind}/java/com/minos/storage/cache/Cache.java")
                (self.root / module / "src" / kind / "java" / "com" / "minos" / "storage" / "cache" / "Cache.java").unlink()

    def test_the_path_mapping_naming_the_local_registry_is_refused(self):
        self.write("minos-storage", "com.minos.storage.home", "Mapping", "final class Mapping { }",
                   ["com.minos.storage.local.registry.*"])
        self.refused(self.check, "home -> backends", "com.minos.storage.local.registry.*")

    def test_a_wildcard_import_following_another_on_its_line_is_refused(self):
        self.write("minos-storage", "com.minos.storage.home", "Mapping", "final class Mapping { }",
                   [second_on_its_line("com.minos.storage.local.registry.*")])
        self.refused(self.check, "home -> backends", "Mapping.java", "com.minos.storage.local.registry.*")

    def test_a_name_cut_around_its_dots_is_refused(self):
        registry = "com.minos.storage.local.registry"
        for use, name, body in (("import", f"{registry}.Registry", "final class Mapping {{ }}"),
                                ("wildcard", f"{registry}.*", "final class Mapping {{ }}"),
                                ("qualified name", f"{registry}.Registry", "final class Mapping {{ {} r; }}")):
            for form, written in cut_around_dots(name).items():
                with self.subTest(use=use, form=form):
                    imports = [] if use == "qualified name" else [written]
                    self.write("minos-storage", "com.minos.storage.home", "Mapping", body.format(written), imports)
                    self.refused(self.check, "home -> backends", f"Mapping.java -> {name}")

    def test_a_name_written_with_a_unicode_escape_is_refused(self):
        registry = "com.minos.storage.local.registry"
        self.write("minos-storage", "com.minos.storage.home", "Mapping",
                   f"final class Mapping {{ {registry}{escaped('.')}Registry r; }}")
        self.refused(self.check, "home -> backends", f"Mapping.java -> {registry}.Registry")

    def test_a_codec_test_naming_the_postgresql_backend_is_refused(self):
        self.write("minos-storage", "com.minos.storage.codec", "CodecTest",
                   "final class CodecTest { Object p = new com.minos.storage.postgresql.PostgresStorageBackend(); }",
                   kind="test")
        self.refused(self.check, "codec -> backends", "src/test/java/com/minos/storage/codec/CodecTest.java")

    def test_a_mention_in_a_comment_or_a_literal_is_accepted(self):
        self.write("minos-storage", "com.minos.storage.local", "Doc",
                   'final class Doc { /** {@code com.minos.storage.postgresql.PostgresStorageBackend} */\n'
                   ' String s = "java.sql.Connection"; }')
        self.write("minos-storage", "com.minos.storage.codec", "Doc",
                   'final class Doc { // com.minos.storage.local.store.FileSymbolSnapshotStore\n }')
        self.check()

    def test_the_shared_packages_naming_javax_sql_is_refused(self):
        self.write("minos-storage", "com.minos.storage.home", "Pool", "final class Pool { DataSource d; }",
                   ["javax.sql.DataSource"])
        self.refused(self.check, "home -> backends", "javax.sql.DataSource")

    def test_a_storage_package_outside_the_closed_list_is_refused(self):
        self.write("minos-storage", "com.minos.storage.bridge", "Bridge", "final class Bridge { }")
        self.write("minos-storage", "com.minos.storage.bridgetest", "BridgeTest", "final class BridgeTest { }",
                   kind="test")
        self.refused(self.check, "storage package outside codec, home, local, postgresql",
                     "minos-storage/src/main/java/com/minos/storage/bridge/Bridge.java",
                     "minos-storage/src/test/java/com/minos/storage/bridgetest/BridgeTest.java")

    def test_a_package_whose_name_only_starts_like_a_forbidden_one_is_accepted(self):
        self.write("minos-storage", "com.minos.storage.local", "Uses", "final class Uses { Text t; }",
                   ["org.postgresqlish.Text", "java.sqlite.Row"])
        self.check()

    def test_the_real_tree_keeps_the_storage_packages_apart(self):
        self.assertEqual(4, _MODULE.check_storage_package_isolation())


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
        self.assertEqual(sum(len(roots) for roots in RATCHETS.PACKAGE_ROOTS_BY_MODULE.values()),
                         _MODULE.check_package_roots())


class SnapshotReadPortRuleTest(RatchetTreeTestCase):
    """A18 — only the core names the in-memory index; the deprecated view stays in its closed list (AUD-ARC-07)."""

    MODULES = ("minos-engine", "minos-storage", "minos-application")
    LISTED = frozenset({
        "minos-storage/storage.local.store.FileSymbolSnapshotStore",
        "minos-storage/storage.postgresql.snapshot.PostgresCodeKnowledgeSnapshotStore",
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
        self.write("minos-storage", "com.minos.storage.local.store", "FileSymbolSnapshotStore",
                   "public final class FileSymbolSnapshotStore {\n"
                   "    public Optional<SnapshotQueryView> loadActiveQueryView(UUID id) { return null; }\n}",
                   ["com.minos.store.SnapshotQueryView"])
        self.write("minos-storage", "com.minos.storage.postgresql.snapshot",
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

    def test_a_word_written_with_a_unicode_escape_is_refused(self):
        # AUD-ARC-17: an escape hides no word from a rule.
        self.write("minos-application", "com.minos.application", "Escaped",
                   f"final class Escaped {{ {escaped('I')}nMemoryCodeKnowledgeStore s; }}")
        self.refused(self.check, "A18", "Escaped.java", "InMemoryCodeKnowledgeStore")

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
        self.write("minos-storage", "com.minos.storage.local.store", "Ingest",
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
        self.assertEqual(len(RATCHETS.LEGACY_SNAPSHOT_VIEW_USERS), _MODULE.check_snapshot_read_port())


class PostgresqlBackendIsolationRuleTest(RatchetTreeTestCase):
    """R1 / R2 (E04): the composition root and the SCIP provider never name the PostgreSQL backend nor its driver."""

    BACKEND = "com.minos.storage.postgresql.PostgresStorageBackend"

    def setUp(self):
        super().setUp()
        self.write("minos-storage", "com.minos.storage.postgresql", "PostgresStorageBackend",
                   "public final class PostgresStorageBackend { public static Object open() { return null; } }")
        self.write("minos-bootstrap", "com.minos.bootstrap", "Composer", "final class Composer { }", ["java.util.List"])

    def check(self):
        return _MODULE.check_postgresql_backend_isolation(self.root)

    def test_a_conforming_tree_is_accepted(self):
        self.assertEqual(2, self.check())

    def test_an_import_of_the_backend_in_the_composition_root_is_refused(self):
        self.write("minos-bootstrap", "com.minos.bootstrap", "Leak", "final class Leak { PostgresStorageBackend b; }",
                   [self.BACKEND])
        self.refused(self.check, "R1", "minos-bootstrap/src/main/java/com/minos/bootstrap/Leak.java", self.BACKEND)

    def test_static_and_wildcard_imports_are_refused(self):
        self.write("minos-bootstrap", "com.minos.bootstrap", "Static", "final class Static { }", [f"static {self.BACKEND}.open"])
        self.write("minos-bootstrap", "com.minos.bootstrap", "Wild", "final class Wild { }", ["com.minos.storage.postgresql.*"])
        self.refused(self.check, "Static.java", "Wild.java", "com.minos.storage.postgresql.*")

    def test_a_wildcard_import_following_another_on_its_line_is_refused(self):
        for target in ("com.minos.storage.postgresql", "org.postgresql"):
            with self.subTest(target=target):
                self.write("minos-bootstrap", "com.minos.bootstrap", "Wild", "final class Wild { }",
                           [second_on_its_line(f"{target}.*")])
                self.refused(self.check, "R1", "Wild.java", f"{target}.*")

    def test_a_name_cut_around_its_dots_is_refused(self):
        for use, name, body in (("import", self.BACKEND, "final class Cut {{ }}"),
                                ("wildcard", "org.postgresql.*", "final class Cut {{ }}"),
                                ("qualified name", "org.postgresql.Driver", "final class Cut {{ {} d; }}")):
            for form, written in cut_around_dots(name).items():
                with self.subTest(use=use, form=form):
                    imports = [] if use == "qualified name" else [written]
                    self.write("minos-bootstrap", "com.minos.bootstrap", "Cut", body.format(written), imports)
                    self.refused(self.check, "R1", f"Cut.java -> {name}")

    def test_a_name_written_with_a_unicode_escape_is_refused(self):
        self.write("minos-bootstrap", "com.minos.bootstrap", "Cut",
                   f"final class Cut {{ org.postgresql{escaped('.')}Driver d; }}")
        self.refused(self.check, "R1", "Cut.java -> org.postgresql.Driver")

    def test_a_qualified_name_of_the_driver_in_the_scip_provider_is_refused(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Leak",
                   "final class Leak { Object d = new org.postgresql.Driver(); }")
        self.refused(self.check, "R2", "minos-provider-scip", "org.postgresql.Driver")

    def test_a_mention_in_a_comment_or_a_literal_is_accepted(self):
        self.write("minos-provider-scip", "com.minos.adapter.scip", "Doc",
                   f'final class Doc {{ // {self.BACKEND}\n String s = "org.postgresql.Driver"; }}')
        self.check()

    def test_the_assembly_may_name_the_backend(self):
        self.write("minos-app", "com.minos.app", "Assembly", "final class Assembly { PostgresStorageBackend b; }",
                   [self.BACKEND])
        self.check()

    def test_the_real_tree_isolates_the_postgresql_backend(self):
        self.assertEqual(2, _MODULE.check_postgresql_backend_isolation())


if __name__ == "__main__":
    unittest.main()

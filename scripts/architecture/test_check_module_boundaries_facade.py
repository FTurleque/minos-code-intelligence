#!/usr/bin/env python3
"""Self-test of the A11 reading of the MinosApplication facade by check-module-boundaries.py: the accessors a surface
calls, the accessor ratchet, and the public signatures, fields, nested types and wildcard imports of the facade.

Each case builds a throw-away source tree and runs one rule of the real script against it; the real application is
checked too.
"""

from __future__ import annotations

import sys
import unittest

from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from module_boundaries_testing import FACADE, RATCHETS, RatchetTreeTestCase, _MODULE, cut_around_dots, escaped


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
                         FACADE.minos_application_accessors(self.root, "com.minos"))

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
        accessors = FACADE.minos_application_accessors(self.root, "com.minos")
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
        self.assertEqual({}, FACADE.minos_application_accessors())
        self.assertEqual([], FACADE.public_composition_root_methods())


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

    def test_the_composition_root_written_with_a_unicode_escape_is_refused(self):
        self.surface("minos-cli", "Bootstrap", "final class Bootstrap { Object s(MinosApplication app) { "
                     f"return app.{escaped('c')}ompositionRoot(); }} }}")
        self.refused(lambda: self.check_accessors(self.LISTED), "A11", "Bootstrap.java", "composition root")

    def test_the_composition_root_in_a_comment_a_literal_or_a_test_is_ignored(self):
        self.surface("minos-mcp", "Quiet", 'final class Quiet { // app.compositionRoot()\n '
                     'String s = "MinosApplicationComposers"; }')
        self.write("minos-mcp", "com.minos.mcp", "QuietTest", "final class QuietTest { Object o = app.compositionRoot(); }",
                   kind="test")
        self.assertEqual(5, self.check_accessors(self.LISTED))

    def test_the_real_list_matches_the_real_application(self):
        self.assertEqual(len(FACADE.MINOS_APPLICATION_ENGINE_ACCESSORS),
                         _MODULE.check_minos_application_accessors())
        self.assertEqual(0, len(FACADE.MINOS_APPLICATION_ENGINE_ACCESSORS))

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

    def test_a_facade_type_written_with_a_unicode_escape_is_read(self):
        self.application_with(f"    public {escaped('P')}rojectRegistry leak() {{ return null; }}")
        self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.leak", "registry.ProjectRegistry")

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
                self.assertEqual({}, FACADE.minos_application_accessors(self.root, "com.minos"))

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

    def test_a_wildcard_import_of_the_facade_cut_around_its_dots_is_refused(self):
        for form, written in cut_around_dots("com.minos.registry.*").items():
            with self.subTest(form=form):
                self.application_with("    public void e(ProjectRegistry r) { }", [written])
                self.refused(lambda: self.check_accessors({}), "A11", "imports com.minos.registry.*")

    def application_source(self, header, members):
        """MinosApplication with a header written as is: package line and imports."""
        self.application_with(members, ())
        source = FACADE.minos_application_source(self.root, "com.minos")
        source.write_text(f"{header}\npublic final class MinosApplication {{\n{members}\n}}\n", encoding="utf-8")

    def test_an_import_is_read_wherever_it_stands_on_its_line(self):
        # AUD-ARC-17: Java does not tie an import to the start of a line.
        self.write("minos-engine", "com.minos.orchestration", "IndexingRuntimePorts",
                   "public final class IndexingRuntimePorts { public interface IndexerExecutor { } }")
        package = "package com.minos.application;"
        registry = "import com.minos.registry.ProjectRegistry;"
        leak = "    public ProjectRegistry leak() { return null; }"
        returned = ("MinosApplication.leak", "registry.ProjectRegistry")
        variants = (
            ("after an import", f"{package}\nimport java.util.List; {registry}", leak, returned),
            ("glued to an import", f"{package}\nimport java.util.List;{registry}", leak, returned),
            ("after the package", f"{package} {registry}", leak, returned),
            ("after a lone semicolon", f"{package}\n; {registry}", leak, returned),
            ("after a comment closing on the line of an import",
             f"{package}\nimport java.util.List; /* note\n */ {registry}", leak, returned),
            ("wildcard", f"{package}\nimport java.util.List; import com.minos.registry.*;", leak,
             ("imports com.minos.registry.*",)),
            ("static", f"{package}\nimport java.util.List; "
             "import static com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;",
             "    public IndexerExecutor leak() { return null; }",
             ("MinosApplication.leak", "orchestration.IndexingRuntimePorts")),
        )
        for position, header, members, fragments in variants:
            with self.subTest(position=position):
                self.application_source(header, members)
                self.refused(lambda: self.check_accessors({}), "A11", *fragments)

    def test_an_import_written_in_a_comment_or_a_literal_is_not_an_import(self):
        leak = "; import com.minos.registry.*;"
        self.application_source(f"package com.minos.application; // {leak}\n/* {leak} */",
                                f'    String line = "{leak}";\n    String block = """\n        {leak}\n        """;')
        self.assertEqual(0, self.check_accessors({}))
        self.assertEqual({"minos-api": 0, "minos-cli": 0, "minos-explorer": 0, "minos-mcp": 0, "minos-nexus": 0},
                         _MODULE.check_engine_types_named_by_surfaces(self.root, listed={}))

    def test_an_escaped_quote_does_not_close_a_text_block(self):
        # The text of a block goes on after an escaped quote: what follows is still a literal.
        leak = "; import com.minos.registry.*;"
        for position, text in (("before three quotes", f'\\""" {leak}\n        '),
                               ("before the closing quotes", f'{leak}\n        \\"')):
            with self.subTest(position=position):
                self.application_source("package com.minos.application;",
                                        f'    String block = """\n        {text}""";\n    String after = "{leak}";')
                self.assertEqual(0, self.check_accessors({}))

    def refused_declarations(self, variants, *fragments, imports=("com.minos.registry.ProjectRegistry",)):
        for members in variants:
            with self.subTest(members=members):
                self.application_with("    " + members, imports)
                self.refused(lambda: self.check_accessors({}), "A11", "registry.ProjectRegistry", *fragments)

    def test_an_annotation_inside_a_declaration_does_not_hide_it(self):
        # AUD-ARC-17: an annotation may sit anywhere between the modifiers and the name.
        self.refused_declarations(
            ("public @Deprecated ProjectRegistry x() { return null; }",
             "public @Tag(v = @In({1, 2})) ProjectRegistry x() { return null; }",
             "public <T> @Deprecated ProjectRegistry x() { return null; }"),
            "MinosApplication.x is a new public accessor")
        self.refused_declarations(
            ("public static @Deprecated final ProjectRegistry x() { return null; }",
             "public @Deprecated static ProjectRegistry x() { return null; }"),
            "MinosApplication.x is a public static method")
        self.refused_declarations(
            ("public com.minos.registry.@Nullable ProjectRegistry x() { return null; }",),
            "MinosApplication.x", imports=())
        self.refused_declarations(("public void x(@Tag(v = @In(1)) ProjectRegistry r) { }",),
                                  "MinosApplication.x", "parameter r")

    def test_a_member_is_read_whatever_the_case_of_its_name(self):
        # AUD-ARC-17: a method name is any Java identifier, not only a lower-case one.
        self.refused_declarations(("public ProjectRegistry Leak() { return null; }",
                                   "public void Leak(ProjectRegistry r) { }"), "MinosApplication.Leak")
        self.refused_declarations(("public ProjectRegistry $leak() { return null; }",), "MinosApplication.$leak")
        self.refused_declarations(("public void leak(ProjectRegistry $) { }",), "MinosApplication.leak",
                                  "parameter $")

    def test_a_header_written_over_several_lines_is_read(self):
        # AUD-ARC-17: line breaks inside a type, qualified names included, hide nothing.
        self.refused_declarations(
            ("public Map<String,\n            ProjectRegistry> x() { return null; }",),
            "MinosApplication.x", imports=("com.minos.registry.ProjectRegistry", "java.util.Map"))
        self.refused_declarations(
            ("public com.minos.registry\n            .ProjectRegistry x() { return null; }",
             "public com.minos.registry.\n            ProjectRegistry x() { return null; }",
             "public void x(com.minos.registry\n            .ProjectRegistry r) { }"),
            "MinosApplication.x", imports=())

    def test_declarations_without_an_engine_type_are_accepted(self):
        self.application_with(
            "    public static final String NAME = of(\"x\");\n"
            "    @Override public @Deprecated void close() { }\n"
            "    public int[] Values(@Tag(v = @In(1)) String... names) { return null; }\n"
            "    record Name(String value) { }\n"
            "    private record Box<T>(T value) { }\n"
            "    @interface Marker { }\n"
            "    enum Kind { FIRST(1); Kind(int rank) { } }")
        self.assertEqual(0, self.check_accessors({}))
        self.assertEqual([], FACADE.minos_application_engine_parameters(self.root, "com.minos"))
        self.assertEqual({}, FACADE.minos_application_accessors(self.root, "com.minos"))

    def test_an_unreadable_member_header_fails_closed(self):
        self.application_with("    public ProjectRegistry () { return null; }")
        self.refused(lambda: self.check_accessors({}), "A11 unreadable MinosApplication member")

    def test_a_wildcard_import_through_an_engine_type_is_refused(self):
        # AUD-ARC-17: the member types of an engine type imported by wildcard resolve to nothing.
        ports = "com.minos.orchestration.IndexingRuntimePorts"
        for imported in (ports + ".*", "static " + ports + ".*", ports + ".IndexerExecutor.*"):
            with self.subTest(imported=imported):
                self.application_with("    public IndexerExecutor x() { return null; }", [imported])
                self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.java",
                             "imports " + imported.removeprefix("static "))

    def test_a_static_import_of_a_nested_engine_type_is_read(self):
        # AUD-ARC-17: `import static` brings a member type in scope like a plain import.
        nested = "static com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor"
        for members in ("    public IndexerExecutor x() { return null; }", "    public void x(IndexerExecutor e) { }"):
            with self.subTest(members=members):
                self.application_with(members, [nested])
                self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.x",
                             "orchestration.IndexingRuntimePorts")

    def test_a_static_import_outside_the_engine_or_shadowed_is_accepted(self):
        # A single-type import wins over a static import of the same simple name.
        self.application_with(
            "    public Path x(Map.Entry<String, String> entry) { requireText(entry); return null; }",
            ["static com.minos.domain.Preconditions.requireText", "java.util.Map", "java.util.Map.*",
             "java.nio.file.Path", "static com.minos.orchestration.IndexingRuntimePorts.Path"])
        self.assertEqual(0, self.check_accessors({}))

    def test_a_public_field_of_an_engine_type_is_refused(self):
        # AUD-ARC-17: a field hands out a type like a method does.
        self.refused_declarations(("public ProjectRegistry registry;",
                                   "public static final ProjectRegistry registry = null;",
                                   "public java.util.List<ProjectRegistry> registry;",
                                   "public java.util.Map<String, ProjectRegistry> registry, other;",
                                   "public ProjectRegistry[] registry, other;",
                                   "public @Deprecated volatile ProjectRegistry registry[];",
                                   "public @Tag(v = 1) ProjectRegistry registry;"),
                                  "MinosApplication.registry is a public field")

    def test_a_public_field_of_the_composition_root_type_is_refused(self):
        self.application_with("    public MinosApplicationComposer root;")
        self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.root is a public field",
                     "MinosApplicationComposer")

    def test_a_field_without_an_engine_type_or_not_public_is_accepted(self):
        # The name of a field is not a type, even when it reads like one.
        self.application_with("    public static final String NAME = \"x\";\n"
                              "    public String ProjectRegistry;\n"
                              "    public java.util.Map<String, Integer> first = null, second;\n"
                              "    public int ranks[];\n"
                              "    private ProjectRegistry hidden;\n"
                              "    ProjectRegistry internal;")
        self.assertEqual(0, self.check_accessors({}))

    def test_an_unreadable_public_field_fails_closed(self):
        self.application_with("    public registry;")
        self.refused(lambda: self.check_accessors({}), "A11 unreadable MinosApplication member")

    def test_a_public_nested_type_other_than_the_builder_is_refused(self):
        # AUD-ARC-17: the members of a nested type may be public without saying so, so the type itself is refused.
        for members in ("public record Port(ProjectRegistry registry) { }",
                        "public record Port<T>(T value) { }",
                        "public static final class Port { public ProjectRegistry x() { return null; } }",
                        "public interface Port { ProjectRegistry registry(); }",
                        "public sealed interface Port permits Impl { }",
                        "public enum Port { ONLY }",
                        "public @interface Port { }",
                        "public @Tag(v = 1) record Port(String value) { }"):
            with self.subTest(members=members):
                self.application_with("    " + members)
                self.refused(lambda: self.check_accessors({}), "A11", "MinosApplication.Port is a public nested type",
                             "Builder")

    def test_the_builder_and_non_public_nested_types_are_accepted(self):
        self.application_with("    public static final class Builder {\n"
                              "        public ProjectRegistry registry;\n"
                              "        public Builder projectRegistry(ProjectRegistry value) { return this; }\n"
                              "    }\n"
                              "    record Stores(ProjectRegistry registry) { }\n"
                              "    private record Queries(ProjectRegistry registry) { }")
        self.assertEqual(0, self.check_accessors({}))

    def test_a_generic_signature_without_an_engine_type_is_accepted(self):
        self.application_with("    public <T extends Comparable<T>> java.util.List<T> sorted(java.util.List<T> values) "
                              "{ return values; }\n"
                              "    public static <T> java.util.List<T> none() { return null; }\n"
                              "    public <T> MinosApplication(T value) { }")
        self.assertEqual(0, self.check_accessors({}))
        self.assertEqual([], FACADE.minos_application_engine_parameters(self.root, "com.minos"))
        self.assertEqual({}, FACADE.minos_application_accessors(self.root, "com.minos"))

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
        self.assertEqual([], FACADE.minos_application_engine_parameters(self.root, "com.minos"))

    def test_the_real_application_takes_no_engine_type(self):
        self.assertEqual([], FACADE.minos_application_engine_parameters())

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


if __name__ == "__main__":
    unittest.main()

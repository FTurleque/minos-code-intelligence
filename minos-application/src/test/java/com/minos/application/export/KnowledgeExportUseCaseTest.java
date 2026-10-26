package com.minos.application.export;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.io.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The export selects the local, located facts of the active snapshot and resolves their project-relative paths. */
class KnowledgeExportUseCaseTest {

    @TempDir
    Path temp;

    @Test
    void exportsLocatedSymbolsAndRelationsWithProjectRelativePaths() throws Exception {
        ExportFixture fixture = new ExportFixture(temp);
        Path fileA = fixture.file("src/A.java");
        fixture.file("src/B.java");
        Symbol first = fixture.symbol("a", "src/A.java");
        Symbol second = fixture.symbol("b", stableId(fixture, "src/B.java"));
        Symbol third = fixture.symbol("c", fileA.toString());
        Relationship call = fixture.call("r1", "a", "b", null);
        Relationship located = fixture.call("r2", "b", "c", stableId(fixture, "src/B.java"));

        KnowledgeExport export = fixture.useCase(List.of(third, second, first), List.of(located, call))
                .export(fixture.root);

        assertEquals(fixture.projectId(), export.projectId());
        assertEquals("demo", export.displayName());
        assertEquals(fixture.root, export.rootPath());
        assertEquals(ExportFixture.SNAPSHOT_ID, export.snapshotId());
        assertEquals(List.of("a", "b", "c"), export.symbols().stream().map(symbol -> symbol.symbol().id()).toList());
        assertEquals(List.of("src/A.java", "src/B.java", "src/A.java"),
                export.symbols().stream().map(ExportedSymbol::filePath).toList());
        assertSame(first, export.symbols().getFirst().symbol());
        assertEquals(List.of("r1", "r2"),
                export.relationships().stream().map(relation -> relation.relationship().id()).toList());
        assertEquals(List.of("src/A.java", "src/B.java"),
                export.relationships().stream().map(ExportedRelationship::filePath).toList());
        assertEquals(List.of(), export.limitations());
    }

    @Test
    void omittedFactsAreDeclaredInTheirSelectionOrder() throws Exception {
        ExportFixture fixture = new ExportFixture(temp);
        fixture.file("src/A.java");
        Files.writeString(temp.resolve("outside.txt"), "x");
        List<Symbol> symbols = List.of(
                fixture.symbol("a", "src/A.java"),
                fixture.symbol("ext", "src/A.java", true),
                fixture.symbol("loose", null),
                fixture.symbol("ghost", stableId(fixture, "src/Missing.java")),
                fixture.symbol("outside", "../outside.txt"));
        List<Relationship> relationships = List.of(
                fixture.fileImport("imp", "a", "file-a"),
                fixture.call("to-ext", "a", "ext", null),
                fixture.call("to-ghost", "a", "ghost", null),
                fixture.call("nowhere", "a", "a", stableId(fixture, "src/Nowhere.java")));

        KnowledgeExport export = fixture.useCase(symbols, relationships).export(fixture.root);

        assertEquals(List.of("a"), export.symbols().stream().map(symbol -> symbol.symbol().id()).toList());
        assertEquals(List.of(), export.relationships());
        assertEquals(List.of("EXTERNAL_SYMBOLS_OMITTED", "SYMBOL_WITHOUT_LOCAL_LOCATION_OMITTED",
                "NON_SYMBOL_RELATIONS_OMITTED", "NON_LOCAL_RELATIONS_OMITTED", "UNRESOLVED_FILE_IDS",
                "UNRESOLVED_SYMBOL_FILE_ID_OMITTED", "UNRESOLVED_RELATION_FILE_ID_OMITTED"), export.limitations());
    }

    @Test
    void aRelationToASymbolWithoutResolvedPathIsNonLocal() throws Exception {
        ExportFixture fixture = new ExportFixture(temp);
        fixture.file("src/A.java");
        KnowledgeExport export = fixture.useCase(
                List.of(fixture.symbol("a", "src/A.java"), fixture.symbol("ghost", stableId(fixture, "src/Gone.java"))),
                List.of(fixture.call("r", "a", "ghost", null))).export(fixture.root);

        assertEquals(List.of(), export.relationships());
        assertEquals(List.of("UNRESOLVED_FILE_IDS", "UNRESOLVED_SYMBOL_FILE_ID_OMITTED",
                "NON_LOCAL_RELATIONS_OMITTED"), export.limitations());
    }

    @Test
    void fileIdsThatDoNotDesignateARegularFileUnderTheRootAreNotResolved() throws Exception {
        ExportFixture fixture = new ExportFixture(temp);
        fixture.file("src/A.java");
        KnowledgeExport export = fixture.useCase(List.of(
                fixture.symbol("root", "."),
                fixture.symbol("directory", "src"),
                fixture.symbol("invalid", "bad\u0000name"),
                fixture.symbol("missing", "src/Missing.java")), List.of()).export(fixture.root);

        assertEquals(List.of(), export.symbols());
        assertEquals(List.of("UNRESOLVED_SYMBOL_FILE_ID_OMITTED"), export.limitations());
    }

    @Test
    void theRootIsCanonicalizedBeforeItIsMatched() throws Exception {
        ExportFixture fixture = new ExportFixture(temp);
        fixture.file("src/A.java");
        Path aliased = fixture.root.resolve("src").resolve("..");

        KnowledgeExport export = fixture.useCase(List.of(fixture.symbol("a", "src/A.java")), List.of()).export(aliased);

        assertEquals(fixture.root, export.rootPath());
        assertEquals(List.of("src/A.java"), export.symbols().stream().map(ExportedSymbol::filePath).toList());
    }

    @Test
    void resultsAreImmutable() throws Exception {
        ExportFixture fixture = new ExportFixture(temp);
        fixture.file("src/A.java");
        KnowledgeExport export = fixture.useCase(List.of(fixture.symbol("a", "src/A.java")), List.of())
                .export(fixture.root);

        List<ExportedSymbol> symbols = export.symbols();
        List<ExportedRelationship> relationships = export.relationships();
        List<String> limitations = export.limitations();
        assertThrows(UnsupportedOperationException.class, symbols::clear);
        assertThrows(UnsupportedOperationException.class, relationships::clear);
        assertThrows(UnsupportedOperationException.class, limitations::clear);
    }

    private static String stableId(ExportFixture fixture, String relativePath) {
        return "file:" + Sha256.hex(fixture.projectId() + "\u001F" + relativePath);
    }
}

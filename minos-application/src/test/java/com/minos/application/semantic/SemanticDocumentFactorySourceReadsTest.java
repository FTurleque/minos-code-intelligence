package com.minos.application.semantic;

import com.minos.context.LocalSourceReader;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.io.Sha256;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.SemanticDocument;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the semantic documents read their sources: the documents are, byte for byte, those of the reading
 * per symbol frozen before the reading per file (line endings LF and CRLF, excerpts shrunk to their token
 * budget, a line past the end of the file, a file missing from the disk), and nothing read during a build
 * outlives it.
 */
class SemanticDocumentFactorySourceReadsTest {

    /**
     * Digest of every document built from {@link #fixture()}, recorded on {@code develop} at {@code 9e4a6e61},
     * when every symbol still reread its file. It covers kind, keys, source, position, content and checksum.
     */
    private static final String DOCUMENTS_DIGEST_PER_SYMBOL_READING = "a97f1a0ec357e02445ec9006ac7644ad289581ff0b37fd738f92b3b1b117d81b";

    private Path root;
    private RegisteredProject project;

    @BeforeEach
    void fixture(@TempDir Path temp) throws Exception {
        root = temp;
        Instant now = Instant.parse("2026-10-14T08:00:00Z");
        project = new RegisteredProject(UUID.randomUUID(), root, "source-reads", Optional.empty(), now, now);
        write("src/Lf.java", lfSource(), "\n");
        write("src/Crlf.java", crlfSource(), "\r\n");
        write("src/Wide.java", wideSource(), "\n");
    }

    @Test
    void theDocumentsAreThoseOfTheReadingPerSymbol() throws Exception {
        List<SemanticDocument> documents = new SemanticDocumentFactory().build(project, fixture());

        assertEquals(DOCUMENTS_DIGEST_PER_SYMBOL_READING, digest(documents));
    }

    @Test
    void aFileWithTwentySymbolsIsReadOnce() throws Exception {
        Map<String, Integer> reads = new TreeMap<>();

        new SemanticDocumentFactory(counting(reads)).build(project, fixture());

        assertEquals(1, reads.get("src/Lf.java"), "reads of the file that carries twenty symbols");
    }

    @Test
    void theFilesReadAreTheDistinctFilesThatCarryASymbol() throws Exception {
        Map<String, Integer> reads = new TreeMap<>();

        List<SemanticDocument> documents = new SemanticDocumentFactory(counting(reads)).build(project, fixture());

        assertEquals(Map.of("src/Crlf.java", 1, "src/Gone.java", 1, "src/Lf.java", 1, "src/Wide.java", 1), reads);
        assertEquals(DOCUMENTS_DIGEST_PER_SYMBOL_READING, digest(documents));
    }

    private static SemanticDocumentFactory.SourceFilesOpener counting(Map<String, Integer> reads) {
        return root -> {
            LocalSourceReader reader = new LocalSourceReader(root);
            return fileId -> {
                reads.merge(fileId, 1, Integer::sum);
                return reader.read(fileId);
            };
        };
    }

    @Test
    void theSymbolsOfAFileMissingFromTheDiskFallBackToTheirOwnText() throws Exception {
        List<SemanticDocument> documents = new SemanticDocumentFactory().build(project, fixture());

        SemanticDocument symbol = byKey(documents, "symbol:gone-0");
        SemanticDocument chunk = byKey(documents, "chunk:gone-0:1:2");
        assertEquals(symbol.content(), chunk.content());
    }

    @Test
    void aFileChangedAfterABuildIsReadAgainByTheNextOne() throws Exception {
        SemanticDocumentFactory factory = new SemanticDocumentFactory();
        factory.build(project, fixture());
        Files.writeString(root.resolve("src/Lf.java"), "class Changed {\n  void fresh() {}\n}\n", StandardCharsets.UTF_8);

        List<SemanticDocument> rebuilt = factory.build(project, fixture());

        assertTrue(byKey(rebuilt, "chunk:lf-0:2:3").content().contains("fresh"),
                byKey(rebuilt, "chunk:lf-0:2:3").content());
    }

    static CodeKnowledgeSnapshot snapshotOf(RegisteredProject project, List<Symbol> symbols) {
        return new CodeKnowledgeSnapshot(project.id(), "snapshot-1", symbols, List.of(), List.of());
    }

    CodeKnowledgeSnapshot fixture() {
        String projectId = project.id().toString();
        List<Symbol> symbols = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            symbols.add(symbol(projectId, "lf-" + i, "src/Lf.java", 2 + i * 2, 3 + i * 2));
        }
        for (int i = 0; i < 4; i++) {
            symbols.add(symbol(projectId, "crlf-" + i, "src/Crlf.java", 2 + i * 3, 4 + i * 3));
        }
        symbols.add(symbol(projectId, "crlf-past-end", "src/Crlf.java", 500, 501));
        symbols.add(symbol(projectId, "wide-0", "src/Wide.java", 20, 24));
        symbols.add(symbol(projectId, "wide-1", "src/Wide.java", 2, 3));
        symbols.add(symbol(projectId, "gone-0", "src/Gone.java", 1, 2));
        return snapshotOf(project, symbols);
    }

    private static Symbol symbol(String projectId, String name, String file, int start, int end) {
        return new Symbol("sym-" + name, name, SymbolIdentityQuality.CANONICAL, projectId, "module",
                file, null, SymbolKind.METHOD, name, "pkg." + name, "void " + name + "()", "java",
                new SymbolLocation(file, start, 0, end, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    private static String lfSource() {
        StringBuilder source = new StringBuilder("class Lf {\n");
        for (int i = 0; i < 20; i++) {
            source.append("  void lf").append(i).append("() { // é ").append(i).append('\n')
                    .append("  }\n");
        }
        return source.append("}\n").toString();
    }

    private static String crlfSource() {
        StringBuilder source = new StringBuilder("class Crlf {\n");
        for (int i = 0; i < 4; i++) {
            source.append("  int crlf").append(i).append("() {\n").append("    return ").append(i).append(";\n")
                    .append("  }\n");
        }
        return source.append("}\n").toString();
    }

    /** Lines long enough for an excerpt to be shrunk, then truncated, to its token budget. */
    private static String wideSource() {
        StringBuilder source = new StringBuilder("class Wide {\n");
        for (int i = 0; i < 40; i++) {
            source.append("  // ").append(String.valueOf((char) ('a' + i % 26)).repeat(400)).append('\n');
        }
        return source.append("}\n").toString();
    }

    private void write(String relative, String content, String lineEnding) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.replace("\n", lineEnding), StandardCharsets.UTF_8);
    }

    private static SemanticDocument byKey(List<SemanticDocument> documents, String key) {
        return documents.stream().filter(document -> document.stableKey().equals(key)).findFirst().orElseThrow();
    }

    static String digest(List<SemanticDocument> documents) {
        StringBuilder all = new StringBuilder();
        for (SemanticDocument document : documents) {
            all.append(String.join("|", document.kind().name(), document.stableKey(), document.id(),
                    document.snapshotId(), document.sourceId(), String.valueOf(document.fileId()),
                    Integer.toString(document.startLine()), Integer.toString(document.endLine()),
                    Sha256.hex(document.content()), document.checksum())).append('\n');
        }
        return Sha256.hex(all.toString());
    }
}

package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.application.semantic.SemanticCorpusTestDoubles.FixedSnapshotStore;
import com.minos.application.semantic.SemanticCorpusTestDoubles.InMemoryProjectRegistry;
import com.minos.application.semantic.SemanticCorpusTestDoubles.InMemoryVectorStore;
import com.minos.application.semantic.SemanticCorpusTestDoubles.RefusingProvider;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-only consultation of a semantic index: paged list bound to one index, detail compared with the
 * source as it is now, and neighbours ranked from the stored vector without any call to the provider.
 */
class SemanticCorpusQueryServiceTest {

    private static final String ACTIVE = "snapshot-1";
    private static final int DIMENSIONS = SemanticCorpusTestDoubles.DIMENSIONS;

    private Path root;
    private UUID projectUuid;
    private String projectId;
    private RegisteredProject project;
    private InMemoryProjectRegistry registry;
    private FixedSnapshotStore snapshots;
    private InMemoryVectorStore vectors;
    private RefusingProvider provider;
    private SemanticCorpusQueryService corpus;
    private SemanticSearchService semanticSearch;
    private HybridSearchService hybridSearch;

    @BeforeEach
    void wire(@TempDir Path temp) throws Exception {
        root = Files.createDirectory(temp.resolve("project"));
        Files.createDirectories(root.resolve("src"));
        writeSource("src/Alpha.java", "package demo;", "", "class Alpha {", "    void alpha() { run(); }", "}", "");
        writeSource("src/Beta.java", "package demo;", "", "class Beta {", "    void beta() { stop(); }", "}", "");
        registry = new InMemoryProjectRegistry();
        project = registry.registerProject(root, "corpus-fixture");
        projectUuid = project.id();
        projectId = projectUuid.toString();
        snapshots = new FixedSnapshotStore(new CodeKnowledgeSnapshot(projectUuid, ACTIVE,
                List.of(symbol("alpha", "src/Alpha.java", "core"), symbol("beta", "src/Beta.java", "edge")),
                List.of(), List.of()));
        vectors = new InMemoryVectorStore();
        provider = new RefusingProvider();
        vectors.replace(indexOf(new SemanticDocumentFactory().build(project, snapshots.active), ACTIVE, 1_000L));
        ProjectResolver resolver = new ProjectResolver(registry);
        SemanticIndexService index = new SemanticIndexService(resolver, snapshots, vectors, Optional.of(provider));
        semanticSearch = new SemanticSearchService(index);
        hybridSearch = new HybridSearchService(resolver, snapshots, index, semanticSearch);
        corpus = new SemanticCorpusQueryService(registry, resolver, snapshots, index, semanticSearch, hybridSearch);
    }

    @Test
    void aSemanticSearchKeepsTheRanksAndScoresOfTheSearchServiceAndNamesTheScore() throws Exception {
        SemanticSearchService.SearchResponse direct = semanticSearch.search(projectId,
                new SemanticSearchService.SearchRequest("alpha run", 10, -1.0));

        SemanticCorpusQueryService.SearchResult explored = corpus.search(projectId,
                new SemanticCorpusQueryService.SearchRequest("alpha run",
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 10, -1.0, null, null, null, null));

        assertFalse(direct.hits().isEmpty());
        assertEquals(direct.hits().stream().map(hit -> hit.document().stableKey()).toList(),
                explored.hits().stream().map(hit -> hit.document().stableKey()).toList());
        assertEquals(direct.hits().stream().map(SemanticSearchService.SearchHit::score).toList(),
                explored.hits().stream().map(SemanticCorpusQueryService.SearchHit::score).toList());
        for (int index = 0; index < explored.hits().size(); index++) {
            assertEquals(index + 1, explored.hits().get(index).rank());
            assertEquals("COSINE_SIMILARITY", explored.hits().get(index).scoreKind());
        }
        assertEquals(direct.limitations(), explored.limitations());
        assertEquals(direct.hits().size(), explored.rankedCount());
        assertEquals("SEMANTIC", explored.mode());
        assertEquals(ACTIVE, explored.identity().orElseThrow().snapshotId());
        assertTrue(explored.limitations().contains("REFUSING_PROVIDER_LIMITATION"));
    }

    @Test
    void aHybridSearchKeepsTheRanksScoresAndSignalsOfTheHybridService() throws Exception {
        HybridSearchService.HybridResponse direct = hybridSearch.search(projectId,
                new HybridSearchService.HybridRequest("alpha", 10, 0.0));

        SemanticCorpusQueryService.SearchResult explored = corpus.search(projectId,
                new SemanticCorpusQueryService.SearchRequest("alpha",
                        SemanticCorpusQueryService.SearchMode.HYBRID, 10, 0.0, null, null, null, null));

        assertFalse(direct.hits().isEmpty());
        assertEquals(direct.hits().stream().map(hit -> hit.document().stableKey()).toList(),
                explored.hits().stream().map(hit -> hit.document().stableKey()).toList());
        assertEquals(direct.hits().stream().map(HybridSearchService.HybridHit::score).toList(),
                explored.hits().stream().map(SemanticCorpusQueryService.SearchHit::score).toList());
        assertTrue(explored.hits().stream().allMatch(hit -> "HYBRID_SCORE".equals(hit.scoreKind())));
        assertEquals(direct.hits().getFirst().signals().stream().map(HybridSearchService.RankingSignal::type).toList(),
                explored.hits().getFirst().signals().stream().map(SemanticCorpusQueryService.ScoreSignal::type).toList());
        assertEquals(direct.limitations(), explored.limitations());
    }

    @Test
    void aSearchFilterRemovesHitsWithoutChangingAScoreOrARank() throws Exception {
        SemanticCorpusQueryService.SearchResult all = corpus.search(projectId,
                new SemanticCorpusQueryService.SearchRequest("alpha run",
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 10, -1.0, null, null, null, null));

        SemanticCorpusQueryService.SearchResult chunks = corpus.search(projectId,
                new SemanticCorpusQueryService.SearchRequest("alpha run",
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 10, -1.0,
                        SemanticDocumentKind.FILE, null, null, null));

        assertEquals(all.hits().stream().filter(hit -> "FILE".equals(hit.document().kind())).toList(), chunks.hits());
        assertEquals(2, chunks.hits().size());
        assertTrue(chunks.hits().size() < all.hits().size());
        assertEquals(all.rankedCount(), chunks.rankedCount());
        assertTrue(chunks.hits().stream().anyMatch(hit -> hit.rank() > chunks.hits().indexOf(hit) + 1),
                "a kept hit keeps the rank it had before the filter");
    }

    @Test
    void aSearchOnAnIndexThatIsNotReadyIsEmptyAndSaysWhy() throws Exception {
        snapshots.active = new CodeKnowledgeSnapshot(projectUuid, "snapshot-2", snapshots.active.symbols(),
                List.of(), List.of());

        SemanticCorpusQueryService.SearchResult stale = corpus.search(projectId,
                new SemanticCorpusQueryService.SearchRequest("alpha",
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 10, -1.0, null, null, null, null));

        assertEquals(List.of(), stale.hits());
        assertEquals(0, stale.rankedCount());
        assertTrue(stale.limitations().contains("SEMANTIC_SEARCH_REQUIRES_READY_INDEX"), stale.limitations().toString());
        assertTrue(stale.limitations().contains("SEMANTIC_INDEX_SNAPSHOT_STALE"));
        assertEquals(Optional.empty(), stale.identity());
    }

    @Test
    void theBoundsOfASearchAreThoseOfTheServiceThatRanks() {
        assertEquals("limit must be between 1 and 1000", assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.SearchRequest("q",
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 1_001, 0.0, null, null, null, null)).getMessage());
        assertEquals("limit must be between 1 and 500", assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.SearchRequest("q",
                        SemanticCorpusQueryService.SearchMode.HYBRID, 501, 0.0, null, null, null, null)).getMessage());
        assertEquals("minimumScore must be between 0 and 1", assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.SearchRequest("q",
                        SemanticCorpusQueryService.SearchMode.HYBRID, 10, -0.5, null, null, null, null)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.SearchRequest(" ",
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 10, 0.0, null, null, null, null));
        assertEquals("query exceeds 65536 UTF-8 bytes", assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.SearchRequest("q".repeat(65_537),
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 10, 0.0, null, null, null, null)).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.SearchRequest("q",
                        SemanticCorpusQueryService.SearchMode.SEMANTIC, 10, Double.NaN, null, null, null, null));
    }

    @Test
    void aFullWalkReturnsEveryDocumentOnceInStableKeyOrderOnOneIndex() throws Exception {
        vectors.replace(syntheticIndex(ACTIVE, 2_000L, 120));

        List<String> walked = new ArrayList<>();
        List<SemanticCorpusQueryService.IndexIdentity> identities = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            SemanticCorpusQueryService.DocumentsPage page = corpus.documents(projectId,
                    new SemanticCorpusQueryService.DocumentsRequest(cursor, 50, null, null, null, null));
            pages++;
            assertEquals(120, page.totalDocuments());
            identities.add(page.identity().orElseThrow());
            page.documents().forEach(document -> walked.add(document.stableKey()));
            cursor = page.nextCursor();
        } while (cursor != null);

        List<String> expected = new ArrayList<>();
        for (int number = 0; number < 120; number++) expected.add(key(number));
        assertEquals(expected, walked);
        assertEquals(3, pages);
        assertEquals(1, Set.copyOf(identities).size());
        assertEquals(ACTIVE, identities.getFirst().snapshotId());
        assertEquals(provider.id(), identities.getFirst().providerId());
        assertEquals(DIMENSIONS, identities.getFirst().dimensions());
    }

    @Test
    void aPageSizeOutOfBoundsIsAUsageErrorThatNamesTheBound() {
        IllegalArgumentException tooLarge = assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.DocumentsRequest(null, 5_000, null, null, null, null));
        IllegalArgumentException tooSmall = assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.DocumentsRequest(null, 0, null, null, null, null));

        assertEquals("limit must be between 1 and 200", tooLarge.getMessage());
        assertEquals(tooLarge.getMessage(), tooSmall.getMessage());
        assertEquals(50, SemanticCorpusQueryService.DocumentsRequest.firstPage().limit());
    }

    @Test
    void aCursorIssuedForAnIndexIsRefusedOnceThatIndexIsReplaced() throws Exception {
        vectors.replace(syntheticIndex(ACTIVE, 2_000L, 120));
        SemanticCorpusQueryService.DocumentsPage first = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(null, 50, null, null, null, null));

        vectors.replace(syntheticIndex(ACTIVE, 3_000L, 121));
        SemanticCorpusQueryService.DocumentsRequest next =
                new SemanticCorpusQueryService.DocumentsRequest(first.nextCursor(), 50, null, null, null, null);

        SemanticCorpusQueryService.IndexChangedException refused =
                assertThrows(SemanticCorpusQueryService.IndexChangedException.class,
                        () -> corpus.documents(projectId, next));
        assertTrue(refused.getMessage().contains("start the reading again"), refused.getMessage());
        assertNotEquals(first.identity().orElseThrow(),
                corpus.documents(projectId, SemanticCorpusQueryService.DocumentsRequest.firstPage())
                        .identity().orElseThrow());
    }

    @Test
    void aCursorIsRefusedWhenTheIndexDisappearedOrWhenItIsNotACursor() throws Exception {
        SemanticCorpusQueryService.DocumentsPage first = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(null, 1, null, null, null, null));
        vectors.delete(projectId);

        assertThrows(SemanticCorpusQueryService.IndexChangedException.class, () -> corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(first.nextCursor(), 1, null, null, null, null)));
        for (String invalid : List.of("***", "YQ", "%%")) {
            IllegalArgumentException usage = assertThrows(IllegalArgumentException.class,
                    () -> corpus.documents(projectId,
                            new SemanticCorpusQueryService.DocumentsRequest(invalid, 1, null, null, null, null)));
            assertEquals("cursor is not valid", usage.getMessage());
        }
    }

    @Test
    void aProjectWithoutIndexHasAnEmptyPageThatSaysSo() throws Exception {
        vectors.delete(projectId);

        SemanticCorpusQueryService.DocumentsPage page =
                corpus.documents(projectId, SemanticCorpusQueryService.DocumentsRequest.firstPage());

        assertEquals(Optional.empty(), page.identity());
        assertEquals(List.of(), page.documents());
        assertNull(page.nextCursor());
        assertTrue(page.limitations().contains("SEMANTIC_INDEX_MISSING"), page.limitations().toString());
    }

    @Test
    void aFilterRemovesDocumentsWithoutReorderingThoseItKeeps() throws Exception {
        List<String> all = keys(corpus.documents(projectId, SemanticCorpusQueryService.DocumentsRequest.firstPage()));

        List<String> chunks = keys(corpus.documents(projectId, new SemanticCorpusQueryService.DocumentsRequest(
                null, 50, SemanticDocumentKind.CHUNK, null, null, null)));
        List<String> betaFiles = keys(corpus.documents(projectId, new SemanticCorpusQueryService.DocumentsRequest(
                null, 50, null, null, null, "src/Beta")));
        List<String> core = keys(corpus.documents(projectId, new SemanticCorpusQueryService.DocumentsRequest(
                null, 50, null, "core", "java", null)));
        List<String> none = keys(corpus.documents(projectId, new SemanticCorpusQueryService.DocumentsRequest(
                null, 50, null, null, "kotlin", null)));

        assertEquals(6, all.size());
        assertEquals(all.stream().filter(key -> key.startsWith("chunk:")).toList(), chunks);
        assertEquals(2, chunks.size());
        assertEquals(all.stream().filter(key -> key.contains("beta") || key.contains("Beta")).toList(), betaFiles);
        assertEquals(all.stream().filter(key -> key.contains("alpha") || key.contains("Alpha")).toList(), core);
        assertEquals(List.of(), none);
    }

    @Test
    void aFilteredPageIsCutAtTheLimitAndItsCursorResumesAfterTheLastDocumentKept() throws Exception {
        SemanticCorpusQueryService.DocumentsPage first = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(null, 1, SemanticDocumentKind.CHUNK, null, null, null));
        SemanticCorpusQueryService.DocumentsPage second = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(
                        first.nextCursor(), 1, SemanticDocumentKind.CHUNK, null, null, null));

        assertEquals(1, first.documents().size());
        assertEquals(1, second.documents().size());
        assertNotEquals(keys(first), keys(second));
        assertTrue(keys(first).getFirst().compareTo(keys(second).getFirst()) < 0);
    }

    @Test
    void aFilterThatMatchesNothingStopsAfterABoundedScanAndGivesACursorToGoOn() throws Exception {
        vectors.replace(syntheticIndex(ACTIVE, 2_000L, SemanticCorpusQueryService.MAX_SCANNED_DOCUMENTS + 150));
        SemanticCorpusQueryService.DocumentsRequest request = new SemanticCorpusQueryService.DocumentsRequest(
                null, 10, null, null, null, "nowhere/");

        SemanticCorpusQueryService.DocumentsPage first = corpus.documents(projectId, request);
        SemanticCorpusQueryService.DocumentsPage second = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(first.nextCursor(), 10, null, null, null, "nowhere/"));

        assertEquals(List.of(), first.documents());
        assertTrue(first.limitations().contains("SEMANTIC_DOCUMENT_FILTER_SCAN_BOUNDED"), first.limitations().toString());
        assertEquals(List.of(), second.documents());
        assertNull(second.nextCursor());
        assertFalse(second.limitations().contains("SEMANTIC_DOCUMENT_FILTER_SCAN_BOUNDED"));
    }

    @Test
    void aFilterIsBoundedAndNeverBlank() {
        String oversized = "x".repeat(SemanticCorpusQueryService.MAX_FILTER_UTF8_BYTES + 1);

        assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.DocumentsRequest(null, 10, null, " ", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.DocumentsRequest(null, 10, null, null, oversized, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticCorpusQueryService.DocumentsRequest(" ", 10, null, null, null, null));
    }

    @Test
    void aDocumentIsDescribedByItsSymbolOnlyWhenTheIndexDescribesTheActiveSnapshot() throws Exception {
        SemanticCorpusQueryService.DocumentsPage aligned =
                corpus.documents(projectId, SemanticCorpusQueryService.DocumentsRequest.firstPage());
        SemanticCorpusQueryService.DocumentSummary chunk = aligned.documents().stream()
                .filter(document -> document.stableKey().startsWith("chunk:key-alpha")).findFirst().orElseThrow();
        SemanticCorpusQueryService.DocumentSummary file = aligned.documents().stream()
                .filter(document -> "FILE".equals(document.kind())).findFirst().orElseThrow();
        snapshots.active = new CodeKnowledgeSnapshot(projectUuid, "snapshot-2", snapshots.active.symbols(),
                List.of(), List.of());

        SemanticCorpusQueryService.DocumentsPage stale =
                corpus.documents(projectId, SemanticCorpusQueryService.DocumentsRequest.firstPage());

        assertTrue(chunk.symbolResolved());
        assertEquals("alpha", chunk.symbolName());
        assertEquals("pkg.alpha", chunk.qualifiedName());
        assertEquals("METHOD", chunk.symbolKind());
        assertEquals("java", chunk.language());
        assertEquals("core", chunk.moduleId());
        assertFalse(file.symbolResolved());
        assertNull(file.symbolName());
        assertEquals("java", file.language());
        assertFalse(aligned.limitations().contains("SEMANTIC_DOCUMENT_SYMBOLS_UNRESOLVED_INDEX_NOT_ALIGNED"));
        assertTrue(stale.limitations().contains("SEMANTIC_DOCUMENT_SYMBOLS_UNRESOLVED_INDEX_NOT_ALIGNED"));
        assertTrue(stale.limitations().contains("SEMANTIC_INDEX_SNAPSHOT_STALE"), stale.limitations().toString());
        assertTrue(stale.documents().stream().noneMatch(SemanticCorpusQueryService.DocumentSummary::symbolResolved));
        assertTrue(stale.documents().stream().allMatch(document -> document.language() == null));
    }

    @Test
    void neighboursAreRankedFromTheStoredVectorWithoutAnyCallToTheProvider() throws Exception {
        String origin = "chunk:key-alpha:4:4";
        SemanticVector stored = vectors.storedDocument(projectId, origin).orElseThrow().document().orElseThrow().vector();
        List<SemanticVectorStore.VectorHit> expected = vectors.search(projectId, stored, 6, -1.0).stream()
                .filter(hit -> !origin.equals(hit.document().stableKey())).toList();
        provider.refuseEveryCall = true;

        SemanticCorpusQueryService.Neighbors neighbors = corpus.neighbors(projectId, origin, 5);

        assertEquals(0, provider.calls);
        assertEquals(expected.stream().map(hit -> hit.document().stableKey()).toList(),
                neighbors.neighbors().stream().map(neighbor -> neighbor.document().stableKey()).toList());
        assertEquals(expected.stream().map(SemanticVectorStore.VectorHit::score).toList(),
                neighbors.neighbors().stream().map(SemanticCorpusQueryService.Neighbor::similarity).toList());
        assertEquals(5, neighbors.neighbors().size());
        assertTrue(neighbors.neighbors().stream().noneMatch(n -> origin.equals(n.document().stableKey())));
        assertEquals(origin, neighbors.stableKey());
        assertEquals(ACTIVE, neighbors.identity().snapshotId());
        assertTrue(neighbors.limitations().contains("VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT"));
        assertTrue(neighbors.limitations().contains("REFUSING_PROVIDER_LIMITATION"), neighbors.limitations().toString());
    }

    @Test
    void theNumberOfNeighboursIsBoundedAndTheLimitIsHonoured() throws Exception {
        String origin = "chunk:key-alpha:4:4";

        assertEquals(2, corpus.neighbors(projectId, origin, 2).neighbors().size());
        IllegalArgumentException usage = assertThrows(IllegalArgumentException.class,
                () -> corpus.neighbors(projectId, origin, 201));
        assertEquals("limit must be between 1 and 200", usage.getMessage());
        assertThrows(IllegalArgumentException.class, () -> corpus.neighbors(projectId, origin, 0));
        assertThrows(IllegalArgumentException.class, () -> corpus.neighbors(projectId, " ", 5));
        assertThrows(IllegalArgumentException.class, () -> corpus.neighbors(projectId,
                "k".repeat(SemanticCorpusQueryService.MAX_STABLE_KEY_UTF8_BYTES + 1), 5));
    }

    @Test
    void anUnknownStableKeyIsNotFoundForNeighboursAndForTheDetail() {
        assertThrows(SemanticCorpusQueryService.DocumentNotFoundException.class,
                () -> corpus.neighbors(projectId, "chunk:absent", 5));
        assertThrows(SemanticCorpusQueryService.DocumentNotFoundException.class,
                () -> corpus.detail(projectId, "chunk:absent"));
        vectors.delete(projectId);
        assertThrows(SemanticCorpusQueryService.DocumentNotFoundException.class,
                () -> corpus.neighbors(projectId, "chunk:key-alpha:4:4", 5));
        assertThrows(SemanticCorpusQueryService.DocumentNotFoundException.class,
                () -> corpus.detail(projectId, "chunk:key-alpha:4:4"));
    }

    @Test
    void neighboursOfAnIndexReplacedWhileTheyAreRankedAreRefused() throws Exception {
        vectors.replacementAfterNextSearch = syntheticIndex(ACTIVE, 9_000L, 3);

        assertThrows(SemanticCorpusQueryService.IndexChangedException.class,
                () -> corpus.neighbors(projectId, "chunk:key-alpha:4:4", 5));
    }

    @Test
    void theDetailOfAnUnchangedChunkShowsItsSourceAsIdentical() throws Exception {
        SemanticCorpusQueryService.Detail detail = corpus.detail(projectId, "chunk:key-alpha:4:4");

        assertEquals(SemanticCorpusQueryService.Divergence.IDENTIQUE, detail.divergence());
        assertTrue(detail.indexedContent().contains("void alpha() { run(); }"));
        assertEquals(detail.indexedContent(), detail.source().orElseThrow().content());
        assertEquals("src/Alpha.java", detail.source().orElseThrow().fileId());
        assertEquals("CHUNK", detail.document().kind());
        assertEquals(ACTIVE, detail.identity().snapshotId());
    }

    @Test
    void theDetailOfAChunkWhoseSourceChangedShowsBothContentsAndTheDivergence() throws Exception {
        writeSource("src/Alpha.java", "package demo;", "", "class Alpha {", "    void alpha() { halt(); }", "}", "");

        SemanticCorpusQueryService.Detail detail = corpus.detail(projectId, "chunk:key-alpha:4:4");

        assertEquals(SemanticCorpusQueryService.Divergence.DIFFERENTE, detail.divergence());
        assertTrue(detail.indexedContent().contains("run();"));
        assertTrue(detail.source().orElseThrow().content().contains("halt();"));
        assertFalse(detail.source().orElseThrow().content().contains("run();"));
    }

    @Test
    void theDetailOfADocumentWhoseFileWasDeletedSaysTheSourceIsMissingAndShowsNoAbsolutePath() throws Exception {
        Files.delete(root.resolve("src/Alpha.java"));

        SemanticCorpusQueryService.Detail chunk = corpus.detail(projectId, "chunk:key-alpha:4:4");
        SemanticCorpusQueryService.Detail file = corpus.detail(projectId, "file:src/Alpha.java");

        assertEquals(SemanticCorpusQueryService.Divergence.SOURCE_INTROUVABLE, chunk.divergence());
        assertEquals(Optional.empty(), chunk.source());
        assertTrue(chunk.indexedContent().contains("run();"));
        assertEquals(SemanticCorpusQueryService.Divergence.SOURCE_INTROUVABLE, file.divergence());
        assertFalse(chunk.toString().contains(root.toString()), "absolute root in the detail");
        assertFalse(file.toString().contains(root.toString()), "absolute root in the detail");
    }

    @Test
    void theDetailOfAFileOrSymbolDocumentIsNotCompared() throws Exception {
        SemanticCorpusQueryService.Detail file = corpus.detail(projectId, "file:src/Alpha.java");
        SemanticCorpusQueryService.Detail symbol = corpus.detail(projectId, "symbol:key-alpha");

        assertEquals(SemanticCorpusQueryService.Divergence.NON_COMPARABLE, file.divergence());
        assertEquals(Optional.empty(), file.source());
        assertTrue(file.indexedContent().startsWith("file src/Alpha.java"));
        assertEquals(SemanticCorpusQueryService.Divergence.NON_COMPARABLE, symbol.divergence());
        assertTrue(symbol.source().orElseThrow().content().contains("void alpha()"));
        assertFalse(file.toString().contains(root.toString()));
        assertFalse(symbol.toString().contains(root.toString()));
    }

    @Test
    void aStoredFileIdentifierThatLeavesTheProjectRootIsNeverRead() throws Exception {
        Path outside = root.resolveSibling("outside.txt");
        Files.writeString(outside, "secret outside the project\n", StandardCharsets.UTF_8);
        List<SemanticVectorStore.IndexedDocument> escaping = new ArrayList<>();
        for (String fileId : List.of("../outside.txt", outside.toString(), "file:///" + outside.toString().replace('\\', '/'))) {
            String key = "chunk:escape:" + escaping.size();
            escaping.add(indexed(new SemanticDocument("id-" + escaping.size(), key, projectId, ACTIVE,
                    SemanticDocumentKind.CHUNK, "sym-alpha", fileId, 1, 1, "indexed text", "chk-" + escaping.size())));
        }
        vectors.replace(new SemanticVectorStore.IndexSnapshot(projectId, ACTIVE, provider.id(), provider.modelId(),
                DIMENSIONS, 5_000L, escaping));

        for (SemanticVectorStore.IndexedDocument value : escaping) {
            SemanticCorpusQueryService.Detail detail = corpus.detail(projectId, value.document().stableKey());

            assertEquals(SemanticCorpusQueryService.Divergence.SOURCE_INTROUVABLE, detail.divergence());
            assertEquals(Optional.empty(), detail.source());
            assertFalse(detail.toString().contains("secret outside the project"));
        }
    }

    @Test
    void theProjectsAreListedByNameWithTheStateOfTheirIndexAndWithoutTheirRoot(@TempDir Path other) throws Exception {
        RegisteredProject second = registry.registerProject(other, "another-fixture");

        List<SemanticCorpusQueryService.ProjectSummary> projects = corpus.projects();

        assertEquals(List.of("another-fixture", "corpus-fixture"),
                projects.stream().map(SemanticCorpusQueryService.ProjectSummary::name).toList());
        SemanticCorpusQueryService.ProjectSummary ready = projects.get(1);
        assertEquals(projectId, ready.projectId());
        assertEquals("READY", ready.state());
        assertEquals(provider.id(), ready.providerId());
        assertEquals(provider.modelId(), ready.modelId());
        assertEquals(DIMENSIONS, ready.dimensions());
        assertEquals(ACTIVE, ready.activeSnapshotId());
        assertEquals(ACTIVE, ready.indexedSnapshotId());
        assertEquals(6, ready.documentCount());
        assertTrue(ready.limitations().contains("REFUSING_PROVIDER_LIMITATION"));
        assertEquals(second.id().toString(), projects.get(0).projectId());
        assertFalse(projects.toString().contains(root.toString()), "absolute root in the project list");
        assertFalse(projects.toString().contains(other.toString()), "absolute root in the project list");
    }

    @Test
    void theLimitationsOfTheProviderReachTheListTheDetailAndTheNeighbours() throws Exception {
        String origin = "chunk:key-alpha:4:4";

        assertTrue(corpus.documents(projectId, SemanticCorpusQueryService.DocumentsRequest.firstPage())
                .limitations().contains("REFUSING_PROVIDER_LIMITATION"));
        assertTrue(corpus.detail(projectId, origin).limitations().contains("REFUSING_PROVIDER_LIMITATION"));
        assertTrue(corpus.neighbors(projectId, origin, 3).limitations().contains("REFUSING_PROVIDER_LIMITATION"));
    }

    @Test
    void theFingerprintOfTheDocumentsDependsOnKeysAndChecksumsNotOnTheirOrder() {
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put("a", "1");
        ordered.put("b", "2");
        Map<String, String> reversed = new java.util.LinkedHashMap<>();
        reversed.put("b", "2");
        reversed.put("a", "1");

        assertEquals(SemanticCorpusQueryService.fingerprint(ordered), SemanticCorpusQueryService.fingerprint(reversed));
        assertNotEquals(SemanticCorpusQueryService.fingerprint(ordered),
                SemanticCorpusQueryService.fingerprint(Map.of("a", "1", "b", "3")));
        assertNotEquals(SemanticCorpusQueryService.fingerprint(Map.of("ab", "c")),
                SemanticCorpusQueryService.fingerprint(Map.of("a", "bc")));
        assertEquals(64, SemanticCorpusQueryService.fingerprint(Map.of()).length());
    }

    private static List<String> keys(SemanticCorpusQueryService.DocumentsPage page) {
        return page.documents().stream().map(SemanticCorpusQueryService.DocumentSummary::stableKey).toList();
    }

    private static String key(int number) {
        return String.format(Locale.ROOT, "chunk:doc-%04d", number);
    }

    private void writeSource(String relative, String... lines) throws IOException {
        Files.writeString(root.resolve(relative), String.join("\n", lines), StandardCharsets.UTF_8);
    }

    private Symbol symbol(String name, String fileId, String moduleId) {
        return new Symbol("sym-" + name, "key-" + name, SymbolIdentityQuality.CANONICAL, projectId, moduleId,
                fileId, null, SymbolKind.METHOD, name, "pkg." + name, "void " + name + "()", "java",
                new SymbolLocation(fileId, 4, 4, 4, 20, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    private SemanticVectorStore.IndexSnapshot indexOf(List<SemanticDocument> documents, String snapshotId, long builtAt) {
        return new SemanticVectorStore.IndexSnapshot(projectId, snapshotId, provider.id(), provider.modelId(),
                DIMENSIONS, builtAt, documents.stream().map(this::indexed).toList());
    }

    private SemanticVectorStore.IndexSnapshot syntheticIndex(String snapshotId, long builtAt, int count) {
        List<SemanticDocument> documents = new ArrayList<>(count);
        for (int number = count - 1; number >= 0; number--) {
            documents.add(new SemanticDocument("id-" + number, key(number), projectId, snapshotId,
                    SemanticDocumentKind.CHUNK, "sym-alpha", "src/Alpha.java", 4, 4,
                    "synthetic content " + number, "chk-" + number));
        }
        return indexOf(documents, snapshotId, builtAt);
    }

    private SemanticVectorStore.IndexedDocument indexed(SemanticDocument document) {
        return new SemanticVectorStore.IndexedDocument(document,
                new LocalHashEmbeddingProvider(DIMENSIONS).embed(document.stableKey(), document.content()));
    }

    @Test
    void twoPagesReadTheViewOfTheStoreAndKeepTheSameSymbols() throws Exception {
        SemanticCorpusQueryService.DocumentsRequest request =
                new SemanticCorpusQueryService.DocumentsRequest(null, 50, null, null, null, null);

        SemanticCorpusQueryService.DocumentsPage first = corpus.documents(projectId, request);
        SemanticCorpusQueryService.DocumentsPage second = corpus.documents(projectId, request);

        assertEquals(first.documents(), second.documents());
        assertTrue(first.documents().stream().anyMatch(SemanticCorpusQueryService.DocumentSummary::symbolResolved));
        assertEquals(2, snapshots.readViewCalls, "the view is asked of the store for each page");
        assertEquals(1, snapshots.viewBuilds, "the store loads it once");
    }

    @Test
    void anEvictedViewIsNoLongerReachableFromTheService() throws Exception {
        corpus.documents(projectId, new SemanticCorpusQueryService.DocumentsRequest(null, 50, null, null, null, null));
        WeakReference<SnapshotReadView> served = new WeakReference<>(snapshots.view);
        WeakReference<Symbol> servedSymbol = new WeakReference<>(snapshots.view.snapshot().symbols().getFirst());

        // The store drops the view and keeps no symbol of it in memory, as a store that reloads from disk would.
        snapshots.evictAndForgetSymbols();

        for (int attempt = 0; attempt < 50 && (served.get() != null || servedSymbol.get() != null); attempt++) {
            System.gc();
            Thread.onSpinWait();
        }
        assertNull(served.get(), "the service keeps neither the evicted view nor anything that holds it");
        assertNull(servedSymbol.get(), "the service keeps no symbol of the evicted view, without another call");
    }

    @Test
    void aFileIsDescribedByItsSymbolOfSmallestKeyWhateverItsPosition() throws Exception {
        // The symbol of smallest key comes last by position; a symbol whose location names another file and
        // whose key is smaller still does not describe the file of its fileId.
        useSnapshotAndFileDocuments(List.of(
                fileSymbol("zeta", "key-z", "src/Gamma.java", "src/Gamma.java", 2, "java", "early"),
                fileSymbol("alef", "key-b", "src/Gamma.java", "src/Gamma.java", 9, "kotlin", "late"),
                fileSymbol("moved", "key-a", "src/Gamma.java", "src/Other.java", 1, "scala", "elsewhere")),
                "src/Gamma.java");

        SemanticCorpusQueryService.DocumentSummary gamma = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(null, 50, null, null, null, null))
                .documents().getFirst();

        assertEquals("src/Gamma.java", gamma.fileId());
        assertFalse(gamma.symbolResolved());
        assertEquals("kotlin", gamma.language());
        assertEquals("late", gamma.moduleId());
    }

    @Test
    void aSymbolWithoutLocationDescribesTheFileOfItsFileId() throws Exception {
        useSnapshotAndFileDocuments(List.of(
                fileSymbol("loose", "key-l", "src/Delta.java", null, 0, "go", "loose-module")),
                "src/Delta.java");

        SemanticCorpusQueryService.DocumentSummary delta = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(null, 50, null, null, null, null))
                .documents().getFirst();

        assertEquals("go", delta.language());
        assertEquals("loose-module", delta.moduleId());
    }

    private void useSnapshotAndFileDocuments(List<Symbol> symbols, String... fileIds) {
        snapshots.active = new CodeKnowledgeSnapshot(projectUuid, ACTIVE, symbols, List.of(), List.of());
        List<SemanticDocument> documents = new ArrayList<>();
        for (String fileId : fileIds) {
            documents.add(new SemanticDocument("id-" + fileId, "file:" + fileId, projectId, ACTIVE,
                    SemanticDocumentKind.FILE, fileId, fileId, 0, 0, "file " + fileId, "chk-" + fileId));
        }
        vectors.replace(indexOf(documents, ACTIVE, 7_000L));
    }

    private Symbol fileSymbol(String name, String symbolKey, String fileId, String locationFileId, int line,
                              String language, String moduleId) {
        SymbolLocation location = locationFileId == null ? null
                : new SymbolLocation(locationFileId, line, 0, line, 10, PositionEncoding.UTF16_CODE_UNITS);
        return new Symbol("sym-" + name, symbolKey, SymbolIdentityQuality.CANONICAL, projectId, moduleId,
                fileId, null, SymbolKind.METHOD, name, "pkg." + name, "void " + name + "()", language, location,
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    @Test
    void anIndexOfAnotherSnapshotLeavesTheSymbolsUnresolvedWithoutAskingForTheView() throws Exception {
        snapshots.active = new CodeKnowledgeSnapshot(projectUuid, "snapshot-2", snapshots.active.symbols(),
                List.of(), List.of());

        SemanticCorpusQueryService.DocumentsPage page = corpus.documents(projectId,
                new SemanticCorpusQueryService.DocumentsRequest(null, 50, null, null, null, null));

        assertFalse(page.documents().stream().anyMatch(SemanticCorpusQueryService.DocumentSummary::symbolResolved));
        assertEquals(0, snapshots.readViewCalls);
    }

}

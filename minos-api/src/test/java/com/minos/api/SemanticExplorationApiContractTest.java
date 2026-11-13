package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the exploration API: version 1, DTOs that name no internal type, the five readings on a real
 * local home, and failures classified in the published taxonomy.
 */
class SemanticExplorationApiContractTest {

    private Path projectRoot;
    private MinosApplication application;
    private SemanticCodeIntelligenceApi semantic;
    private SemanticExplorationApi api;

    @BeforeEach
    void indexAFixture(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        projectRoot = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/Greeting.java"),
                "package fixture;\n\nclass GreetingService {\n    String greet() { return \"hello\"; }\n}\n",
                StandardCharsets.UTF_8);
        MinosApplication baseline = MinosApplication.open(home);
        var project = baseline.projectRegistry().registerProject(projectRoot, "fixture");
        baseline.snapshotStore().publish(project.id(), "snapshot-1",
                List.of(symbol(project.id().toString())), List.of(), List.of());
        application = MinosApplication.builder(home).embeddingProvider(new LocalHashEmbeddingProvider(64)).build();
        semantic = new LocalSemanticCodeIntelligenceApi(application);
        api = new LocalSemanticExplorationApi(application);
        assertEquals("READY", semantic.synchronizeSemanticIndex("fixture").state());
    }

    @Test
    void theContractIsVersionOneAndLeavesTheSemanticContractUnchanged() {
        assertEquals("1", SemanticExplorationApi.CONTRACT_VERSION);
        assertEquals("1", api.contractVersion());
        assertEquals("1", SemanticCodeIntelligenceApi.CONTRACT_VERSION);
        assertEquals(200, SemanticExplorationApi.MAX_PAGE_SIZE);
    }

    @Test
    void noDtoNamesATypeOutsideTheApiPackageAndTheJdk() {
        List<String> foreign = new ArrayList<>();
        for (Class<?> nested : SemanticExplorationApi.class.getDeclaredClasses()) {
            assertTrue(nested.isRecord(), nested + " is a record");
            for (RecordComponent component : nested.getRecordComponents()) {
                String type = component.getGenericType().getTypeName();
                for (String name : type.split("[<>, ]+")) {
                    boolean allowed = name.isEmpty() || !name.contains(".") || name.startsWith("java.")
                            || name.startsWith("com.minos.api.");
                    if (!allowed) foreign.add(nested.getSimpleName() + "." + component.getName() + ": " + name);
                }
            }
        }
        for (var method : SemanticExplorationApi.class.getDeclaredMethods()) {
            for (Type type : method.getGenericParameterTypes()) {
                String name = type.getTypeName();
                if (name.contains(".") && !name.startsWith("java.") && !name.startsWith("com.minos.api.")) {
                    foreign.add(method.getName() + ": " + name);
                }
            }
        }
        assertEquals(List.of(), foreign);
    }

    @Test
    void theFiveReadingsDescribeOneIndexAndNeverItsRoot() throws Exception {
        List<SemanticExplorationApi.ProjectDto> projects = api.listProjects();
        SemanticExplorationApi.DocumentPageDto page =
                api.listDocuments("fixture", SemanticExplorationApi.DocumentPageQuery.firstPage());
        String chunk = page.documents().stream().filter(document -> "CHUNK".equals(document.kind()))
                .findFirst().orElseThrow().stableKey();
        SemanticExplorationApi.DocumentDetailDto detail = api.getDocument("fixture", chunk);
        SemanticExplorationApi.NeighborsDto neighbors = api.getNeighbors("fixture", chunk, 5);
        SemanticExplorationApi.SearchDto search = api.search("fixture",
                new SemanticExplorationApi.SearchQuery("GreetingService", "semantic", 10, -1.0, null, null, null, null));
        SemanticCodeIntelligenceApi.SemanticSearchDto direct = semantic.semanticSearch("fixture",
                new SemanticCodeIntelligenceApi.SemanticQuery("GreetingService", 10, -1.0));

        assertEquals(1, projects.size());
        assertEquals("fixture", projects.getFirst().name());
        assertEquals("READY", projects.getFirst().state());
        assertEquals(3, page.totalDocuments());
        assertEquals(3, page.documents().size());
        assertNull(page.nextCursor());
        assertNotNull(page.identity());
        assertEquals("snapshot-1", page.identity().snapshotId());
        assertEquals(64, page.identity().documentsFingerprint().length());
        assertEquals(page.identity(), detail.identity());
        assertEquals(page.identity(), neighbors.identity());
        assertEquals(page.identity(), search.identity());
        assertEquals("IDENTIQUE", detail.divergence());
        assertTrue(detail.indexedContent().contains("String greet()"));
        assertEquals("src/Greeting.java", detail.source().fileId());
        assertTrue(detail.document().symbolResolved());
        assertEquals("GreetingService", detail.document().symbolName());
        assertEquals(2, neighbors.neighbors().size());
        assertTrue(neighbors.neighbors().stream().noneMatch(value -> chunk.equals(value.document().stableKey())));
        assertEquals(direct.hits().stream().map(hit -> hit.document().stableKey()).toList(),
                search.hits().stream().map(hit -> hit.document().stableKey()).toList());
        assertEquals(direct.hits().stream().map(SemanticCodeIntelligenceApi.SemanticHitDto::score).toList(),
                search.hits().stream().map(SemanticExplorationApi.SearchHitDto::score).toList());
        assertTrue(search.hits().stream().allMatch(hit -> "COSINE_SIMILARITY".equals(hit.scoreKind())));
        for (Object answer : List.of(projects, page, detail, neighbors, search)) {
            assertFalse(answer.toString().contains(projectRoot.toString()), "absolute root in " + answer);
            assertTrue(answer.toString().contains("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL"),
                    "local-hash limitation in " + answer);
        }
    }

    @Test
    void aHybridSearchNamesItsScoreAndItsSignals() throws Exception {
        SemanticExplorationApi.SearchDto search = api.search("fixture",
                new SemanticExplorationApi.SearchQuery("GreetingService", "HYBRID", 10, 0.0, "symbol", null, "java", null));

        assertFalse(search.hits().isEmpty());
        assertTrue(search.hits().stream().allMatch(hit -> "HYBRID_SCORE".equals(hit.scoreKind())
                && "SYMBOL".equals(hit.document().kind()) && !hit.signals().isEmpty()));
        assertEquals("HYBRID", search.mode());
    }

    @Test
    void failuresAreClassifiedInThePublishedTaxonomy() throws Exception {
        SemanticExplorationApi.DocumentPageDto first = api.listDocuments("fixture",
                new SemanticExplorationApi.DocumentPageQuery(null, 1, null, null, null, null));

        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.getDocument("fixture", "chunk:absent"));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.getNeighbors("fixture", "chunk:absent", 5));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.getNeighbors("fixture", first.documents().getFirst().stableKey(), 201));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.listDocuments("fixture", null));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.listDocuments("unknown-project",
                SemanticExplorationApi.DocumentPageQuery.firstPage()));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.listDocuments("fixture",
                new SemanticExplorationApi.DocumentPageQuery("not a cursor", 10, null, null, null, null)));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.listDocuments("fixture",
                new SemanticExplorationApi.DocumentPageQuery(null, 10, "module", null, null, null)));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.search("fixture",
                new SemanticExplorationApi.SearchQuery("query", "lexical", 10, 0.0, null, null, null, null)));
        assertCode(MinosApi.ErrorCode.INVALID_REQUEST, () -> api.search("fixture",
                new SemanticExplorationApi.SearchQuery("query", "hybrid", 501, 0.0, null, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticExplorationApi.DocumentPageQuery(null, 5_000, null, null, null, null));

        application.semanticVectorStore().delete(first.projectId());
        MinosApi.MinosApiException replaced = assertCode(MinosApi.ErrorCode.UNAVAILABLE,
                () -> api.listDocuments("fixture",
                        new SemanticExplorationApi.DocumentPageQuery(first.nextCursor(), 1, null, null, null, null)));
        assertTrue(replaced.getMessage().contains("start the reading again"), replaced.getMessage());
        assertNull(replaced.getCause());
    }

    @FunctionalInterface
    private interface Call {
        void run() throws MinosApi.MinosApiException;
    }

    private static MinosApi.MinosApiException assertCode(MinosApi.ErrorCode expected, Call call) {
        MinosApi.MinosApiException thrown = assertThrows(MinosApi.MinosApiException.class, call::run);
        assertEquals(expected, thrown.code(), thrown.getMessage());
        return thrown;
    }

    private static Symbol symbol(String projectId) {
        return new Symbol(
                "greeting", "fixture:greeting", SymbolIdentityQuality.CANONICAL, projectId,
                "module", "src/Greeting.java", null, SymbolKind.CLASS,
                "GreetingService", "fixture.GreetingService", "class GreetingService", "java",
                new SymbolLocation("src/Greeting.java", 3, 0, 5, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }
}

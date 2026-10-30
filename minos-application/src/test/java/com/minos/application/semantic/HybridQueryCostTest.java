package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one query costs the stores: a hybrid search over a ready semantic index loads the active snapshot
 * at most once, loads no vector and computes no index size; the index status still reports the size, and
 * an index that changes under a query is still detected.
 */
class HybridQueryCostTest {

    private static final String SNAPSHOT_ID = "snapshot-1";
    private static final long INDEX_SIZE_BYTES = 4_096L;

    private CountingSnapshotStore snapshots;
    private CountingVectorStore vectors;
    private SemanticIndexService index;
    private SemanticSearchService semanticSearch;
    private HybridSearchService hybrid;
    private String project;

    @BeforeEach
    void wire(@TempDir Path root) throws Exception {
        InMemoryProjectRegistry registry = new InMemoryProjectRegistry();
        RegisteredProject registered = registry.registerProject(root, "query-cost");
        project = registered.id().toString();
        snapshots = new CountingSnapshotStore(new CodeKnowledgeSnapshot(registered.id(), SNAPSHOT_ID,
                List.of(symbol(project, "alpha"), symbol(project, "beta")), List.of(), List.of()));
        vectors = new CountingVectorStore(new SemanticVectorStore.IndexSnapshot(project, SNAPSHOT_ID, "fake", "model",
                2, 1_000L, List.of(indexed(project, "alpha", SNAPSHOT_ID), indexed(project, "beta", SNAPSHOT_ID))));
        ProjectResolver resolver = new ProjectResolver(registry);
        index = new SemanticIndexService(resolver, snapshots, vectors, Optional.of(new FixedEmbeddingProvider()));
        semanticSearch = new SemanticSearchService(index);
        hybrid = new HybridSearchService(resolver, snapshots, index, semanticSearch);
    }

    @Test
    void aHybridSearchLoadsTheActiveSnapshotAtMostOnce() throws Exception {
        HybridSearchService.HybridResponse response = hybrid.search(project, request());

        assertTrue(response.semanticAvailable());
        assertFalse(response.hits().isEmpty());
        assertTrue(snapshots.loads <= 1, "active snapshot loads: " + snapshots.loads);
    }

    @Test
    void aHybridSearchLoadsNoVectorAndComputesNoIndexSize() throws Exception {
        hybrid.search(project, request());

        assertEquals(0, vectors.loads, "full index loads");
        assertEquals(0, vectors.sizeCalls, "index size computations");
    }

    @Test
    void aSecondHybridSearchOverTheSameIndexReadsNoDocument() throws Exception {
        hybrid.search(project, request());
        int afterFirst = vectors.documentReads;

        hybrid.search(project, request());

        assertEquals(1, afterFirst, "the corpus is built from one read of the documents");
        assertEquals(afterFirst, vectors.documentReads, "the cached corpus is reused");
    }

    @Test
    void theIndexStatusStillReportsItsSize() throws Exception {
        SemanticIndexService.Status status = index.status(project);

        assertEquals(SemanticIndexService.State.READY, status.state());
        assertEquals(INDEX_SIZE_BYTES, status.indexSizeBytes());
    }

    @Test
    void hitsOfAnotherIndexAreNeverReturned() throws Exception {
        vectors.hitSnapshotId = "snapshot-of-another-index";

        SemanticSearchService.SearchResponse response = semanticSearch.search(project,
                new SemanticSearchService.SearchRequest("alpha", 5, 0.0));

        assertTrue(response.hits().isEmpty());
        assertTrue(response.limitations().contains("SEMANTIC_INDEX_CHANGED_DURING_QUERY"), response.limitations().toString());
    }

    private static HybridSearchService.HybridRequest request() {
        return new HybridSearchService.HybridRequest("alpha", 5, 0.0);
    }

    private static Symbol symbol(String projectId, String name) {
        return new Symbol("sym-" + name, "key-" + name, SymbolIdentityQuality.CANONICAL, projectId, null,
                "src/" + name + ".java", null, SymbolKind.METHOD, name, "pkg." + name, "void " + name + "()", "java",
                new SymbolLocation("src/" + name + ".java", 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    private static SemanticVectorStore.IndexedDocument indexed(String projectId, String name, String snapshotId) {
        String key = "symbol:" + name;
        return new SemanticVectorStore.IndexedDocument(document(projectId, name, snapshotId),
                SemanticVector.fromArray(key, new double[] {1.0, 0.0}));
    }

    private static SemanticDocument document(String projectId, String name, String snapshotId) {
        return new SemanticDocument("doc-" + name + "-" + snapshotId, "symbol:" + name, projectId, snapshotId,
                SemanticDocumentKind.SYMBOL, "sym-" + name, "src/" + name + ".java", 1, 1,
                "method " + name + " of the fixture", "checksum-" + name);
    }

    /** Counts full loads apart from reads of the active identifier alone. */
    private static final class CountingSnapshotStore implements CodeKnowledgeSnapshotStore {
        private final CodeKnowledgeSnapshot active;
        private int loads;

        CountingSnapshotStore(CodeKnowledgeSnapshot active) {
            this.active = active;
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            loads++;
            return Optional.of(active);
        }

        @Override
        public Optional<String> activeSnapshotId(UUID projectId) {
            return Optional.of(active.snapshotId());
        }

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            throw new UnsupportedOperationException("legacy symbol snapshots are not used by this test");
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            throw new UnsupportedOperationException("query views are not used by this test");
        }
    }

    /** A store that answers metadata, documents and search without its vectors, and counts every access. */
    private static final class CountingVectorStore implements SemanticVectorStore {
        private final IndexSnapshot index;
        private int loads;
        private int sizeCalls;
        private int documentReads;
        private String hitSnapshotId = SNAPSHOT_ID;

        CountingVectorStore(IndexSnapshot index) {
            this.index = index;
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            loads++;
            return Optional.of(index);
        }

        @Override
        public Optional<IndexMetadata> metadata(String projectId) {
            return Optional.of(new IndexMetadata(index.projectId(), index.snapshotId(), index.providerId(),
                    index.modelId(), index.dimensions(), index.builtAtEpochMilli(), index.documents().size()));
        }

        @Override
        public Optional<IndexDocuments> documents(String projectId) {
            documentReads++;
            return Optional.of(new IndexDocuments(metadata(projectId).orElseThrow(),
                    index.documents().stream().map(IndexedDocument::document).toList()));
        }

        @Override
        public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore) {
            return index.documents().stream()
                    .map(value -> new VectorHit(document(projectId, value.document().sourceId().substring(4),
                            hitSnapshotId), 0.5))
                    .toList();
        }

        @Override
        public long sizeBytes(String projectId) {
            sizeCalls++;
            return INDEX_SIZE_BYTES;
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            throw new UnsupportedOperationException("the index is not written by this test");
        }

        @Override
        public void delete(String projectId) {
            throw new UnsupportedOperationException("the index is not written by this test");
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) {
            throw new UnsupportedOperationException("the index is not written by this test");
        }
    }

    private static final class FixedEmbeddingProvider implements EmbeddingProvider {
        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String modelId() {
            return "model";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public SemanticVector embed(String stableKey, String text) {
            return SemanticVector.fromArray(stableKey, new double[] {1.0, 0.0});
        }
    }

    private static final class InMemoryProjectRegistry implements ProjectRegistry {
        private final Map<UUID, RegisteredProject> projects = new HashMap<>();

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            Instant now = Instant.parse("2026-10-13T08:00:00Z");
            RegisteredProject project = new RegisteredProject(
                    UUID.randomUUID(), rootPath, displayName, Optional.empty(), now, now);
            projects.put(project.id(), project);
            return project;
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public List<RegisteredProject> listProjects() {
            return List.copyOf(projects.values());
        }

        @Override
        public RegisteredWorkspace createWorkspace(String name) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public RegisteredProject removeProjectFromWorkspace(UUID projectId) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredWorkspace> listWorkspaces() {
            return List.of();
        }
    }
}

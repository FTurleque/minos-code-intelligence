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
import com.minos.semantic.StaleSemanticSyncException;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the query path sees of the semantic index in every state: the active snapshot is identified without
 * being loaded, the size is computed only for the surfaced status, an index that is not ready is never
 * served, and the cached corpus carries the identity of the index its documents were read from.
 */
class SemanticQueryPathStatesTest {

    private static final String ACTIVE = "snapshot-1";
    private static final String NEWER = "snapshot-2";
    private static final String PROVIDER = "fake";
    private static final long INDEX_SIZE_BYTES = 4_096L;

    private UUID projectId;
    private String project;
    private ProjectResolver resolver;
    private SwitchableSnapshotStore snapshots;
    private MutableVectorStore vectors;
    private SemanticIndexService index;
    private HybridSearchService hybrid;

    @BeforeEach
    void wire(@TempDir Path root) {
        InMemoryProjectRegistry registry = new InMemoryProjectRegistry();
        projectId = registry.registerProject(root, "query-path-states").id();
        project = projectId.toString();
        resolver = new ProjectResolver(registry);
        snapshots = new SwitchableSnapshotStore(knowledge(ACTIVE));
        vectors = new MutableVectorStore(index(ACTIVE, PROVIDER, 1_000L, "alpha", "beta"));
        index = service(Optional.of(new FixedEmbeddingProvider()));
        hybrid = new HybridSearchService(resolver, snapshots, index, new SemanticSearchService(index));
    }

    @Test
    void theQueryPathStatusOfAReadyIndexCarriesNoSizeAndLoadsNothing() throws Exception {
        SemanticIndexService.Status status = index.statusWithoutSize(project);

        assertEquals(SemanticIndexService.State.READY, status.state());
        assertEquals(ACTIVE, status.activeSnapshotId());
        assertEquals(ACTIVE, status.indexedSnapshotId());
        assertEquals(2, status.documentCount());
        assertEquals(SemanticIndexService.SIZE_NOT_COMPUTED, status.indexSizeBytes());
        assertEquals(0, vectors.sizeCalls, "index size computations");
        assertEquals(0, vectors.loads, "full index loads");
        assertEquals(0, snapshots.loads, "active snapshot loads");
    }

    @Test
    void theSurfacedStatusReportsTheSizeWhetherTheProjectIsNamedOrIdentified() throws Exception {
        SemanticIndexService.Status byReference = index.status(project);
        SemanticIndexService.Status byId = index.status(projectId);

        assertEquals(byReference, byId);
        assertEquals(INDEX_SIZE_BYTES, byId.indexSizeBytes());
        assertEquals(0, snapshots.loads, "the surfaced status identifies the active snapshot without loading it");
    }

    @Test
    void withoutAnEmbeddingProviderTheIndexIsDisabledAndStillDescribed() throws Exception {
        SemanticIndexService disabled = service(Optional.empty());

        SemanticIndexService.Status surfaced = disabled.status(project);
        SemanticIndexService.Status onQueryPath = disabled.statusWithoutSize(project);

        assertEquals(SemanticIndexService.State.DISABLED, onQueryPath.state());
        assertEquals(ACTIVE, onQueryPath.activeSnapshotId());
        assertEquals(ACTIVE, onQueryPath.indexedSnapshotId());
        assertEquals(2, onQueryPath.documentCount());
        assertNull(onQueryPath.providerId());
        assertEquals(List.of("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"), onQueryPath.limitations());
        assertEquals(SemanticIndexService.SIZE_NOT_COMPUTED, onQueryPath.indexSizeBytes());
        assertEquals(INDEX_SIZE_BYTES, surfaced.indexSizeBytes());
        assertEquals(1, vectors.sizeCalls, "only the surfaced status computes the size");
        assertEquals(0, snapshots.loads, "active snapshot loads");
    }

    @Test
    void aDisabledIndexWithNeitherSnapshotNorIndexNamesNeither() throws Exception {
        snapshots.active = null;
        vectors.index = null;

        SemanticIndexService.Status status = service(Optional.empty()).statusWithoutSize(project);

        assertEquals(SemanticIndexService.State.DISABLED, status.state());
        assertNull(status.activeSnapshotId());
        assertNull(status.indexedSnapshotId());
        assertEquals(0, status.documentCount());
    }

    @Test
    void withoutAnActiveSnapshotTheStoredIndexIsNamedButNotReady() throws Exception {
        snapshots.active = null;

        SemanticIndexService.Status surfaced = index.status(project);
        SemanticIndexService.Status onQueryPath = index.statusWithoutSize(project);

        assertEquals(SemanticIndexService.State.NO_ACTIVE_SNAPSHOT, onQueryPath.state());
        assertNull(onQueryPath.activeSnapshotId());
        assertEquals(ACTIVE, onQueryPath.indexedSnapshotId());
        assertEquals(2, onQueryPath.documentCount());
        assertEquals(List.of("ACTIVE_KNOWLEDGE_SNAPSHOT_UNAVAILABLE"), onQueryPath.limitations());
        assertEquals(SemanticIndexService.SIZE_NOT_COMPUTED, onQueryPath.indexSizeBytes());
        assertEquals(INDEX_SIZE_BYTES, surfaced.indexSizeBytes());
    }

    @Test
    void withoutAStoredIndexTheStatusIsMissingAndNoSizeIsAsked() throws Exception {
        vectors.index = null;

        SemanticIndexService.Status status = index.status(project);

        assertEquals(SemanticIndexService.State.MISSING, status.state());
        assertEquals(ACTIVE, status.activeSnapshotId());
        assertNull(status.indexedSnapshotId());
        assertEquals(0, status.documentCount());
        assertEquals(0L, status.indexSizeBytes());
        assertEquals(List.of("SEMANTIC_INDEX_MISSING"), status.limitations());
        assertEquals(0, vectors.sizeCalls, "index size computations");
    }

    @Test
    void anIndexBuiltForAnotherSnapshotIsStale() throws Exception {
        snapshots.active = knowledge(NEWER);

        SemanticIndexService.Status status = index.statusWithoutSize(project);

        assertEquals(SemanticIndexService.State.STALE, status.state());
        assertEquals(NEWER, status.activeSnapshotId());
        assertEquals(ACTIVE, status.indexedSnapshotId());
        assertEquals(List.of("SEMANTIC_INDEX_SNAPSHOT_STALE"), status.limitations());
    }

    @Test
    void anIndexBuiltByAnotherProviderIsStale() throws Exception {
        vectors.index = index(ACTIVE, "another-provider", 1_000L, "alpha");

        SemanticIndexService.Status status = index.statusWithoutSize(project);

        assertEquals(SemanticIndexService.State.STALE, status.state());
        assertEquals(List.of("SEMANTIC_INDEX_MODEL_STALE"), status.limitations());
    }

    @Test
    void theActiveIndexOfAReadyProjectIsLoadedWithoutLoadingTheSnapshotNorSizingTheIndex() throws Exception {
        Optional<SemanticVectorStore.IndexSnapshot> active = index.activeIndex(project);

        assertEquals(Optional.of(vectors.index), active);
        assertEquals(1, vectors.loads, "full index loads");
        assertEquals(0, vectors.sizeCalls, "index size computations");
        assertEquals(0, snapshots.loads, "active snapshot loads");
    }

    @Test
    void aStaleIndexIsNeverLoadedAsTheActiveIndex() throws Exception {
        snapshots.active = knowledge(NEWER);

        Optional<SemanticVectorStore.IndexSnapshot> active = index.activeIndex(project);

        assertEquals(Optional.empty(), active);
        assertEquals(0, vectors.loads, "full index loads");
    }

    @Test
    void aSynchronizationCommitsAfterReadingTheActiveIdentifierAlone() throws Exception {
        vectors.index = null;

        SemanticIndexService.UpdateReport report = index.synchronize(project);

        assertEquals(SemanticIndexService.State.READY, report.state());
        assertEquals(ACTIVE, report.snapshotId());
        assertEquals(ACTIVE, vectors.index.snapshotId());
        assertEquals(report.documentCount(), vectors.index.documents().size());
        assertEquals(1, snapshots.loads, "the build loads the snapshot once; the commit check does not");
        assertEquals(1, snapshots.identifierReads, "the commit check reads the active identifier");
        assertEquals(SemanticIndexService.State.READY, index.statusWithoutSize(project).state());
    }

    @Test
    void aSynchronizationOvertakenByANewSnapshotIsRefusedAndWritesNothing() {
        vectors.index = null;
        snapshots.activeAfterNextLoad = knowledge(NEWER);

        StaleSemanticSyncException refusal = assertThrows(StaleSemanticSyncException.class,
                () -> index.synchronize(project));

        assertTrue(refusal.getMessage().contains("built for snapshot " + ACTIVE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("active snapshot is now " + NEWER), refusal.getMessage());
        assertNull(vectors.index);
    }

    @Test
    void aHybridSearchOverAStaleIndexRanksTheActiveSnapshotAndReadsNothingFromTheIndex() throws Exception {
        snapshots.active = knowledge(NEWER);

        HybridSearchService.HybridResponse response = hybrid.search(project, request("alpha"));

        assertFalse(response.semanticAvailable());
        assertEquals(NEWER, response.snapshotId());
        assertTrue(response.limitations().contains("SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED"),
                response.limitations().toString());
        assertFalse(response.hits().isEmpty());
        assertEquals(Set.of("LEXICAL_GRAPH"), rankingModes(response.hits()));
        assertEquals(Set.of(NEWER), snapshotIds(response.hits()), "no document of the stale index is served");
        assertEquals(0, vectors.documentReads, "index document reads");
        assertEquals(0, vectors.loads, "full index loads");
        assertEquals(0, vectors.searches, "vector searches");
    }

    @Test
    void theLexicalBaselineRanksTheActiveSnapshotWithoutTheSemanticIndex() throws Exception {
        List<HybridSearchService.HybridHit> all = hybrid.lexicalBaseline(project, "alpha", 5);
        List<HybridSearchService.HybridHit> best = hybrid.lexicalBaseline(project, "alpha", 1);

        assertTrue(all.size() > 1, "hits: " + all.size());
        assertEquals(1, best.size());
        assertEquals(all.getFirst(), best.getFirst());
        assertEquals(Set.of("LEXICAL"), rankingModes(all));
        assertEquals(Set.of("src/alpha.java"),
                all.stream().map(hit -> hit.document().fileId()).collect(Collectors.toSet()));
        assertEquals(Set.of(ACTIVE), snapshotIds(all));
        assertEquals(0, vectors.documentReads, "index document reads");
        assertEquals(0, vectors.loads, "full index loads");
    }

    @Test
    void aReplacedIndexIsReadAgainAndItsDocumentsReplaceTheCachedOnes() throws Exception {
        hybrid.search(project, request("alpha"));
        vectors.index = index(ACTIVE, PROVIDER, 2_000L, "alpha", "gamma");

        HybridSearchService.HybridResponse response = hybrid.search(project, request("alpha"));

        assertEquals(2, vectors.documentReads, "one read per index identity");
        assertEquals(Set.of("sym-alpha", "sym-gamma"), sourceIds(response.hits()));
        assertEquals(0, vectors.loads, "full index loads");
    }

    @Test
    void aCorpusReadFromAnIndexReplacedMeanwhileIsCachedUnderTheIdentityOfThatIndex() throws Exception {
        vectors.replacementOnNextDocumentRead = index(ACTIVE, PROVIDER, 2_000L, "alpha", "gamma");

        HybridSearchService.HybridResponse first = hybrid.search(project, request("alpha"));
        HybridSearchService.HybridResponse second = hybrid.search(project, request("alpha"));

        assertEquals(Set.of("sym-alpha", "sym-gamma"), sourceIds(first.hits()));
        assertEquals(sourceIds(first.hits()), sourceIds(second.hits()));
        assertEquals(1, vectors.documentReads, "the corpus of the replacing index is reused, not read again");
    }

    private SemanticIndexService service(Optional<EmbeddingProvider> provider) {
        return new SemanticIndexService(resolver, snapshots, vectors, provider);
    }

    private static HybridSearchService.HybridRequest request(String query) {
        return new HybridSearchService.HybridRequest(query, 5, 0.0);
    }

    private static Set<String> rankingModes(List<HybridSearchService.HybridHit> hits) {
        return hits.stream().map(HybridSearchService.HybridHit::rankingMode).collect(Collectors.toSet());
    }

    private static Set<String> snapshotIds(List<HybridSearchService.HybridHit> hits) {
        return hits.stream().map(hit -> hit.document().snapshotId()).collect(Collectors.toSet());
    }

    private static Set<String> sourceIds(List<HybridSearchService.HybridHit> hits) {
        return hits.stream().map(hit -> hit.document().sourceId()).collect(Collectors.toSet());
    }

    private CodeKnowledgeSnapshot knowledge(String snapshotId) {
        return new CodeKnowledgeSnapshot(projectId, snapshotId, List.of(symbol("alpha"), symbol("beta")),
                List.of(), List.of());
    }

    private Symbol symbol(String name) {
        return new Symbol("sym-" + name, "key-" + name, SymbolIdentityQuality.CANONICAL, project, null,
                "src/" + name + ".java", null, SymbolKind.METHOD, name, "pkg." + name, "void " + name + "()", "java",
                new SymbolLocation("src/" + name + ".java", 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    private SemanticVectorStore.IndexSnapshot index(String snapshotId, String providerId, long builtAt,
                                                    String... names) {
        return new SemanticVectorStore.IndexSnapshot(project, snapshotId, providerId, "model", 2, builtAt,
                Arrays.stream(names).map(name -> indexed(name, snapshotId)).toList());
    }

    private SemanticVectorStore.IndexedDocument indexed(String name, String snapshotId) {
        String key = "symbol:" + name;
        SemanticDocument document = new SemanticDocument("doc-" + name + "-" + snapshotId, key, project, snapshotId,
                SemanticDocumentKind.SYMBOL, "sym-" + name, "src/" + name + ".java", 1, 1,
                "method " + name + " of the fixture", "checksum-" + name);
        return new SemanticVectorStore.IndexedDocument(document, SemanticVector.fromArray(key, new double[] {1.0, 0.0}));
    }

    /** An active snapshot that can be absent or replaced; full loads and identifier reads are counted apart. */
    private static final class SwitchableSnapshotStore implements CodeKnowledgeSnapshotStore {
        private CodeKnowledgeSnapshot active;
        private CodeKnowledgeSnapshot activeAfterNextLoad;
        private int loads;
        private int identifierReads;

        SwitchableSnapshotStore(CodeKnowledgeSnapshot active) {
            this.active = active;
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            loads++;
            Optional<CodeKnowledgeSnapshot> loaded = Optional.ofNullable(active);
            if (activeAfterNextLoad != null) {
                active = activeAfterNextLoad;
                activeAfterNextLoad = null;
            }
            return loaded;
        }

        @Override
        public Optional<String> activeSnapshotId(UUID projectId) {
            identifierReads++;
            return Optional.ofNullable(active).map(CodeKnowledgeSnapshot::snapshotId);
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

    /**
     * An index that can be absent or replaced, read part by part; a conditional replacement follows the
     * contract of the port: it is refused when the active snapshot is no longer the expected one.
     */
    private static final class MutableVectorStore implements SemanticVectorStore {
        private IndexSnapshot index;
        private IndexSnapshot replacementOnNextDocumentRead;
        private int loads;
        private int sizeCalls;
        private int documentReads;
        private int searches;

        MutableVectorStore(IndexSnapshot index) {
            this.index = index;
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            loads++;
            return Optional.ofNullable(index);
        }

        @Override
        public Optional<IndexMetadata> metadata(String projectId) {
            return Optional.ofNullable(index).map(MutableVectorStore::metadataOf);
        }

        @Override
        public Optional<IndexDocuments> documents(String projectId) {
            documentReads++;
            if (replacementOnNextDocumentRead != null) {
                index = replacementOnNextDocumentRead;
                replacementOnNextDocumentRead = null;
            }
            return Optional.ofNullable(index).map(value -> new IndexDocuments(metadataOf(value),
                    value.documents().stream().map(IndexedDocument::document).toList()));
        }

        @Override
        public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore) {
            searches++;
            return index.documents().stream().map(value -> new VectorHit(value.document(), 0.5)).toList();
        }

        @Override
        public long sizeBytes(String projectId) {
            sizeCalls++;
            return INDEX_SIZE_BYTES;
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) throws IOException {
            String current = activeSnapshotReader.read().orElse("absent");
            if (!expectedActiveSnapshotId.equals(current)) {
                throw new StaleSemanticSyncException(next.projectId(), expectedActiveSnapshotId, current);
            }
            index = next;
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            throw new UnsupportedOperationException("unconditional replacement is not used by this test");
        }

        @Override
        public void delete(String projectId) {
            throw new UnsupportedOperationException("deletion is not used by this test");
        }

        private static IndexMetadata metadataOf(IndexSnapshot value) {
            return new IndexMetadata(value.projectId(), value.snapshotId(), value.providerId(), value.modelId(),
                    value.dimensions(), value.builtAtEpochMilli(), value.documents().size());
        }
    }

    private static final class FixedEmbeddingProvider implements EmbeddingProvider {
        @Override
        public String id() {
            return PROVIDER;
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
            RegisteredProject registered = new RegisteredProject(
                    UUID.randomUUID(), rootPath, displayName, Optional.empty(), now, now);
            projects.put(registered.id(), registered);
            return registered;
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

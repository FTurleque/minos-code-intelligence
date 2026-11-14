package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticProjectionStore;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One registered project with a semantic index held in memory and a projection store held in memory, wired to
 * the projection service. The vector store counts what is read from it; the provider and the search refuse
 * every call.
 */
final class ProjectionFixture {

    static final String ACTIVE = "snapshot-1";
    static final int DIMENSIONS = 32;

    final CountingVectorStore vectors = new CountingVectorStore();
    final InMemoryProjectionStore projections = new InMemoryProjectionStore();
    final RefusingProvider provider = new RefusingProvider();
    final String projectId;
    final SemanticProjectionService service;
    private String activeSnapshotId = ACTIVE;
    private long builtAt = 1_000L;

    ProjectionFixture(Path root) {
        Registry registry = new Registry();
        RegisteredProject project = registry.registerProject(root, "projection-fixture");
        projectId = project.id().toString();
        ProjectResolver resolver = new ProjectResolver(registry);
        Snapshots snapshots = new Snapshots();
        SemanticIndexService index = new SemanticIndexService(resolver, snapshots, vectors, Optional.of(provider));
        SemanticSearchService search = new SemanticSearchService(index);
        SemanticCorpusQueryService corpus = new SemanticCorpusQueryService(registry, resolver, snapshots, index,
                search, new HybridSearchService(resolver, snapshots, index, search));
        service = new SemanticProjectionService(resolver, index, corpus, projections);
    }

    /** Replaces the index by {@code count} chunks whose vectors are those of the deterministic local provider. */
    void index(int count) {
        index(ACTIVE, count, "content ");
    }

    void index(String snapshotId, int count, String contentPrefix) {
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>(count);
        for (int number = 0; number < count; number++) {
            documents.add(document(snapshotId, SemanticDocumentKind.CHUNK, key("chunk", number),
                    contentPrefix + number));
        }
        replace(snapshotId, documents);
    }

    void replace(String snapshotId, List<SemanticVectorStore.IndexedDocument> documents) {
        vectors.replace(new SemanticVectorStore.IndexSnapshot(projectId, snapshotId, provider.id(),
                provider.modelId(), DIMENSIONS, ++builtAt, documents));
    }

    void activate(String snapshotId) {
        activeSnapshotId = snapshotId;
    }

    SemanticVectorStore.IndexedDocument document(String snapshotId, SemanticDocumentKind kind, String stableKey,
                                                 String content) {
        return document(snapshotId, kind, stableKey, content,
                new LocalHashEmbeddingProvider(DIMENSIONS).embed(stableKey, content));
    }

    SemanticVectorStore.IndexedDocument document(String snapshotId, SemanticDocumentKind kind, String stableKey,
                                                 String content, SemanticVector vector) {
        return new SemanticVectorStore.IndexedDocument(new SemanticDocument("id-" + stableKey, stableKey, projectId,
                snapshotId, kind, "source", "src/File.java", 1, 2, content, "chk-" + content.hashCode()), vector);
    }

    static String key(String prefix, int number) {
        return String.format(Locale.ROOT, "%s:doc-%05d", prefix, number);
    }

    static SemanticProjectionService.PreparationRequest request(int maxPoints, long seed) {
        return new SemanticProjectionService.PreparationRequest(maxPoints, seed,
                java.util.Set.of(SemanticDocumentKind.CHUNK), 64L * 1024L * 1024L);
    }

    /** A provider no preparation and no reading may call. */
    static final class RefusingProvider implements EmbeddingProvider {
        int calls;

        @Override public String id() { return "fixture-provider"; }
        @Override public String modelId() { return "fixture-model"; }
        @Override public int dimensions() { return DIMENSIONS; }

        @Override
        public SemanticVector embed(String stableKey, String text) throws IOException {
            calls++;
            throw new IOException("the embedding provider must not be called");
        }
    }

    /** One index per project; every reading of vectors is counted, and a page may trigger a replacement. */
    static final class CountingVectorStore implements SemanticVectorStore {
        private final Map<String, IndexSnapshot> indexes = new HashMap<>();
        int fullLoads;
        int vectorPages;
        int storedDocuments;
        int searches;
        int largestPage;
        boolean refuseFullLoad;
        Runnable afterVectorPage;

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            fullLoads++;
            if (refuseFullLoad) throw new AssertionError("the whole index must not be loaded");
            return Optional.ofNullable(indexes.get(projectId));
        }

        @Override
        public Optional<IndexMetadata> metadata(String projectId) {
            IndexSnapshot index = indexes.get(projectId);
            return index == null ? Optional.empty() : Optional.of(new IndexMetadata(index.projectId(),
                    index.snapshotId(), index.providerId(), index.modelId(), index.dimensions(),
                    index.builtAtEpochMilli(), index.documents().size()));
        }

        @Override
        public Optional<IndexChecksums> checksums(String projectId) {
            IndexSnapshot index = indexes.get(projectId);
            if (index == null) return Optional.empty();
            Map<String, String> checksums = new HashMap<>();
            for (IndexedDocument value : index.documents()) {
                checksums.put(value.document().stableKey(), value.document().checksum());
            }
            return Optional.of(new IndexChecksums(metadata(projectId).orElseThrow(), checksums));
        }

        @Override
        public Optional<VectorPage> vectorPage(String projectId, String afterStableKey, int limit) {
            SemanticVectorStore.requireVectorPageBounds(afterStableKey, limit);
            vectorPages++;
            IndexSnapshot index = indexes.get(projectId);
            if (index == null) return Optional.empty();
            List<IndexedDocument> ordered = new ArrayList<>(index.documents());
            ordered.sort(java.util.Comparator.comparing(value -> value.document().stableKey()));
            List<StoredVector> page = new ArrayList<>();
            boolean hasMore = false;
            for (IndexedDocument value : ordered) {
                if (afterStableKey != null && value.document().stableKey().compareTo(afterStableKey) <= 0) continue;
                if (page.size() == limit) {
                    hasMore = true;
                    break;
                }
                page.add(new StoredVector(value.document().kind(), value.vector()));
            }
            largestPage = Math.max(largestPage, page.size());
            VectorPage read = new VectorPage(metadata(projectId).orElseThrow(), page, hasMore);
            if (afterVectorPage != null) afterVectorPage.run();
            return Optional.of(read);
        }

        @Override
        public Optional<StoredDocument> storedDocument(String projectId, String stableKey) throws IOException {
            storedDocuments++;
            return SemanticVectorStore.super.storedDocument(projectId, stableKey);
        }

        @Override
        public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore) {
            searches++;
            throw new AssertionError("the search must not be called");
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            indexes.put(snapshot.projectId(), snapshot);
        }

        @Override
        public void delete(String projectId) {
            indexes.remove(projectId);
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) {
            throw new UnsupportedOperationException("conditional replacement is not used by this test");
        }

        int vectorReads() {
            return fullLoads + vectorPages + storedDocuments + searches;
        }
    }

    /** One projection per project; what is stored can be declared unreadable, and a preparation be held. */
    static final class InMemoryProjectionStore implements SemanticProjectionStore {
        private final Map<String, SemanticProjection> published = new HashMap<>();
        boolean unreadable;
        boolean busy;
        int publications;

        @Override
        public Optional<SemanticProjection> read(String projectId) throws IOException {
            if (unreadable) throw new UnreadableProjectionException("the stored projection is unreadable");
            return Optional.ofNullable(published.get(projectId));
        }

        @Override
        public Preparation prepare(String projectId) throws IOException {
            if (busy) throw new PreparationInProgressException(null);
            return new Preparation() {
                @Override
                public void publish(SemanticProjection projection) {
                    publications++;
                    published.put(projectId, projection);
                }

                @Override
                public void close() {
                    // Nothing is held by this store.
                }
            };
        }
    }

    private final class Snapshots implements CodeKnowledgeSnapshotStore {
        @Override
        public Optional<String> activeSnapshotId(UUID id) {
            return Optional.ofNullable(activeSnapshotId);
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID id) {
            return Optional.empty();
        }

        @Override
        public SymbolSnapshot publish(UUID id, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID id, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID id) {
            throw new UnsupportedOperationException("legacy symbol snapshots are not used by this test");
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID id) {
            throw new UnsupportedOperationException("query views are not used by this test");
        }
    }

    private static final class Registry implements ProjectRegistry {
        private final Map<UUID, RegisteredProject> projects = new HashMap<>();

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            Instant now = Instant.parse("2026-11-14T08:00:00Z");
            RegisteredProject registered = new RegisteredProject(
                    UUID.randomUUID(), rootPath, displayName, Optional.empty(), now, now);
            projects.put(registered.id(), registered);
            return registered;
        }

        @Override public Optional<RegisteredProject> findProject(UUID id) { return Optional.ofNullable(projects.get(id)); }
        @Override public List<RegisteredProject> listProjects() { return List.copyOf(projects.values()); }

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

        @Override public Optional<RegisteredWorkspace> findWorkspace(UUID id) { return Optional.empty(); }
        @Override public List<RegisteredWorkspace> listWorkspaces() { return List.of(); }
    }
}

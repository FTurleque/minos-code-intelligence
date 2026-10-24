package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Maintains an optional reconstructible semantic index aligned with the active MINOS snapshot. */
public final class SemanticIndexService {

    /** {@code indexSizeBytes} of a status built for the query path: the size was not computed. */
    public static final long SIZE_NOT_COMPUTED = -1L;

    public static final int MAX_DOCUMENTS = SemanticIndexBudget.DEFAULT.maxDocuments();
    private static final long MAX_SYNCHRONIZE_WORKING_SET_BYTES = 768L * 1024L * 1024L;
    private static final long INDEX_OBJECT_OVERHEAD_BYTES = 512L;
    private static final long PERSISTED_INDEX_AMPLIFICATION = 3L;

    private final ProjectResolver projects;
    private final CodeKnowledgeSnapshotStore snapshots;
    private final SemanticVectorStore store;
    private final Optional<EmbeddingProvider> embeddingProvider;
    private final SemanticDocumentFactory documents;

    public SemanticIndexService(
            ProjectResolver projects,
            CodeKnowledgeSnapshotStore snapshots,
            SemanticVectorStore store,
            Optional<EmbeddingProvider> embeddingProvider
    ) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.store = Objects.requireNonNull(store, "store");
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.documents = new SemanticDocumentFactory();
        embeddingProvider.ifPresent(SemanticIndexService::validateProvider);
    }

    public Optional<EmbeddingProvider> embeddingProvider() { return embeddingProvider; }
    public SemanticVectorStore vectorStore() { return store; }

    public Status status(String projectReference) throws IOException { return status(projects.resolve(projectReference), true); }
    public Status status(UUID projectId) throws IOException { return status(projects.resolveById(projectId), true); }

    /**
     * State and identity of the index for the query path: the active snapshot is identified without being
     * loaded and the index size is not computed: {@code indexSizeBytes} is {@link #SIZE_NOT_COMPUTED},
     * never a size. Not an index status to surface: {@link #status(String)} reports the size.
     */
    public Status statusWithoutSize(String projectReference) throws IOException {
        return status(projects.resolve(projectReference), false);
    }
    public UpdateReport synchronize(String projectReference) throws IOException { return synchronize(projects.resolve(projectReference)); }
    public UpdateReport synchronize(UUID projectId) throws IOException { return synchronize(projects.resolveById(projectId)); }

    public Optional<SemanticVectorStore.IndexSnapshot> activeIndex(String projectReference) throws IOException {
        RegisteredProject project = projects.resolve(projectReference);
        Status status = status(project, false);
        if (status.state() != State.READY) return Optional.empty();
        return store.load(project.id().toString());
    }

    private Status status(RegisteredProject project, boolean withSize) throws IOException {
        String projectId = project.id().toString();
        Optional<String> active = snapshots.activeSnapshotId(project.id());
        Optional<SemanticVectorStore.IndexMetadata> stored = store.metadata(projectId);
        if (embeddingProvider.isEmpty()) {
            return new Status(projectId, project.displayName(), State.DISABLED,
                    active.orElse(null),
                    stored.map(SemanticVectorStore.IndexMetadata::snapshotId).orElse(null),
                    null, null, 0, stored.map(SemanticVectorStore.IndexMetadata::documentCount).orElse(0),
                    sizeBytes(projectId, withSize), List.of("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"));
        }
        EmbeddingProvider provider = embeddingProvider.orElseThrow();
        if (active.isEmpty()) {
            return new Status(projectId, project.displayName(), State.NO_ACTIVE_SNAPSHOT,
                    null, stored.map(SemanticVectorStore.IndexMetadata::snapshotId).orElse(null),
                    provider.id(), provider.modelId(), provider.dimensions(),
                    stored.map(SemanticVectorStore.IndexMetadata::documentCount).orElse(0),
                    sizeBytes(projectId, withSize),
                    appendProviderLimitations(List.of("ACTIVE_KNOWLEDGE_SNAPSHOT_UNAVAILABLE"), provider));
        }
        if (stored.isEmpty()) {
            return new Status(projectId, project.displayName(), State.MISSING,
                    active.orElseThrow(), null, provider.id(), provider.modelId(),
                    provider.dimensions(), 0, 0L,
                    appendProviderLimitations(List.of("SEMANTIC_INDEX_MISSING"), provider));
        }
        SemanticVectorStore.IndexMetadata index = stored.orElseThrow();
        boolean modelAligned = sameModel(index, provider);
        boolean snapshotAligned = active.orElseThrow().equals(index.snapshotId());
        State state = modelAligned && snapshotAligned ? State.READY : State.STALE;
        List<String> limitations = new ArrayList<>();
        if (!snapshotAligned) limitations.add("SEMANTIC_INDEX_SNAPSHOT_STALE");
        if (!modelAligned) limitations.add("SEMANTIC_INDEX_MODEL_STALE");
        limitations.addAll(providerLimitations(provider));
        return new Status(projectId, project.displayName(), state,
                active.orElseThrow(), index.snapshotId(), provider.id(), provider.modelId(),
                provider.dimensions(), index.documentCount(), sizeBytes(projectId, withSize), limitations);
    }

    private UpdateReport synchronize(RegisteredProject project) throws IOException {
        String projectId = project.id().toString();
        if (embeddingProvider.isEmpty()) {
            return new UpdateReport(projectId, null, State.DISABLED, 0, 0, 0, 0, 0,
                    0L, sizeBytes(projectId), List.of("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"));
        }
        EmbeddingProvider provider = embeddingProvider.orElseThrow();
        long started = System.nanoTime();
        Optional<SemanticVectorStore.IndexMetadata> previousMetadata = store.metadata(projectId);
        if (previousMetadata.isPresent()) {
            SemanticVectorStore.IndexMetadata index = previousMetadata.orElseThrow();
            Optional<String> activeSnapshotId = snapshots.activeSnapshotId(project.id());
            if (activeSnapshotId.isPresent() && isCurrent(index, activeSnapshotId.orElseThrow(), provider)) {
                // The index already describes the active snapshot with this model: nothing is rebuilt or written.
                return new UpdateReport(projectId, index.snapshotId(), State.READY,
                        index.documentCount(), 0, 0, 0, index.documentCount(),
                        (System.nanoTime() - started) / 1_000_000L, sizeBytes(projectId), providerLimitations(provider));
            }
        }
        CodeKnowledgeSnapshot active = snapshots.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active knowledge snapshot: " + project.displayName()));
        List<SemanticDocument> currentDocuments = documents.build(
                project, active, SemanticIndexBudget.DEFAULT, provider.dimensions());
        if (currentDocuments.size() > MAX_DOCUMENTS) {
            throw new IllegalStateException("semantic document count exceeds bound: " + currentDocuments.size());
        }

        long currentWorkingSetBytes = estimateWorkingSet(currentDocuments, provider.dimensions());
        boolean reusableMetadata = previousMetadata.isPresent() && sameModel(previousMetadata.orElseThrow(), provider);
        long persistedBytes = previousMetadata.isPresent() ? sizeBytes(projectId) : 0L;
        boolean reuseFitsWorkingSet = reusableMetadata
                && saturatingAdd(currentWorkingSetBytes, saturatingMultiply(persistedBytes, PERSISTED_INDEX_AMPLIFICATION))
                    <= MAX_SYNCHRONIZE_WORKING_SET_BYTES;
        // Reuse is decided on stable keys and checksums alone: the stored vectors are neither read nor sent
        // back, the store keeps them for the documents marked as reused and refuses a reuse it cannot honour.
        Map<String, String> oldChecksums = reuseFitsWorkingSet
                ? store.checksums(projectId)
                    .filter(previous -> sameModel(previous.metadata(), provider))
                    .map(SemanticVectorStore.IndexChecksums::checksumsByStableKey)
                    .orElse(null)
                : null;
        boolean reusableModel = oldChecksums != null;

        int added = 0;
        int changed = 0;
        int reused = 0;
        List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>(currentDocuments.size());
        Set<String> currentKeys = new LinkedHashSet<>();
        for (SemanticDocument document : currentDocuments) {
            currentKeys.add(document.stableKey());
            String oldChecksum = reusableModel ? oldChecksums.get(document.stableKey()) : null;
            if (document.checksum().equals(oldChecksum)) {
                indexed.add(SemanticVectorStore.IndexedDocument.reusingStoredVector(document));
                reused++;
            } else {
                SemanticVector vector = provider.embed(document.stableKey(), document.content());
                validateVector(provider, document, vector);
                indexed.add(new SemanticVectorStore.IndexedDocument(document, vector));
                if (oldChecksum == null) added++; else changed++;
            }
        }
        int removed = reusableModel
                ? (int) oldChecksums.keySet().stream().filter(key -> !currentKeys.contains(key)).count()
                : previousMetadata.map(SemanticVectorStore.IndexMetadata::documentCount).orElse(0);
        if (!reusableModel && previousMetadata.isPresent()) {
            added = currentDocuments.size(); changed = 0; reused = 0;
        }

        indexed.sort(Comparator.comparing(value -> value.document().stableKey()));
        SemanticVectorStore.IndexSnapshot next = new SemanticVectorStore.IndexSnapshot(
                projectId, active.snapshotId(), provider.id(), provider.modelId(), provider.dimensions(),
                System.currentTimeMillis(), indexed);
        final String capturedSnapshotId = active.snapshotId();
        store.replaceConditionally(next, capturedSnapshotId,
                () -> snapshots.activeSnapshotId(project.id()));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        List<String> limitations = new ArrayList<>(providerLimitations(provider));
        if (reusableMetadata && !reuseFitsWorkingSet) {
            limitations.add("SEMANTIC_REUSE_SKIPPED_WORKING_SET_BUDGET");
        }
        return new UpdateReport(projectId, active.snapshotId(), State.READY,
                currentDocuments.size(), added, changed, removed, reused,
                elapsedMillis, sizeBytes(projectId), limitations);
    }

    private static boolean sameModel(SemanticVectorStore.IndexMetadata index, EmbeddingProvider provider) {
        return provider.id().equals(index.providerId())
                && provider.modelId().equals(index.modelId())
                && provider.dimensions() == index.dimensions();
    }

    private static boolean isCurrent(SemanticVectorStore.IndexMetadata index, String activeSnapshotId,
                                     EmbeddingProvider provider) {
        return activeSnapshotId.equals(index.snapshotId()) && sameModel(index, provider);
    }

    private long sizeBytes(String projectId) throws IOException { return store.sizeBytes(projectId); }

    private long sizeBytes(String projectId, boolean computed) throws IOException {
        return computed ? sizeBytes(projectId) : SIZE_NOT_COMPUTED;
    }

    private static long estimateWorkingSet(List<SemanticDocument> documents, int dimensions) throws IOException {
        long bytes = 64L * 1024L * 1024L;
        try {
            for (SemanticDocument document : documents) {
                bytes = Math.addExact(bytes, INDEX_OBJECT_OVERHEAD_BYTES);
                bytes = Math.addExact(bytes, document.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                bytes = Math.addExact(bytes, Math.multiplyExact((long) dimensions, Double.BYTES));
            }
        } catch (ArithmeticException exception) {
            throw new IOException("semantic synchronize working-set estimate overflow", exception);
        }
        if (bytes > MAX_SYNCHRONIZE_WORKING_SET_BYTES) {
            throw new IOException("semantic synchronize working set exceeds safety budget: " + bytes
                    + "/" + MAX_SYNCHRONIZE_WORKING_SET_BYTES);
        }
        return bytes;
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) { return Long.MAX_VALUE; }
    }

    private static List<String> appendProviderLimitations(List<String> base, EmbeddingProvider provider) {
        List<String> result = new ArrayList<>(base); result.addAll(providerLimitations(provider)); return List.copyOf(result);
    }

    private static List<String> providerLimitations(EmbeddingProvider provider) {
        return List.copyOf(Objects.requireNonNull(provider.limitations(), "embedding provider limitations"));
    }

    private static void validateProvider(EmbeddingProvider provider) {
        Objects.requireNonNull(provider, "embeddingProvider");
        if (provider.id() == null || provider.id().isBlank()) throw new IllegalArgumentException("embedding provider id must not be blank");
        if (provider.modelId() == null || provider.modelId().isBlank()) throw new IllegalArgumentException("embedding model id must not be blank");
        if (provider.dimensions() < 1 || provider.dimensions() > 16_384) throw new IllegalArgumentException("embedding dimensions out of range");
    }

    private static void validateVector(EmbeddingProvider provider, SemanticDocument document, SemanticVector vector) {
        Objects.requireNonNull(vector, "embedding provider returned null vector");
        if (!document.stableKey().equals(vector.stableKey())) throw new IllegalStateException("embedding provider changed stableKey");
        if (vector.dimensions() != provider.dimensions()) throw new IllegalStateException("embedding provider returned unexpected dimensions");
    }

    public enum State { DISABLED, NO_ACTIVE_SNAPSHOT, MISSING, STALE, READY }

    public record Status(String projectId, String projectName, State state, String activeSnapshotId,
                         String indexedSnapshotId, String providerId, String modelId, int dimensions,
                         int documentCount, long indexSizeBytes, List<String> limitations) {
        public Status { limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations")); }
    }

    public record UpdateReport(String projectId, String snapshotId, State state, int documentCount,
                               int embeddedAdded, int embeddedChanged, int removed, int reused,
                               long rebuildMillis, long indexSizeBytes, List<String> limitations) {
        public UpdateReport { limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations")); }
        public int embeddedCount() { return embeddedAdded + embeddedChanged; }
    }
}

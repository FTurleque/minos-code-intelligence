package com.minos.semantic;

import com.minos.application.ProjectResolver;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSemanticVectorStore;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Maintains an optional reconstructible semantic index aligned with the active MINOS snapshot. */
public final class SemanticIndexService {

    public static final int MAX_DOCUMENTS = 250_000;

    private final ProjectResolver projects;
    private final FileSymbolSnapshotStore snapshots;
    private final SemanticVectorStore store;
    private final Optional<EmbeddingProvider> embeddingProvider;
    private final SemanticDocumentFactory documents;

    public SemanticIndexService(
            ProjectResolver projects,
            FileSymbolSnapshotStore snapshots,
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

    public Optional<EmbeddingProvider> embeddingProvider() {
        return embeddingProvider;
    }

    public Status status(String projectReference) throws IOException {
        return status(projects.resolve(projectReference));
    }

    public Status status(UUID projectId) throws IOException {
        return status(projects.resolveById(projectId));
    }

    public UpdateReport synchronize(String projectReference) throws IOException {
        return synchronize(projects.resolve(projectReference));
    }

    public UpdateReport synchronize(UUID projectId) throws IOException {
        return synchronize(projects.resolveById(projectId));
    }

    public Optional<SemanticVectorStore.IndexSnapshot> activeIndex(String projectReference) throws IOException {
        RegisteredProject project = projects.resolve(projectReference);
        Status status = status(project);
        if (status.state() != State.READY) return Optional.empty();
        return store.load(project.id().toString());
    }

    private Status status(RegisteredProject project) throws IOException {
        String projectId = project.id().toString();
        Optional<CodeKnowledgeSnapshot> active = snapshots.loadActiveKnowledge(project.id());
        Optional<SemanticVectorStore.IndexSnapshot> stored = store.load(projectId);
        if (embeddingProvider.isEmpty()) {
            return new Status(projectId, project.displayName(), State.DISABLED,
                    active.map(CodeKnowledgeSnapshot::snapshotId).orElse(null),
                    stored.map(SemanticVectorStore.IndexSnapshot::snapshotId).orElse(null),
                    null, null, 0, stored.map(value -> value.documents().size()).orElse(0),
                    sizeBytes(projectId), List.of("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"));
        }
        EmbeddingProvider provider = embeddingProvider.orElseThrow();
        if (active.isEmpty()) {
            return new Status(projectId, project.displayName(), State.NO_ACTIVE_SNAPSHOT,
                    null, stored.map(SemanticVectorStore.IndexSnapshot::snapshotId).orElse(null),
                    provider.id(), provider.modelId(), provider.dimensions(),
                    stored.map(value -> value.documents().size()).orElse(0), sizeBytes(projectId),
                    List.of("ACTIVE_KNOWLEDGE_SNAPSHOT_UNAVAILABLE"));
        }
        if (stored.isEmpty()) {
            return new Status(projectId, project.displayName(), State.MISSING,
                    active.orElseThrow().snapshotId(), null, provider.id(), provider.modelId(),
                    provider.dimensions(), 0, 0L, List.of("SEMANTIC_INDEX_MISSING"));
        }
        SemanticVectorStore.IndexSnapshot index = stored.orElseThrow();
        boolean modelAligned = provider.id().equals(index.providerId())
                && provider.modelId().equals(index.modelId())
                && provider.dimensions() == index.dimensions();
        boolean snapshotAligned = active.orElseThrow().snapshotId().equals(index.snapshotId());
        State state = modelAligned && snapshotAligned ? State.READY : State.STALE;
        List<String> limitations = new ArrayList<>();
        if (!snapshotAligned) limitations.add("SEMANTIC_INDEX_SNAPSHOT_STALE");
        if (!modelAligned) limitations.add("SEMANTIC_INDEX_MODEL_STALE");
        if (provider instanceof LocalHashEmbeddingProvider) limitations.add("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL");
        return new Status(projectId, project.displayName(), state,
                active.orElseThrow().snapshotId(), index.snapshotId(), provider.id(), provider.modelId(),
                provider.dimensions(), index.documents().size(), sizeBytes(projectId), limitations);
    }

    private UpdateReport synchronize(RegisteredProject project) throws IOException {
        String projectId = project.id().toString();
        if (embeddingProvider.isEmpty()) {
            return new UpdateReport(projectId, null, State.DISABLED, 0, 0, 0, 0, 0,
                    0L, sizeBytes(projectId), List.of("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"));
        }
        EmbeddingProvider provider = embeddingProvider.orElseThrow();
        CodeKnowledgeSnapshot active = snapshots.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active knowledge snapshot: " + project.displayName()));
        long started = System.nanoTime();
        List<SemanticDocument> currentDocuments = documents.build(project, active);
        if (currentDocuments.size() > MAX_DOCUMENTS) {
            throw new IllegalStateException("semantic document count exceeds M20 bound: " + currentDocuments.size());
        }

        Optional<SemanticVectorStore.IndexSnapshot> previousOptional = store.load(projectId);
        SemanticVectorStore.IndexSnapshot previous = previousOptional.orElse(null);
        boolean reusableModel = previous != null
                && provider.id().equals(previous.providerId())
                && provider.modelId().equals(previous.modelId())
                && provider.dimensions() == previous.dimensions();
        Map<String, SemanticVectorStore.IndexedDocument> oldByStableKey = new HashMap<>();
        if (reusableModel) {
            for (SemanticVectorStore.IndexedDocument value : previous.documents()) {
                oldByStableKey.put(value.document().stableKey(), value);
            }
        }

        int added = 0;
        int changed = 0;
        int reused = 0;
        List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>(currentDocuments.size());
        Set<String> currentKeys = new LinkedHashSet<>();
        for (SemanticDocument document : currentDocuments) {
            currentKeys.add(document.stableKey());
            SemanticVectorStore.IndexedDocument old = oldByStableKey.get(document.stableKey());
            SemanticVector vector;
            if (old != null && old.document().checksum().equals(document.checksum())) {
                vector = old.vector();
                reused++;
            } else {
                vector = provider.embed(document.stableKey(), document.content());
                validateVector(provider, document, vector);
                if (old == null) added++; else changed++;
            }
            indexed.add(new SemanticVectorStore.IndexedDocument(document, vector));
        }
        int removed = reusableModel
                ? (int) oldByStableKey.keySet().stream().filter(key -> !currentKeys.contains(key)).count()
                : previous == null ? 0 : previous.documents().size();
        if (!reusableModel && previous != null) {
            added = currentDocuments.size();
            changed = 0;
            reused = 0;
        }

        indexed.sort(Comparator.comparing(value -> value.document().stableKey()));
        SemanticVectorStore.IndexSnapshot next = new SemanticVectorStore.IndexSnapshot(
                projectId, active.snapshotId(), provider.id(), provider.modelId(), provider.dimensions(),
                System.currentTimeMillis(), indexed);
        store.replace(next);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        List<String> limitations = provider instanceof LocalHashEmbeddingProvider
                ? List.of("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL") : List.of();
        return new UpdateReport(projectId, active.snapshotId(), State.READY,
                currentDocuments.size(), added, changed, removed, reused,
                elapsedMillis, sizeBytes(projectId), limitations);
    }

    private long sizeBytes(String projectId) throws IOException {
        if (store instanceof FileSemanticVectorStore fileStore) return fileStore.sizeBytes(projectId);
        Optional<SemanticVectorStore.IndexSnapshot> value = store.load(projectId);
        if (value.isEmpty()) return 0L;
        long bytes = 0L;
        for (SemanticVectorStore.IndexedDocument document : value.orElseThrow().documents()) {
            bytes += document.document().content().getBytes(StandardCharsets.UTF_8).length;
            bytes += (long) document.vector().dimensions() * Double.BYTES;
        }
        return bytes;
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

    public enum State {
        DISABLED,
        NO_ACTIVE_SNAPSHOT,
        MISSING,
        STALE,
        READY
    }

    public record Status(
            String projectId,
            String projectName,
            State state,
            String activeSnapshotId,
            String indexedSnapshotId,
            String providerId,
            String modelId,
            int dimensions,
            int documentCount,
            long indexSizeBytes,
            List<String> limitations
    ) {
        public Status {
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }

    public record UpdateReport(
            String projectId,
            String snapshotId,
            State state,
            int documentCount,
            int embeddedAdded,
            int embeddedChanged,
            int removed,
            int reused,
            long rebuildMillis,
            long indexSizeBytes,
            List<String> limitations
    ) {
        public UpdateReport {
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }

        public int embeddedCount() {
            return embeddedAdded + embeddedChanged;
        }
    }
}

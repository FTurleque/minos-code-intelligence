package com.minos.store;

import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates semantic mutations with the structural snapshot mutation lease for the same project.
 *
 * <p>The semantic implementation still owns its private writer lock; this decorator adds the
 * storage-family lease shared with {@link FileSymbolSnapshotStore}. Holding it across the active
 * snapshot recheck and semantic write closes the N -> N+1 TOCTOU window.</p>
 */
public final class ProjectMutationSemanticVectorStore implements SemanticVectorStore {
    private final Path storageRoot;
    private final SemanticVectorStore delegate;

    public ProjectMutationSemanticVectorStore(Path storageRoot, SemanticVectorStore delegate) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<IndexSnapshot> load(String projectId) throws IOException {
        return delegate.load(projectId);
    }

    @Override
    public Optional<IndexMetadata> metadata(String projectId) throws IOException {
        return delegate.metadata(projectId);
    }

    @Override
    public String searchEngine() {
        return delegate.searchEngine();
    }

    @Override
    public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore)
            throws IOException {
        return delegate.search(projectId, query, limit, minimumScore);
    }

    @Override
    public long sizeBytes(String projectId) throws IOException {
        return delegate.sizeBytes(projectId);
    }

    @Override
    public void replace(IndexSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, snapshot.projectId())) {
            delegate.replace(snapshot);
        }
    }

    @Override
    public void replaceConditionally(
            IndexSnapshot next,
            String expectedActiveSnapshotId,
            ActiveSnapshotIdReader activeSnapshotReader
    ) throws IOException {
        Objects.requireNonNull(next, "next");
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, next.projectId())) {
            delegate.replaceConditionally(next, expectedActiveSnapshotId, activeSnapshotReader);
        }
    }

    @Override
    public void delete(String projectId) throws IOException {
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, projectId)) {
            delegate.delete(projectId);
        }
    }
}

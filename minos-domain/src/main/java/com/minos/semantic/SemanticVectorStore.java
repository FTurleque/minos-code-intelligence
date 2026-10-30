package com.minos.semantic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

import static com.minos.domain.Preconditions.requireText;

/** Reconstructible semantic vector index abstraction. Snapshots remain authoritative. */
public interface SemanticVectorStore {

    /**
     * Reads the currently-active structural snapshot ID for the project, evaluated inside a
     * commit boundary. Implementations must be safe to invoke while holding a cross-process lock.
     */
    @FunctionalInterface
    interface ActiveSnapshotIdReader {
        Optional<String> read() throws IOException;
    }

    Optional<IndexSnapshot> load(String projectId) throws IOException;
    void replace(IndexSnapshot snapshot) throws IOException;
    void delete(String projectId) throws IOException;

    /**
     * Atomically replaces the semantic index for {@code next.projectId()} provided that
     * {@code activeSnapshotReader.read()} still returns {@code expectedActiveSnapshotId}
     * when evaluated inside the commit boundary.
     *
     * <p>Implementations must ensure that the re-check and the write share the same
     * cross-JVM per-project exclusive boundary (file lock, advisory lock, …) so that
     * no TOCTOU window exists between steps 4 and 5 of the build protocol.
     *
     * @throws StaleSemanticSyncException if the active snapshot changed during the build phase.
     */
    void replaceConditionally(IndexSnapshot next,
                               String expectedActiveSnapshotId,
                               ActiveSnapshotIdReader activeSnapshotReader) throws IOException;

    /**
     * Loads only index metadata when the backend can do so cheaply. The default preserves
     * compatibility for simple/local stores by deriving metadata from the full snapshot.
     */
    default Optional<IndexMetadata> metadata(String projectId) throws IOException {
        return load(projectId).map(IndexMetadata::from);
    }

    /**
     * Documents of the index without their vectors, with the metadata of the index they were read from.
     * The default derives both from the full snapshot; a backend that stores vectors apart from the
     * documents reads the documents only.
     */
    default Optional<IndexDocuments> documents(String projectId) throws IOException {
        return load(projectId).map(IndexDocuments::from);
    }

    /**
     * Stable key and checksum of every document of the index, with the metadata of that index: enough to
     * decide which documents can keep their stored vector. The default derives them from the full snapshot;
     * a backend that stores vectors apart from the documents reads neither the vectors nor the contents.
     */
    default Optional<IndexChecksums> checksums(String projectId) throws IOException {
        return load(projectId).map(IndexChecksums::from);
    }

    /** Stable diagnostic identifier for the vector ranking engine. */
    default String searchEngine() { return "exact-linear"; }

    /**
     * Executes vector ranking for one already-embedded query. The local backend keeps the
     * historical exact cosine scan; database backends may push ranking into their vector engine.
     */
    default List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore) throws IOException {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        if (!Double.isFinite(minimumScore) || minimumScore < -1.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("minimumScore must be between -1 and 1");
        }
        Optional<IndexSnapshot> loaded = load(projectId);
        if (loaded.isEmpty()) return List.of();
        IndexSnapshot index = loaded.orElseThrow();
        if (query.dimensions() != index.dimensions()) throw new IllegalArgumentException("vector dimensions mismatch");

        Comparator<VectorHit> bestFirst = Comparator.comparingDouble(VectorHit::score).reversed()
                .thenComparing(hit -> hit.document().stableKey());
        Comparator<VectorHit> worstFirst = bestFirst.reversed();
        PriorityQueue<VectorHit> top = new PriorityQueue<>(limit, worstFirst);
        for (IndexedDocument value : index.documents()) {
            double denominator = query.norm() * value.vector().norm();
            double score = 0.0;
            if (denominator != 0.0) {
                double dot = 0.0;
                for (int i = 0; i < query.dimensions(); i++) dot += query.valueAt(i) * value.vector().valueAt(i);
                score = Math.max(-1.0, Math.min(1.0, dot / denominator));
            }
            if (score < minimumScore) continue;
            VectorHit candidate = new VectorHit(value.document(), score);
            if (top.size() < limit) top.add(candidate);
            else if (bestFirst.compare(candidate, top.peek()) < 0) {
                top.poll(); top.add(candidate);
            }
        }
        List<VectorHit> hits = new ArrayList<>(top);
        hits.sort(bestFirst);
        return List.copyOf(hits);
    }

    default long sizeBytes(String projectId) throws IOException {
        Optional<IndexSnapshot> value = load(projectId);
        if (value.isEmpty()) return 0L;
        long bytes = 0L;
        for (IndexedDocument document : value.orElseThrow().documents()) {
            bytes += document.document().content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            bytes += (long) document.vector().dimensions() * Float.BYTES;
        }
        return bytes;
    }

    record IndexMetadata(
            String projectId,
            String snapshotId,
            String providerId,
            String modelId,
            int dimensions,
            long builtAtEpochMilli,
            int documentCount
    ) {
        public IndexMetadata {
            requireText(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId");
            requireText(modelId, "modelId");
            if (dimensions < 1) throw new IllegalArgumentException("dimensions must be greater than zero");
            if (builtAtEpochMilli < 0L) throw new IllegalArgumentException("builtAtEpochMilli must not be negative");
            if (documentCount < 0) throw new IllegalArgumentException("documentCount must not be negative");
        }

        static IndexMetadata from(IndexSnapshot snapshot) {
            return new IndexMetadata(
                    snapshot.projectId(), snapshot.snapshotId(), snapshot.providerId(), snapshot.modelId(),
                    snapshot.dimensions(), snapshot.builtAtEpochMilli(), snapshot.documents().size());
        }
    }

    /** The documents of one index, in stable-key order, and the metadata of that same index. */
    record IndexDocuments(IndexMetadata metadata, List<SemanticDocument> documents) {
        public IndexDocuments {
            Objects.requireNonNull(metadata, "metadata");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        }

        static IndexDocuments from(IndexSnapshot snapshot) {
            return new IndexDocuments(IndexMetadata.from(snapshot),
                    snapshot.documents().stream().map(IndexedDocument::document).toList());
        }
    }

    /** The checksum of each document of one index, by stable key, and the metadata of that same index. */
    record IndexChecksums(IndexMetadata metadata, Map<String, String> checksumsByStableKey) {
        public IndexChecksums {
            Objects.requireNonNull(metadata, "metadata");
            checksumsByStableKey = Map.copyOf(Objects.requireNonNull(checksumsByStableKey, "checksumsByStableKey"));
        }

        static IndexChecksums from(IndexSnapshot snapshot) {
            Map<String, String> checksums = new LinkedHashMap<>();
            for (IndexedDocument value : snapshot.documents()) {
                checksums.put(value.document().stableKey(), value.document().checksum());
            }
            return new IndexChecksums(IndexMetadata.from(snapshot), checksums);
        }
    }

    record VectorHit(SemanticDocument document, double score) {
        public VectorHit {
            Objects.requireNonNull(document, "document");
            if (!Double.isFinite(score) || score < -1.0 || score > 1.0) throw new IllegalArgumentException("score must be finite and between -1 and 1");
        }
    }

    /**
     * One document of an index to write and its vector. A document whose content did not change may be
     * written {@linkplain #reusingStoredVector(SemanticDocument) without its vector}: the store then keeps the
     * vector it holds for the same stable key and checksum, and fails if it holds none. A document without a
     * vector that is not marked so is refused: no row is ever written with a missing vector.
     */
    record IndexedDocument(SemanticDocument document, SemanticVector vector, boolean reusesStoredVector) {
        public IndexedDocument {
            Objects.requireNonNull(document, "document");
            if (reusesStoredVector) {
                if (vector != null) throw new IllegalArgumentException("a document reusing its stored vector carries no vector");
            } else {
                Objects.requireNonNull(vector, "vector");
                if (!document.stableKey().equals(vector.stableKey())) throw new IllegalArgumentException("document/vector stableKey mismatch");
            }
        }

        /** A document written with its vector. */
        public IndexedDocument(SemanticDocument document, SemanticVector vector) {
            this(document, vector, false);
        }

        /** A document whose stored vector, for the same stable key and checksum, is kept by the store. */
        public static IndexedDocument reusingStoredVector(SemanticDocument document) {
            return new IndexedDocument(document, null, true);
        }
    }

    record IndexSnapshot(String projectId, String snapshotId, String providerId, String modelId,
                         int dimensions, long builtAtEpochMilli, List<IndexedDocument> documents) {
        public IndexSnapshot {
            requireText(projectId, "projectId"); requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId"); requireText(modelId, "modelId");
            if (dimensions < 1) throw new IllegalArgumentException("dimensions must be greater than zero");
            if (builtAtEpochMilli < 0) throw new IllegalArgumentException("builtAtEpochMilli must not be negative");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            for (IndexedDocument value : documents) {
                if (!projectId.equals(value.document().projectId())) throw new IllegalArgumentException("semantic document belongs to another project");
                if (!snapshotId.equals(value.document().snapshotId())) throw new IllegalArgumentException("semantic document belongs to another snapshot");
                if (!value.reusesStoredVector() && value.vector().dimensions() != dimensions) {
                    throw new IllegalArgumentException("semantic vector dimensions mismatch");
                }
            }
        }

        /** Whether at least one document keeps the vector the store holds for it. */
        public boolean reusesStoredVectors() {
            for (IndexedDocument value : documents) {
                if (value.reusesStoredVector()) return true;
            }
            return false;
        }

        /**
         * This index with the vector of every reused document taken from {@code stored}, the index the store
         * holds. Fails, without returning a partial index, when a reused document has no stored counterpart
         * with the same stable key and checksum, or when {@code stored} was built by another provider, model
         * or number of dimensions.
         */
        public IndexSnapshot withStoredVectors(Optional<IndexSnapshot> stored) throws IOException {
            if (!reusesStoredVectors()) return this;
            IndexSnapshot previous = stored.orElseThrow(() -> new IOException(
                    "semantic documents reuse stored vectors but the index holds none"));
            if (!providerId.equals(previous.providerId()) || !modelId.equals(previous.modelId())
                    || dimensions != previous.dimensions()) {
                throw new IOException("semantic documents reuse vectors of another embedding model");
            }
            Map<String, IndexedDocument> byStableKey = new HashMap<>();
            for (IndexedDocument value : previous.documents()) byStableKey.put(value.document().stableKey(), value);
            List<IndexedDocument> resolved = new ArrayList<>(documents.size());
            for (IndexedDocument value : documents) {
                if (!value.reusesStoredVector()) {
                    resolved.add(value);
                    continue;
                }
                IndexedDocument kept = byStableKey.get(value.document().stableKey());
                if (kept == null || !kept.document().checksum().equals(value.document().checksum())) {
                    throw new IOException("semantic document reuses a stored vector that does not exist: "
                            + value.document().stableKey());
                }
                resolved.add(new IndexedDocument(value.document(), kept.vector()));
            }
            return new IndexSnapshot(projectId, snapshotId, providerId, modelId, dimensions, builtAtEpochMilli, resolved);
        }
    }
}

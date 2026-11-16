package com.minos.application.semantic;

import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The memory accounting of a preparation: the question of capacity, and the largest index the product builds. */
class ProjectionPreparationTest {

    @Test
    void askingWhetherBytesFitChangesNeitherTheMemoryHeldNorThePeak() {
        ProjectionPreparation.WorkingMemory memory = new ProjectionPreparation.WorkingMemory(1_000L);
        memory.reserve(400L);

        assertFalse(memory.fits(700L));
        assertTrue(memory.fits(600L));
        assertFalse(memory.fits(601L));
        assertFalse(memory.fits(-1L));
        assertEquals(400L, memory.peak(), "a question reserves nothing");
        memory.reserve(600L);
        assertEquals(1_000L, memory.peak());
        assertThrows(SemanticProjectionService.RefusedException.class, () -> memory.reserve(1L));
    }

    /**
     * The largest index the synchronization builds (SemanticIndexBudget: 192 MiB of vectors counted at eight
     * bytes per dimension, so 32 768 documents of 768 dimensions) with the default memory budget: the sample
     * fits, so the preparation iterates in memory and walks the index twice. The vectors are all equal, which
     * ends the fitting after one iteration without changing what is reserved.
     */
    @Test
    void theLargestIndexTheProductBuildsIsIteratedInMemoryUnderTheDefaultBudget() throws Exception {
        int documents = 32_768;
        int dimensions = 768;
        UniformStore store = new UniformStore(documents, dimensions);
        SemanticVectorStore.IndexMetadata metadata = store.metadata("project").orElseThrow();
        SemanticProjectionService.PreparationRequest request = new SemanticProjectionService.PreparationRequest(
                documents, 42L, Set.of(SemanticDocumentKind.CHUNK),
                SemanticProjectionService.DEFAULT_MAX_MEMORY_MEGABYTES * 1024L * 1024L);

        ProjectionPreparation.Outcome outcome = new ProjectionPreparation(store, metadata, request,
                SemanticProjectionService.Progress.NONE).run();

        assertEquals(SemanticProjectionService.Strategy.IN_MEMORY, outcome.strategy());
        assertEquals(2, outcome.passes());
        assertEquals(documents, outcome.points().size());
        assertTrue(outcome.peakWorkingBytes() <= request.maxMemoryBytes(), "peak " + outcome.peakWorkingBytes());
        assertTrue(outcome.peakWorkingBytes() >= (long) documents * dimensions * Float.BYTES,
                "the matrix of the sample is counted");
    }

    /** An index of equal vectors, generated page by page, that holds nothing of its documents. */
    private static final class UniformStore implements SemanticVectorStore {
        private final int documents;
        private final int dimensions;
        private final double[] values;

        UniformStore(int documents, int dimensions) {
            this.documents = documents;
            this.dimensions = dimensions;
            this.values = new double[dimensions];
            java.util.Arrays.fill(values, 0.5);
        }

        private static String key(int number) {
            return String.format(Locale.ROOT, "chunk:src/main/java/com/minos/module/File%06d.java:%d", number, number);
        }

        @Override
        public Optional<IndexMetadata> metadata(String projectId) {
            return Optional.of(new IndexMetadata(projectId, "snapshot", "provider", "model", dimensions, 1L, documents));
        }

        @Override
        public Optional<VectorPage> vectorPage(String projectId, String afterStableKey, int limit) {
            SemanticVectorStore.requireVectorPageBounds(afterStableKey, limit);
            int first = afterStableKey == null ? 0 : numberAfter(afterStableKey);
            List<StoredVector> page = new ArrayList<>(limit);
            int number = first;
            while (number < documents && page.size() < limit) {
                String key = key(number++);
                page.add(new StoredVector(SemanticDocumentKind.CHUNK, SemanticVector.fromArray(key, values)));
            }
            return Optional.of(new VectorPage(metadata(projectId).orElseThrow(), page, number < documents));
        }

        /** Keys are zero-padded and sorted: the number after {@code key} is read from the key itself. */
        private static int numberAfter(String key) {
            int start = key.indexOf("File") + 4;
            return Integer.parseInt(key.substring(start, start + 6)) + 1;
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            throw new AssertionError("the whole index must not be loaded");
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) {
            throw new UnsupportedOperationException();
        }
    }
}

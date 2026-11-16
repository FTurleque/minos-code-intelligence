package com.minos.application.semantic;

import com.minos.application.semantic.SemanticProjectionService.PreparationRequest;
import com.minos.application.semantic.SemanticProjectionService.Progress;
import com.minos.application.semantic.SemanticProjectionService.Refusal;
import com.minos.application.semantic.SemanticProjectionService.RefusedException;
import com.minos.application.semantic.SemanticProjectionService.Strategy;
import com.minos.io.Sha256;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * One computation of a projection: a reproducible sample of the documents of the selected kinds, then the two
 * principal axes of their vectors. The selection is one walk of the index by pages. When the vectors of the sample
 * fit in what is left of the memory budget, a second walk copies them and every later step reads that copy;
 * otherwise each later step is one more walk of the index and the sample is never held. The memory held grows with
 * the number of points and with the size of one vector, and is counted against the memory budget of the request.
 *
 * <p>Both strategies compute on the same value of every component, the vector component rounded to {@code float},
 * and visit the vectors of the sample in the order the index presents them: they publish the same projection.</p>
 */
final class ProjectionPreparation {

    private static final String SEPARATOR = "\u001f";
    /** Estimated bytes of one selected document besides its stable key: rank, references and coordinates. */
    private static final long POINT_OVERHEAD_BYTES = 256L;
    private static final long VECTOR_OVERHEAD_BYTES = 160L;
    /** Estimated bytes of one entry of the table that finds a selected document by its stable key. */
    private static final long POSITION_ENTRY_BYTES = 64L;
    /** The largest number of components of the matrix of a sample: one Java array. */
    private static final long MAX_MATRIX_COMPONENTS = Integer.MAX_VALUE - 8L;

    private final SemanticVectorStore vectors;
    private final SemanticVectorStore.IndexMetadata metadata;
    private final PreparationRequest request;
    private final Progress progress;
    private final WorkingMemory memory;
    private final int pageSize;
    private int passes;

    ProjectionPreparation(SemanticVectorStore vectors, SemanticVectorStore.IndexMetadata metadata,
                          PreparationRequest request, Progress progress) {
        this.vectors = vectors;
        this.metadata = metadata;
        this.request = request;
        this.progress = progress;
        this.memory = new WorkingMemory(request.maxMemoryBytes());
        long vectorBytes = 2L * metadata.dimensions() * Double.BYTES + VECTOR_OVERHEAD_BYTES;
        this.pageSize = (int) Math.max(1L, Math.min(SemanticVectorStore.MAX_VECTOR_PAGE_SIZE,
                request.maxMemoryBytes() / 4L / vectorBytes));
        memory.reserve(pageSize * vectorBytes);
        memory.reserve(PrincipalAxes.workingBytes(metadata.dimensions()));
        memory.reserve((long) metadata.dimensions() * Float.BYTES);
    }

    /**
     * What the computation found; the points are in stable-key order. {@code passes} is the number of walks of
     * the index, {@code iterations} the number of fitting iterations of the axes.
     */
    record Outcome(List<SemanticProjection.Point> points, int candidateCount, List<Double> explainedVariance,
                   int passes, long peakWorkingBytes, Strategy strategy, int iterations) {
    }

    Outcome run() throws IOException {
        Sample sample = select();
        if (sample.keys().length == 0) {
            throw new RefusedException(Refusal.NO_DOCUMENT_OF_THE_SELECTED_KINDS,
                    "the index holds no document of the selected kinds; choose other kinds with --kinds");
        }
        int points = sample.keys().length;
        PrincipalAxes axes = new PrincipalAxes(metadata.dimensions(), request.seed());
        Rows copied = copyIfItFits(sample);
        Strategy strategy = copied == null ? Strategy.STREAMED : Strategy.IN_MEMORY;
        Rows rows = copied == null ? new StreamedRows(sample) : copied;
        rows.each("center", (position, values, offset) -> axes.addToMean(values, offset));
        axes.endMean();
        boolean still = false;
        int iterations = 0;
        while (iterations < SemanticProjectionService.MAX_ITERATIONS && !still) {
            rows.each("fit", (position, values, offset) -> axes.addToIteration(values, offset));
            still = axes.endIteration();
            iterations++;
        }
        double[] coordinates = new double[2 * points];
        rows.each("project", (position, values, offset) -> axes.project(values, offset, coordinates, position));
        double[] variance = axes.finish(coordinates, points);
        List<SemanticProjection.Point> projected = new ArrayList<>(points);
        for (int position = 0; position < points; position++) {
            projected.add(new SemanticProjection.Point(sample.keys()[position], sample.kinds()[position],
                    (float) coordinates[2 * position], (float) coordinates[2 * position + 1]));
        }
        return new Outcome(projected, sample.candidateCount(), List.of(variance[0], variance[1]), passes,
                memory.peak(), strategy, iterations);
    }

    /**
     * Keeps, among the documents of the selected kinds, the {@code maxPoints} whose rank is the lowest. The rank
     * of a document is the SHA-256 of the seed and of its stable key: it depends on nothing else, so the same
     * seed draws the same sample of the same index.
     */
    private Sample select() throws IOException {
        Comparator<Candidate> byRank = Comparator.comparing(Candidate::rank).thenComparing(Candidate::stableKey);
        PriorityQueue<Candidate> kept = new PriorityQueue<>(byRank.reversed());
        int[] candidates = {0};
        String seed = Long.toString(request.seed());
        walk("select", vector -> {
            if (!request.kinds().contains(vector.kind())) return;
            candidates[0]++;
            Candidate candidate = new Candidate(Sha256.hex(seed + SEPARATOR + vector.stableKey()),
                    vector.stableKey(), vector.kind());
            if (kept.size() < request.maxPoints()) {
                memory.reserve(candidate.bytes());
                kept.add(candidate);
            } else if (byRank.compare(candidate, kept.peek()) < 0) {
                memory.release(kept.poll().bytes());
                memory.reserve(candidate.bytes());
                kept.add(candidate);
            }
        });
        List<Candidate> ordered = new ArrayList<>(kept);
        ordered.sort(Comparator.comparing(Candidate::stableKey));
        String[] keys = new String[ordered.size()];
        SemanticDocumentKind[] kinds = new SemanticDocumentKind[ordered.size()];
        memory.reserve(POSITION_ENTRY_BYTES * keys.length);
        Map<String, Integer> positions = new HashMap<>(Math.max(16, keys.length * 2));
        for (int position = 0; position < keys.length; position++) {
            keys[position] = ordered.get(position).stableKey();
            kinds[position] = ordered.get(position).kind();
            positions.put(keys[position], position);
        }
        return new Sample(keys, kinds, positions, candidates[0]);
    }

    /**
     * Copies the vectors of the sample into one matrix of {@code float}, in the order the index presents them,
     * when the matrix and the position of each row fit in what is left of the budget: the question is asked
     * without effect, so that a sample that does not fit leaves the memory held and the peak as they were. One
     * walk of the index; the copy is refused if the index changed under it.
     *
     * @return the rows, or {@code null} when the sample does not fit and the steps must walk the index
     */
    private Rows copyIfItFits(Sample sample) throws IOException {
        int points = sample.keys().length;
        int dimensions = metadata.dimensions();
        long components = (long) points * dimensions;
        long bytes = components * Float.BYTES + (long) points * Integer.BYTES;
        if (components > MAX_MATRIX_COMPONENTS || !memory.fits(bytes)) return null;
        memory.reserve(bytes);
        float[] matrix = new float[(int) components];
        int[] positions = new int[points];
        int[] filled = {0};
        BitSet visited = new BitSet(points);
        walk("copy", vector -> {
            Integer position = sample.positions().get(vector.stableKey());
            if (position == null) return;
            if (visited.get(position)) throw indexChanged();
            visited.set(position);
            int row = filled[0]++;
            positions[row] = position;
            for (int index = 0; index < dimensions; index++) {
                matrix[row * dimensions + index] = (float) vector.vector().valueAt(index);
            }
        });
        if (visited.cardinality() != points) throw indexChanged();
        return new CopiedRows(matrix, positions, dimensions);
    }

    /** One pass over the stored vectors, by pages, all of them read from the index the preparation started on. */
    private void walk(String phase, VectorVisitor visitor) throws IOException {
        passes++;
        String after = null;
        long read = 0L;
        boolean more = true;
        while (more) {
            Optional<SemanticVectorStore.VectorPage> loaded = vectors.vectorPage(metadata.projectId(), after, pageSize);
            if (loaded.isEmpty() || !metadata.equals(loaded.orElseThrow().metadata())) throw indexChanged();
            SemanticVectorStore.VectorPage page = loaded.orElseThrow();
            for (SemanticVectorStore.StoredVector vector : page.vectors()) visitor.visit(vector);
            read += page.vectors().size();
            progress.advanced(phase, passes, read);
            more = page.hasMore() && !page.vectors().isEmpty();
            if (more) after = page.vectors().getLast().stableKey();
        }
    }

    static RefusedException indexChanged() {
        return new RefusedException(Refusal.INDEX_CHANGED_DURING_PREPARATION,
                "the semantic index changed while its projection was prepared; nothing was published, prepare again");
    }

    /** The vectors of the sample, one step at a time, each as the components of one row. */
    private interface Rows {
        void each(String phase, RowVisitor visitor) throws IOException;
    }

    @FunctionalInterface
    private interface RowVisitor {
        void visit(int position, float[] values, int offset);
    }

    /** Rows read again from the index at every step: one walk of the index each. */
    private final class StreamedRows implements Rows {
        private final Sample sample;
        private final float[] row = new float[metadata.dimensions()];

        StreamedRows(Sample sample) {
            this.sample = sample;
        }

        @Override
        public void each(String phase, RowVisitor visitor) throws IOException {
            walkSample(phase, (position, vector) -> {
                for (int index = 0; index < row.length; index++) row[index] = (float) vector.valueAt(index);
                visitor.visit(position, row, 0);
            });
        }

        /**
         * Walks the index and gives the visitor the vectors of the sample, each with its position in the sample.
         * A document is found by its stable key, not by its rank in the walk: the order in which a store returns
         * its pages is its own (a database sorts text by its collation), and need not be the order of the sample.
         */
        private void walkSample(String phase, SampleVisitor visitor) throws IOException {
            BitSet visited = new BitSet(sample.keys().length);
            walk(phase, vector -> {
                Integer position = sample.positions().get(vector.stableKey());
                if (position == null) return;
                if (visited.get(position)) throw indexChanged();
                visited.set(position);
                visitor.visit(position, vector.vector());
            });
            if (visited.cardinality() != sample.keys().length) throw indexChanged();
        }
    }

    /** Rows held in one matrix, in the order of the copy: no read of the index. */
    private final class CopiedRows implements Rows {
        private final float[] matrix;
        private final int[] positions;
        private final int dimensions;

        CopiedRows(float[] matrix, int[] positions, int dimensions) {
            this.matrix = matrix;
            this.positions = positions;
            this.dimensions = dimensions;
        }

        @Override
        public void each(String phase, RowVisitor visitor) {
            for (int row = 0; row < positions.length; row++) visitor.visit(positions[row], matrix, row * dimensions);
            progress.advanced(phase, passes, 0L);
        }
    }

    @FunctionalInterface
    private interface VectorVisitor {
        void visit(SemanticVectorStore.StoredVector vector);
    }

    @FunctionalInterface
    private interface SampleVisitor {
        void visit(int position, SemanticVector vector);
    }

    private record Candidate(String rank, String stableKey, SemanticDocumentKind kind) {
        long bytes() {
            return POINT_OVERHEAD_BYTES + 2L * Character.BYTES * stableKey.length();
        }
    }

    /** The selected documents, in stable-key order, out of the candidates of the selected kinds. */
    private static final class Sample {
        private final String[] keys;
        private final SemanticDocumentKind[] kinds;
        private final Map<String, Integer> positions;
        private final int candidateCount;

        Sample(String[] keys, SemanticDocumentKind[] kinds, Map<String, Integer> positions, int candidateCount) {
            this.keys = keys;
            this.kinds = kinds;
            this.positions = positions;
            this.candidateCount = candidateCount;
        }

        Map<String, Integer> positions() {
            return positions;
        }

        String[] keys() {
            return keys;
        }

        SemanticDocumentKind[] kinds() {
            return kinds;
        }

        int candidateCount() {
            return candidateCount;
        }
    }

    /** The bytes the computation holds, against the budget it was given; exceeding it stops the preparation. */
    static final class WorkingMemory {
        private final long budget;
        private long held;
        private long peak;

        WorkingMemory(long budget) {
            this.budget = budget;
        }

        /** Whether {@code bytes} more would still fit in the budget; changes neither the memory held nor the peak. */
        boolean fits(long bytes) {
            return bytes >= 0L && bytes <= budget - held;
        }

        void reserve(long bytes) {
            held += bytes;
            peak = Math.max(peak, held);
            if (held > budget) {
                throw new RefusedException(Refusal.MEMORY_BUDGET_EXCEEDED,
                        "the preparation needs more than its memory budget of " + budget
                                + " bytes; lower --max-points or raise --max-memory-mb");
            }
        }

        void release(long bytes) {
            held -= bytes;
        }

        long peak() {
            return peak;
        }
    }
}

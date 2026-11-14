package com.minos.application.semantic;

import com.minos.application.semantic.SemanticProjectionService.PreparationRequest;
import com.minos.application.semantic.SemanticProjectionService.Progress;
import com.minos.application.semantic.SemanticProjectionService.Refusal;
import com.minos.application.semantic.SemanticProjectionService.RefusedException;
import com.minos.io.Sha256;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * One computation of a projection: a reproducible sample of the documents of the selected kinds, then the two
 * principal axes of their vectors, every step being one walk of the index by pages. The index is never held:
 * what is kept grows with the number of points and with the size of one vector, and is counted against the
 * memory budget of the request.
 */
final class ProjectionPreparation {

    private static final String SEPARATOR = "\u001f";
    /** Estimated bytes of one selected document besides its stable key: rank, references and coordinates. */
    private static final long POINT_OVERHEAD_BYTES = 256L;
    private static final long VECTOR_OVERHEAD_BYTES = 160L;

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
    }

    /** What the computation found; the points are in stable-key order. */
    record Outcome(List<SemanticProjection.Point> points, int candidateCount, List<Double> explainedVariance,
                   int passes, long peakWorkingBytes) {
    }

    Outcome run() throws IOException {
        Sample sample = select();
        if (sample.keys().length == 0) {
            throw new RefusedException(Refusal.NO_DOCUMENT_OF_THE_SELECTED_KINDS,
                    "the index holds no document of the selected kinds; choose other kinds with --kinds");
        }
        int points = sample.keys().length;
        PrincipalAxes axes = new PrincipalAxes(metadata.dimensions(), request.seed());
        walkSample("center", sample, (position, vector) -> axes.addToMean(vector));
        axes.endMean();
        boolean still = false;
        for (int iteration = 0; iteration < SemanticProjectionService.MAX_ITERATIONS && !still; iteration++) {
            walkSample("fit", sample, (position, vector) -> axes.addToIteration(vector));
            still = axes.endIteration();
        }
        double[] coordinates = new double[2 * points];
        walkSample("project", sample, (position, vector) -> axes.project(vector, coordinates, position));
        double[] variance = axes.finish(coordinates, points);
        List<SemanticProjection.Point> projected = new ArrayList<>(points);
        for (int position = 0; position < points; position++) {
            projected.add(new SemanticProjection.Point(sample.keys()[position], sample.kinds()[position],
                    (float) coordinates[2 * position], (float) coordinates[2 * position + 1]));
        }
        return new Outcome(projected, sample.candidateCount(), List.of(variance[0], variance[1]), passes,
                memory.peak());
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
        for (int position = 0; position < keys.length; position++) {
            keys[position] = ordered.get(position).stableKey();
            kinds[position] = ordered.get(position).kind();
        }
        return new Sample(keys, kinds, candidates[0]);
    }

    /** Walks the index and gives the visitor the vectors of the sample, each with its position in the sample. */
    private void walkSample(String phase, Sample sample, SampleVisitor visitor) throws IOException {
        int[] next = {0};
        walk(phase, vector -> {
            if (next[0] < sample.keys().length && sample.keys()[next[0]].equals(vector.stableKey())) {
                visitor.visit(next[0], vector.vector());
                next[0]++;
            }
        });
        if (next[0] != sample.keys().length) throw indexChanged();
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
        private final int candidateCount;

        Sample(String[] keys, SemanticDocumentKind[] kinds, int candidateCount) {
            this.keys = keys;
            this.kinds = kinds;
            this.candidateCount = candidateCount;
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

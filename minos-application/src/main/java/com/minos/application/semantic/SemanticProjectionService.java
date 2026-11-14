package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticProjectionStore;
import com.minos.semantic.SemanticVectorStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The two-dimensional projection of a semantic index. It is prepared by one explicit call, which reads the
 * stored vectors by pages and publishes a derived cache; it is read back, state and points, without reading a
 * vector or computing anything. Nothing here embeds, searches or changes the index.
 */
public final class SemanticProjectionService {

    public static final String METHOD = "pca";
    public static final int METHOD_VERSION = 1;
    public static final int MAX_ITERATIONS = 40;
    public static final int DEFAULT_MAX_POINTS = 100_000;
    public static final int MAX_POINTS = 1_000_000;
    public static final long DEFAULT_SEED = 42L;
    public static final int DEFAULT_MAX_MEMORY_MEGABYTES = 256;
    public static final int MAX_POINTS_PAGE = 5_000;
    public static final String PREPARE_COMMAND = "minos semantic projection prepare";
    public static final String UNREADABLE = "PROJECTION_UNREADABLE";
    /** Phase reported once, when every pass is done and the projection is about to be published. */
    public static final String PUBLISH_PHASE = "publish";
    static final String PARAMETERS = "components=2;maxIterations=" + MAX_ITERATIONS + ";tolerance=" + PrincipalAxes.TOLERANCE;

    private final ProjectResolver projects;
    private final SemanticIndexService indexService;
    private final SemanticCorpusQueryService corpus;
    private final SemanticProjectionStore store;

    public SemanticProjectionService(ProjectResolver projects, SemanticIndexService indexService,
                                     SemanticCorpusQueryService corpus, SemanticProjectionStore store) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        this.corpus = Objects.requireNonNull(corpus, "corpus");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Computes the projection of the index of the project and publishes it in place of the previous one. The
     * index must be {@code READY}: projecting a stale index would publish a projection that is stale at once.
     * The vectors are read by pages; the embedding provider and the search are never called.
     *
     * @throws RefusedException when the preparation cannot be done or was stopped; nothing is then published
     *                          and the projection published before is untouched
     */
    public PreparationReport prepare(String projectReference, PreparationRequest request, Progress progress)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progress, "progress");
        long started = System.nanoTime();
        String projectId = projects.resolve(projectReference).id().toString();
        SemanticIndexService.Status index = indexService.statusWithoutSize(projectId);
        if (index.state() != SemanticIndexService.State.READY) {
            throw new RefusedException(Refusal.INDEX_NOT_READY, "the semantic index of the project is "
                    + index.state().name() + ", not READY; run minos ide semantic-index-sync " + projectId
                    + " then prepare the projection again");
        }
        SemanticVectorStore vectors = indexService.vectorStore();
        try (SemanticProjectionStore.Preparation preparation = store.prepare(projectId)) {
            SemanticVectorStore.IndexMetadata metadata = vectors.metadata(projectId)
                    .orElseThrow(ProjectionPreparation::indexChanged);
            SemanticProjection.IndexIdentity identity = identityNow(vectors, metadata);
            ProjectionPreparation.Outcome outcome =
                    new ProjectionPreparation(vectors, metadata, request, progress).run();
            // The index is read again before anything is published: what is published describes it or is not.
            boolean unchanged = vectors.metadata(projectId).map(metadata::equals).orElse(false)
                    && identity.equals(identityNow(vectors, metadata));
            if (!unchanged) throw ProjectionPreparation.indexChanged();
            SemanticProjection projection = new SemanticProjection(identity,
                    new SemanticProjection.Method(METHOD, METHOD_VERSION, PARAMETERS, request.seed()),
                    new SemanticProjection.Selection(request.kinds(), request.maxPoints()),
                    metadata.documentCount(), outcome.candidateCount(), outcome.explainedVariance(),
                    outcome.points());
            progress.advanced(PUBLISH_PHASE, outcome.passes() + 1, 0L);
            preparation.publish(projection);
            return new PreparationReport(projectId, projection.points().size(), projection.candidateCount(),
                    projection.documentCount(), projection.sampled(), projection.explainedVariance(),
                    outcome.passes(), (System.nanoTime() - started) / 1_000_000L, outcome.peakWorkingBytes());
        } catch (SemanticProjectionStore.PreparationInProgressException busy) {
            throw new RefusedException(Refusal.PREPARATION_IN_PROGRESS,
                    "another preparation of the projection of this project is running; wait for it to end");
        }
    }

    /**
     * The state of the projection of the project. Nothing is computed and no vector is read: the state comes
     * from the published projection and from the identity of the index, and no projection is ever prepared here.
     */
    public Status status(String projectReference) throws IOException {
        return read(projectReference).status();
    }

    /**
     * One page of the points of the published projection, from {@code offset}, with its state. A stale
     * projection is still shown, as stale; without a readable projection the page is empty and the state says
     * why. Nothing is computed and no vector is read.
     *
     * @throws IllegalArgumentException when {@code offset} is negative or {@code limit} is not between 1 and
     *                                  {@link #MAX_POINTS_PAGE}
     */
    public Points points(String projectReference, int offset, int limit) throws IOException {
        if (limit < 1 || limit > MAX_POINTS_PAGE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_POINTS_PAGE);
        }
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        Reading reading = read(projectReference);
        if (reading.projection().isEmpty()) return new Points(reading.status(), offset, 0, List.of());
        List<SemanticProjection.Point> all = reading.projection().orElseThrow().points();
        int from = Math.min(offset, all.size());
        return new Points(reading.status(), offset, all.size(), all.subList(from, Math.min(all.size(), from + limit)));
    }

    private Reading read(String projectReference) throws IOException {
        String projectId = projects.resolve(projectReference).id().toString();
        SemanticVectorStore vectors = indexService.vectorStore();
        Optional<SemanticVectorStore.IndexMetadata> metadata = vectors.metadata(projectId);
        int documents = metadata.map(SemanticVectorStore.IndexMetadata::documentCount).orElse(0);
        String command = PREPARE_COMMAND + " " + projectId;
        Optional<SemanticProjection> stored;
        try {
            stored = store.read(projectId);
        } catch (SemanticProjectionStore.UnreadableProjectionException unreadable) {
            return new Reading(new Status(projectId, State.ECHOUEE, Optional.empty(), documents, command, UNREADABLE),
                    Optional.empty());
        }
        if (stored.isEmpty()) {
            return new Reading(new Status(projectId, State.ABSENTE, Optional.empty(), documents, command, null),
                    Optional.empty());
        }
        SemanticProjection projection = stored.orElseThrow();
        State state = metadata.isPresent() && describes(projection, vectors, metadata.orElseThrow())
                ? State.PRETE : State.PERIMEE;
        return new Reading(new Status(projectId, state, Optional.of(Description.of(projection)), documents,
                command, null), stored);
    }

    private boolean describes(SemanticProjection projection, SemanticVectorStore vectors,
                              SemanticVectorStore.IndexMetadata metadata) throws IOException {
        try {
            return projection.index().equals(identity(corpus.identityOf(vectors, metadata)));
        } catch (SemanticCorpusQueryService.IndexChangedException replaced) {
            // The index is being replaced under this reading: the projection no longer describes it.
            return false;
        }
    }

    private static SemanticProjection.IndexIdentity identityNow(SemanticVectorStore vectors,
                                                                SemanticVectorStore.IndexMetadata metadata)
            throws IOException {
        try {
            return identity(SemanticCorpusQueryService.readIdentity(vectors, metadata));
        } catch (SemanticCorpusQueryService.IndexChangedException replaced) {
            throw ProjectionPreparation.indexChanged();
        }
    }

    private static SemanticProjection.IndexIdentity identity(SemanticCorpusQueryService.IndexIdentity value) {
        return new SemanticProjection.IndexIdentity(value.projectId(), value.snapshotId(), value.providerId(),
                value.modelId(), value.dimensions(), value.documentsFingerprint());
    }

    private record Reading(Status status, Optional<SemanticProjection> projection) {
    }

    /** What a reading of the projection of a project finds. */
    public enum State { ABSENTE, PRETE, PERIMEE, ECHOUEE }

    /** Why a preparation was refused; nothing is published and the previous projection is untouched. */
    public enum Refusal {
        INDEX_NOT_READY,
        NO_DOCUMENT_OF_THE_SELECTED_KINDS,
        MEMORY_BUDGET_EXCEEDED,
        INDEX_CHANGED_DURING_PREPARATION,
        PREPARATION_IN_PROGRESS
    }

    /** A preparation that was refused, with a message that says what to do. */
    public static final class RefusedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final Refusal refusal;

        RefusedException(Refusal refusal, String message) {
            super(message);
            this.refusal = Objects.requireNonNull(refusal, "refusal");
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** What a preparation is asked for. */
    public record PreparationRequest(int maxPoints, long seed, Set<SemanticDocumentKind> kinds, long maxMemoryBytes) {
        public PreparationRequest {
            if (maxPoints < 1 || maxPoints > MAX_POINTS) {
                throw new IllegalArgumentException("max-points must be between 1 and " + MAX_POINTS);
            }
            kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds"));
            if (kinds.isEmpty()) throw new IllegalArgumentException("kinds must name SYMBOL, CHUNK or FILE");
            if (maxMemoryBytes < 1L) throw new IllegalArgumentException("the memory budget must be positive");
        }
    }

    /** Receives the progress of a preparation: the phase, the pass over the index and the vectors read so far. */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (phase, pass, vectorsRead) -> { };

        void advanced(String phase, int pass, long vectorsRead);
    }

    /** What a preparation did. No path and no content. */
    public record PreparationReport(String projectId, int pointCount, int candidateCount, int documentCount,
                                    boolean sampled, List<Double> explainedVariance, int passes,
                                    long durationMillis, long peakWorkingBytes) {
        public PreparationReport {
            explainedVariance = List.copyOf(Objects.requireNonNull(explainedVariance, "explainedVariance"));
        }
    }

    /** A published projection without its points. */
    public record Description(SemanticProjection.IndexIdentity index, String method, int methodVersion,
                              String parameters, long seed, List<String> kinds, int maxPoints, int pointCount,
                              int candidateCount, int documentCount, boolean sampled,
                              List<Double> explainedVariance) {
        public Description {
            Objects.requireNonNull(index, "index");
            kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds"));
            explainedVariance = List.copyOf(Objects.requireNonNull(explainedVariance, "explainedVariance"));
        }

        static Description of(SemanticProjection projection) {
            return new Description(projection.index(), projection.method().name(), projection.method().version(),
                    projection.method().parameters(), projection.method().seed(),
                    projection.selection().kinds().stream().map(Enum::name).toList(),
                    projection.selection().maxPoints(), projection.points().size(), projection.candidateCount(),
                    projection.documentCount(), projection.sampled(), projection.explainedVariance());
        }
    }

    /**
     * The state of the projection of a project. {@code projection} is empty unless the state is {@link
     * State#PRETE} or {@link State#PERIMEE}; {@code cause} is {@code null} unless it is {@link State#ECHOUEE};
     * {@code prepareCommand} is the command that prepares the projection, never run by a reading.
     */
    public record Status(String projectId, State state, Optional<Description> projection, int indexDocumentCount,
                         String prepareCommand, String cause) {
        public Status {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(prepareCommand, "prepareCommand");
        }
    }

    /** One page of the points of a projection, in stable-key order, with the state they are shown under. */
    public record Points(Status status, int offset, int total, List<SemanticProjection.Point> points) {
        public Points {
            Objects.requireNonNull(status, "status");
            points = List.copyOf(Objects.requireNonNull(points, "points"));
        }
    }
}

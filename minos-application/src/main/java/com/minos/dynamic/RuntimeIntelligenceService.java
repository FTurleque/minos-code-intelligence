package com.minos.dynamic;

import com.minos.application.ProjectResolver;
import com.minos.domain.Symbol;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Imports and queries partial runtime observations without changing static knowledge. */
public final class RuntimeIntelligenceService {

    public static final int MAX_HOT_PATHS = 1_000;
    public static final int MAX_SESSION_RESULTS = 128;
    private static final int MAX_CANDIDATE_IDS = 1_000;
    private static final List<String> LIMITATIONS = List.of(
            "OBSERVED_PARTIAL: imported sessions describe only their declared time windows and collectors",
            "absence of an observation never proves that a symbol, line or call was not executed",
            "runtime observations do not add static provider capabilities or mutate the authoritative snapshot",
            "observedSymbolRatio is a correlation ratio over the active static symbol set, not exhaustive code coverage"
    );

    private final ProjectResolver projects;
    private final CodeKnowledgeSnapshotStore snapshots;
    private final RuntimeObservationStore store;
    private final Clock clock;

    public RuntimeIntelligenceService(
            ProjectRegistry registry,
            CodeKnowledgeSnapshotStore snapshots,
            RuntimeObservationStore store
    ) {
        this(registry, snapshots, store, Clock.systemUTC());
    }

    RuntimeIntelligenceService(
            ProjectRegistry registry,
            CodeKnowledgeSnapshotStore snapshots,
            RuntimeObservationStore store,
            Clock clock
    ) {
        this.projects = new ProjectResolver(Objects.requireNonNull(registry, "registry"));
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ImportResult importSession(String projectReference, RuntimeObservationEnvelopeCodec.DecodedSession decoded)
            throws IOException {
        Objects.requireNonNull(decoded, "decoded");
        RegisteredProject project = projects.resolve(projectReference);
        RuntimeObservationSession session = decoded.session();
        if (!project.id().equals(session.projectId())) {
            throw new IllegalArgumentException("runtime session projectId does not match registered project " + project.id());
        }
        CodeKnowledgeSnapshot snapshot = activeSnapshot(project);
        if (!snapshot.snapshotId().equals(session.snapshotId())) {
            throw new IllegalArgumentException("runtime session snapshot does not match active snapshot: expected "
                    + snapshot.snapshotId() + " but got " + session.snapshotId());
        }

        SymbolIndex index = new SymbolIndex(snapshot.symbols());
        List<CorrelatedRuntimeObservation> correlated = session.observations().stream()
                .map(observation -> new CorrelatedRuntimeObservation(
                        observation,
                        index.resolve(observation.source()),
                        observation.target() == null ? null : index.resolve(observation.target())))
                .toList();
        CorrelatedRuntimeSession accepted = new CorrelatedRuntimeSession(
                session, clock.instant(), decoded.sourceSha256(), correlated);
        RuntimeObservationStore.SaveResult saved = store.save(accepted);
        CorrelationCounts counts = correlationCounts(saved.session().observations());
        return new ImportResult(
                project.id(), project.displayName(), snapshot.snapshotId(), session.sessionId(), decoded.sourceSha256(),
                decoded.sourceBytes(), session.observations().size(), counts.resolved(), counts.unresolved(),
                counts.ambiguous(), saved.alreadyPresent(), saved.session().importedAt(),
                "OBSERVED_PARTIAL", false, LIMITATIONS);
    }

    public List<SessionView> listSessions(String projectReference, int limit) throws IOException {
        if (limit < 1 || limit > MAX_SESSION_RESULTS) throw new IllegalArgumentException("session limit must be between 1 and " + MAX_SESSION_RESULTS);
        RegisteredProject project = projects.resolve(projectReference);
        String activeSnapshotId = activeSnapshot(project).snapshotId();
        return store.list(project.id()).stream().limit(limit)
                .map(value -> sessionView(value, activeSnapshotId))
                .toList();
    }

    public RuntimeReport report(String projectReference, String sessionId, int hotPathLimit) throws IOException {
        if (hotPathLimit < 1 || hotPathLimit > MAX_HOT_PATHS) {
            throw new IllegalArgumentException("hot path limit must be between 1 and " + MAX_HOT_PATHS);
        }
        RegisteredProject project = projects.resolve(projectReference);
        CodeKnowledgeSnapshot snapshot = activeSnapshot(project);
        List<CorrelatedRuntimeSession> selected = selectSessions(project.id(), snapshot.snapshotId(), sessionId);

        Map<String, MutableHotPath> hot = new HashMap<>();
        Map<String, MutableCall> calls = new HashMap<>();
        Set<String> observedSymbolIds = new LinkedHashSet<>();
        Set<String> coveredLines = new LinkedHashSet<>();
        long totalHits = 0;
        long totalDuration = 0;
        List<CorrelatedRuntimeObservation> all = new ArrayList<>();
        for (CorrelatedRuntimeSession session : selected) {
            for (CorrelatedRuntimeObservation correlated : session.observations()) {
                all.add(correlated);
                RuntimeObservation observation = correlated.observation();
                totalHits = safeAdd(totalHits, observation.hits(), "total runtime hits");
                totalDuration = safeAdd(totalDuration, observation.totalDurationNanos(), "total runtime duration");
                addResolved(observedSymbolIds, correlated.source());
                addResolved(observedSymbolIds, correlated.target());
                if (observation.type() == RuntimeObservationType.LINE_COVERAGE) {
                    coveredLines.add(observation.source().fileId() + ":" + observation.source().line());
                }
                String hotKey = hotKey(correlated);
                hot.computeIfAbsent(hotKey, ignored -> new MutableHotPath(correlated))
                        .add(observation.hits(), observation.totalDurationNanos());
                if (observation.type() == RuntimeObservationType.CALL) {
                    String callKey = correlated.source().reference().display() + " -> " + correlated.target().reference().display();
                    calls.computeIfAbsent(callKey, ignored -> new MutableCall(correlated))
                            .add(observation.hits(), observation.totalDurationNanos());
                }
            }
        }
        CorrelationCounts counts = correlationCounts(all);
        List<HotPath> hotPaths = hot.values().stream().map(MutableHotPath::view)
                .sorted(Comparator.comparingLong(HotPath::totalDurationNanos).reversed()
                        .thenComparing(Comparator.comparingLong(HotPath::hits).reversed())
                        .thenComparing(HotPath::key))
                .limit(hotPathLimit).toList();
        List<ObservedCall> observedCalls = calls.values().stream().map(MutableCall::view)
                .sorted(Comparator.comparingLong(ObservedCall::totalDurationNanos).reversed()
                        .thenComparing(Comparator.comparingLong(ObservedCall::hits).reversed())
                        .thenComparing(ObservedCall::source).thenComparing(ObservedCall::target))
                .limit(hotPathLimit).toList();
        double ratio = snapshot.symbols().isEmpty() ? 0.0 : (double) observedSymbolIds.size() / snapshot.symbols().size();
        return new RuntimeReport(
                project.id(), project.displayName(), snapshot.snapshotId(),
                selected.stream().map(value -> sessionView(value, snapshot.snapshotId())).toList(),
                "OBSERVED_PARTIAL", false, snapshot.symbols().size(), observedSymbolIds.size(), ratio,
                coveredLines.size(), totalHits, totalDuration, counts.resolved(), counts.unresolved(), counts.ambiguous(),
                hotPaths, observedCalls, LIMITATIONS);
    }

    public SymbolRuntimeReport symbolReport(
            String projectReference,
            String symbolId,
            String sessionId,
            int callLimit
    ) throws IOException {
        requireText(symbolId, "symbolId");
        if (callLimit < 1 || callLimit > MAX_HOT_PATHS) throw new IllegalArgumentException("call limit is invalid");
        RegisteredProject project = projects.resolve(projectReference);
        CodeKnowledgeSnapshot snapshot = activeSnapshot(project);
        Symbol symbol = snapshot.symbols().stream().filter(value -> symbolId.equals(value.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown symbol in active snapshot: " + symbolId));
        List<CorrelatedRuntimeSession> selected = selectSessions(project.id(), snapshot.snapshotId(), sessionId);
        long executionHits = 0;
        long totalDuration = 0;
        long coveredLineHits = 0;
        Map<String, MutableCall> incoming = new HashMap<>();
        Map<String, MutableCall> outgoing = new HashMap<>();
        for (CorrelatedRuntimeSession session : selected) {
            for (CorrelatedRuntimeObservation value : session.observations()) {
                RuntimeObservation observation = value.observation();
                boolean source = resolvedAs(value.source(), symbolId);
                boolean target = resolvedAs(value.target(), symbolId);
                if (observation.type() == RuntimeObservationType.SYMBOL_EXECUTION && source) {
                    executionHits = safeAdd(executionHits, observation.hits(), "symbol execution hits");
                    totalDuration = safeAdd(totalDuration, observation.totalDurationNanos(), "symbol runtime duration");
                } else if (observation.type() == RuntimeObservationType.LINE_COVERAGE && source) {
                    coveredLineHits = safeAdd(coveredLineHits, observation.hits(), "covered line hits");
                } else if (observation.type() == RuntimeObservationType.CALL) {
                    if (source) {
                        String key = value.source().reference().display() + " -> " + value.target().reference().display();
                        outgoing.computeIfAbsent(key, ignored -> new MutableCall(value))
                                .add(observation.hits(), observation.totalDurationNanos());
                    }
                    if (target) {
                        String key = value.source().reference().display() + " -> " + value.target().reference().display();
                        incoming.computeIfAbsent(key, ignored -> new MutableCall(value))
                                .add(observation.hits(), observation.totalDurationNanos());
                    }
                }
            }
        }
        List<ObservedCall> incomingCalls = orderedCalls(incoming, callLimit);
        List<ObservedCall> outgoingCalls = orderedCalls(outgoing, callLimit);
        boolean observed = executionHits > 0 || coveredLineHits > 0 || !incomingCalls.isEmpty() || !outgoingCalls.isEmpty();
        return new SymbolRuntimeReport(
                project.id(), project.displayName(), snapshot.snapshotId(), symbol.id(), symbol.symbolKey(),
                symbol.qualifiedName(), selected.stream().map(value -> value.session().sessionId()).toList(),
                "OBSERVED_PARTIAL", false, observed, executionHits, totalDuration, coveredLineHits,
                incomingCalls, outgoingCalls, LIMITATIONS);
    }

    private List<CorrelatedRuntimeSession> selectSessions(UUID projectId, String snapshotId, String sessionId) throws IOException {
        if (sessionId != null && !sessionId.isBlank()) {
            CorrelatedRuntimeSession selected = store.find(projectId, sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown runtime session: " + sessionId));
            requireSnapshotAlignment(selected, snapshotId);
            return List.of(selected);
        }
        return store.list(projectId).stream()
                .filter(value -> snapshotId.equals(value.session().snapshotId()))
                .toList();
    }

    private static void requireSnapshotAlignment(CorrelatedRuntimeSession session, String activeSnapshotId) {
        if (!activeSnapshotId.equals(session.session().snapshotId())) {
            throw new IllegalArgumentException("runtime session is aligned to non-active snapshot "
                    + session.session().snapshotId() + "; active snapshot is " + activeSnapshotId);
        }
    }

    private CodeKnowledgeSnapshot activeSnapshot(RegisteredProject project) throws IOException {
        return snapshots.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException("project has no active static snapshot: " + project.displayName()));
    }

    private static SessionView sessionView(CorrelatedRuntimeSession value, String activeSnapshotId) {
        CorrelationCounts counts = correlationCounts(value.observations());
        RuntimeObservationSession session = value.session();
        return new SessionView(
                session.sessionId(), session.snapshotId(), session.startedAt(), session.endedAt(),
                session.collectorId(), session.collectorVersion(), session.environment(), session.completeness().name(),
                session.observations().size(), counts.resolved(), counts.unresolved(), counts.ambiguous(),
                value.importedAt(), value.sourceSha256(), activeSnapshotId.equals(session.snapshotId()));
    }

    private static CorrelationCounts correlationCounts(List<CorrelatedRuntimeObservation> observations) {
        int resolved = 0;
        int unresolved = 0;
        int ambiguous = 0;
        for (CorrelatedRuntimeObservation observation : observations) {
            for (RuntimeSymbolResolution resolution : List.of(observation.source())) {
                switch (resolution.status()) {
                    case RESOLVED -> resolved++;
                    case UNRESOLVED -> unresolved++;
                    case AMBIGUOUS -> ambiguous++;
                }
            }
            RuntimeSymbolResolution target = observation.target();
            if (target != null) {
                switch (target.status()) {
                    case RESOLVED -> resolved++;
                    case UNRESOLVED -> unresolved++;
                    case AMBIGUOUS -> ambiguous++;
                }
            }
        }
        return new CorrelationCounts(resolved, unresolved, ambiguous);
    }

    private static void addResolved(Set<String> ids, RuntimeSymbolResolution resolution) {
        if (resolution != null && resolution.status() == RuntimeResolutionStatus.RESOLVED) ids.add(resolution.symbolId());
    }

    private static boolean resolvedAs(RuntimeSymbolResolution resolution, String symbolId) {
        return resolution != null && resolution.status() == RuntimeResolutionStatus.RESOLVED
                && symbolId.equals(resolution.symbolId());
    }

    private static String hotKey(CorrelatedRuntimeObservation value) {
        return value.observation().type() + ":" + value.source().reference().display()
                + (value.target() == null ? "" : "->" + value.target().reference().display());
    }

    private static List<ObservedCall> orderedCalls(Map<String, MutableCall> calls, int limit) {
        return calls.values().stream().map(MutableCall::view)
                .sorted(Comparator.comparingLong(ObservedCall::totalDurationNanos).reversed()
                        .thenComparing(Comparator.comparingLong(ObservedCall::hits).reversed())
                        .thenComparing(ObservedCall::source).thenComparing(ObservedCall::target))
                .limit(limit).toList();
    }

    private static long safeAdd(long left, long right, String label) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(label + " exceeds supported range", exception);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static final class SymbolIndex {
        private final Map<String, List<Symbol>> byKey;
        private final Map<String, List<Symbol>> byQualifiedName;
        private final Map<String, List<Symbol>> byFile;

        private SymbolIndex(List<Symbol> symbols) {
            List<Symbol> ordered = symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList();
            this.byKey = group(ordered, Symbol::symbolKey);
            this.byQualifiedName = group(ordered, Symbol::qualifiedName);
            this.byFile = group(ordered, Symbol::fileId);
        }

        private RuntimeSymbolResolution resolve(RuntimeSymbolReference reference) {
            List<Symbol> candidates;
            if (reference.symbolKey() != null) {
                candidates = byKey.getOrDefault(reference.symbolKey(), List.of());
            } else if (reference.qualifiedName() != null) {
                candidates = byQualifiedName.getOrDefault(reference.qualifiedName(), List.of());
            } else {
                candidates = byFile.getOrDefault(reference.fileId(), List.of());
                if (reference.line() != null) {
                    candidates = candidates.stream().filter(symbol -> symbol.location() != null
                                    && reference.fileId().equals(symbol.location().fileId())
                                    && symbol.location().startLine() <= reference.line()
                                    && symbol.location().endLine() >= reference.line())
                            .toList();
                }
            }
            if (candidates.isEmpty()) {
                return new RuntimeSymbolResolution(RuntimeResolutionStatus.UNRESOLVED, reference,
                        null, null, null, List.of(), false);
            }
            if (candidates.size() == 1) {
                Symbol symbol = candidates.getFirst();
                return new RuntimeSymbolResolution(RuntimeResolutionStatus.RESOLVED, reference,
                        symbol.id(), symbol.symbolKey(), symbol.qualifiedName(), List.of(), false);
            }
            boolean truncated = candidates.size() > MAX_CANDIDATE_IDS;
            List<String> ids = candidates.stream().map(Symbol::id).limit(MAX_CANDIDATE_IDS).toList();
            return new RuntimeSymbolResolution(RuntimeResolutionStatus.AMBIGUOUS, reference,
                    null, null, null, ids, truncated);
        }

        private static Map<String, List<Symbol>> group(List<Symbol> symbols, Function<Symbol, String> key) {
            return symbols.stream().filter(symbol -> key.apply(symbol) != null)
                    .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        }
    }

    private static final class MutableHotPath {
        private final CorrelatedRuntimeObservation sample;
        private long hits;
        private long duration;

        private MutableHotPath(CorrelatedRuntimeObservation sample) { this.sample = sample; }

        private void add(long valueHits, long valueDuration) {
            hits = safeAdd(hits, valueHits, "hot path hits");
            duration = safeAdd(duration, valueDuration, "hot path duration");
        }

        private HotPath view() {
            return new HotPath(
                    sample.observation().type().name(), hotKey(sample),
                    sample.source().status().name(), sample.source().symbolId(),
                    sample.target() == null ? null : sample.target().status().name(),
                    sample.target() == null ? null : sample.target().symbolId(), hits, duration);
        }
    }

    private static final class MutableCall {
        private final CorrelatedRuntimeObservation sample;
        private long hits;
        private long duration;

        private MutableCall(CorrelatedRuntimeObservation sample) { this.sample = sample; }

        private void add(long valueHits, long valueDuration) {
            hits = safeAdd(hits, valueHits, "observed call hits");
            duration = safeAdd(duration, valueDuration, "observed call duration");
        }

        private ObservedCall view() {
            return new ObservedCall(
                    sample.source().reference().display(), sample.target().reference().display(),
                    sample.source().status().name(), sample.source().symbolId(),
                    sample.target().status().name(), sample.target().symbolId(), hits, duration);
        }
    }

    private record CorrelationCounts(int resolved, int unresolved, int ambiguous) { }

    public record ImportResult(
            UUID projectId, String projectName, String snapshotId, String sessionId, String sourceSha256,
            long sourceBytes, int observationCount, int resolvedReferences, int unresolvedReferences,
            int ambiguousReferences, boolean alreadyPresent, Instant importedAt, String nature,
            boolean exhaustive, List<String> limitations
    ) {
        public ImportResult { limitations = List.copyOf(limitations); }
    }

    public record SessionView(
            String sessionId, String snapshotId, Instant startedAt, Instant endedAt,
            String collectorId, String collectorVersion, String environment, String completeness,
            int observationCount, int resolvedReferences, int unresolvedReferences, int ambiguousReferences,
            Instant importedAt, String sourceSha256, boolean activeSnapshotAligned
    ) { }

    public record HotPath(
            String type, String key, String sourceResolution, String sourceSymbolId,
            String targetResolution, String targetSymbolId, long hits, long totalDurationNanos
    ) { }

    public record ObservedCall(
            String source, String target, String sourceResolution, String sourceSymbolId,
            String targetResolution, String targetSymbolId, long hits, long totalDurationNanos
    ) { }

    public record RuntimeReport(
            UUID projectId, String projectName, String snapshotId, List<SessionView> sessions,
            String nature, boolean exhaustive, int staticSymbolCount, int observedSymbolCount,
            double observedSymbolRatio, int coveredLineCount, long totalHits, long totalDurationNanos,
            int resolvedReferences, int unresolvedReferences, int ambiguousReferences,
            List<HotPath> hotPaths, List<ObservedCall> observedCalls, List<String> limitations
    ) {
        public RuntimeReport {
            sessions = List.copyOf(sessions); hotPaths = List.copyOf(hotPaths);
            observedCalls = List.copyOf(observedCalls); limitations = List.copyOf(limitations);
        }
    }

    public record SymbolRuntimeReport(
            UUID projectId, String projectName, String snapshotId, String symbolId, String symbolKey,
            String qualifiedName, List<String> sessionIds, String nature, boolean exhaustive,
            boolean observedInSelectedSessions, long executionHits, long totalDurationNanos,
            long coveredLineHits, List<ObservedCall> incomingCalls, List<ObservedCall> outgoingCalls,
            List<String> limitations
    ) {
        public SymbolRuntimeReport {
            sessionIds = List.copyOf(sessionIds); incomingCalls = List.copyOf(incomingCalls);
            outgoingCalls = List.copyOf(outgoingCalls); limitations = List.copyOf(limitations);
        }
    }
}

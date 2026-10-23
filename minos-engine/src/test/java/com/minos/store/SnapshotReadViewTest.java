package com.minos.store;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-07 (ADR 0056) : la vue neutre du snapshot actif sert les lectures par le port {@link CodeKnowledgeReader},
 * quel que soit le lecteur ; l'ancien contrat {@link SnapshotQueryView} reste servi sans boucle ni seconde
 * construction d'index.
 */
@SuppressWarnings("deprecation")
class SnapshotReadViewTest {

    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000a0707");
    private static final String PROJECT_ID = PROJECT.toString();
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @Test
    void aReaderThatIsNotTheMemoryIndexServesTheSevenReads() {
        CodeKnowledgeSnapshot snapshot = snapshot();
        InMemoryCodeKnowledgeStore memory = new InMemoryCodeKnowledgeStore(snapshot);
        RecordingReader recording = new RecordingReader(memory);
        CodeKnowledgeReader reader = recording;
        SymbolSearchCriteria criteria = SymbolSearchCriteria.lexical("target", 10);
        RelationshipSearchCriteria relationships = RelationshipSearchCriteria.incoming(ref("target"), Set.of(), 10);

        assertEquals(memory.findSymbolById(PROJECT_ID, "a"), reader.findSymbolById(PROJECT_ID, "a"));
        assertEquals(memory.findSymbols(PROJECT_ID, criteria), reader.findSymbols(PROJECT_ID, criteria));
        assertEquals(memory.findSymbols(PROJECT_ID, "b", 5), reader.findSymbols(PROJECT_ID, "b", 5));
        assertEquals(memory.lacksModuleAttribution(PROJECT_ID), reader.lacksModuleAttribution(PROJECT_ID));
        assertEquals(memory.findFileSymbols(PROJECT_ID, "src/a.java", 10),
                reader.findFileSymbols(PROJECT_ID, "src/a.java", 10));
        assertEquals(memory.findUsages(PROJECT_ID, "a", 10), reader.findUsages(PROJECT_ID, "a", 10));
        assertEquals(memory.findRelationships(PROJECT_ID, relationships),
                reader.findRelationships(PROJECT_ID, relationships));
        assertEquals(Set.of("findSymbolById", "findSymbols(criteria)", "findSymbols(query)", "lacksModuleAttribution",
                "findFileSymbols", "findUsages", "findRelationships"), Set.copyOf(recording.calls));
        assertFalse(reader instanceof CodeKnowledgeStore, "a reader carries no write");
    }

    @Test
    void theDefaultReadsOfAReaderAreThoseOfItsCriteriaSearch() {
        CodeKnowledgeReader minimal = new MinimalReader();

        assertFalse(minimal.lacksModuleAttribution(PROJECT_ID), "a reader that does not know answers false");
        assertEquals(List.of(), minimal.findSymbols(PROJECT_ID, "a", 5));
    }

    @Test
    void theViewRefusesMissingComponentsAndANegativeDuration() {
        CodeKnowledgeSnapshot snapshot = snapshot();
        SnapshotDescriptor descriptor = descriptor(snapshot);
        CodeKnowledgeReader reader = new RecordingReader(new InMemoryCodeKnowledgeStore(snapshot));

        assertThrows(NullPointerException.class, () -> new SnapshotReadView(null, snapshot, reader, 0L));
        assertThrows(NullPointerException.class, () -> new SnapshotReadView(descriptor, null, reader, 0L));
        assertThrows(NullPointerException.class, () -> new SnapshotReadView(descriptor, snapshot, null, 0L));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotReadView(descriptor, snapshot, reader, -1L));
        assertThrows(NullPointerException.class, () -> SnapshotReadView.indexed(null, snapshot));
        assertThrows(NullPointerException.class, () -> SnapshotReadView.indexed(descriptor, null));

        SnapshotReadView view = new SnapshotReadView(descriptor, snapshot, reader, 7L);
        assertSame(descriptor, view.descriptor());
        assertSame(snapshot, view.snapshot());
        assertSame(reader, view.reader());
        assertEquals(7L, view.buildNanos());
    }

    @Test
    void theIndexedFactoryBuildsTheMemoryIndexAndSharesItsEdgeIndex() {
        CodeKnowledgeSnapshot snapshot = snapshot();
        SnapshotReadView view = SnapshotReadView.indexed(descriptor(snapshot), snapshot);

        InMemoryCodeKnowledgeStore memory = assertInstanceOf(InMemoryCodeKnowledgeStore.class, view.reader());
        assertTrue(view.buildNanos() >= 0L);
        assertSame(memory.symbolEdgeIndex(PROJECT_ID), view.incomingRelationships());
        assertSame(view.incomingRelationships(), view.incomingRelationships());
        assertEquals(1L, memory.symbolEdgeIndexBuilds());
        assertEquals(List.of("r1", "r2"), ids(view.incomingRelationships().incoming("target")));
    }

    @Test
    void anotherReaderGetsAnIncomingIndexBuiltOnceFromTheSnapshotEvenUnderConcurrentReaders() throws Exception {
        CodeKnowledgeSnapshot snapshot = snapshot();
        SnapshotReadView view = new SnapshotReadView(descriptor(snapshot), snapshot,
                new RecordingReader(new InMemoryCodeKnowledgeStore(snapshot)), 0L);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<IncomingRelationships>> results = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return view.incomingRelationships();
                }));
            }
            start.countDown();
            IncomingRelationships shared = results.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<IncomingRelationships> result : results) {
                assertSame(shared, result.get(30, TimeUnit.SECONDS));
            }
            assertSame(shared, view.incomingRelationships(), "a later read reuses the published index");
            IncomingRelationships reference = IncomingRelationships.of(snapshot);
            assertEquals(ids(reference.incoming("target")), ids(shared.incoming("target")));
            assertEquals(reference.targetCount(), shared.targetCount());
            assertTrue(shared.hasUnresolved(Set.of(RelationshipKind.IMPORTS)));
            assertTrue(shared.leavesProjectSymbols(Set.of(RelationshipKind.REFERENCES)));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, view.incomingRelationshipsBuilds());
    }

    @Test
    void aStoreWrittenForTheLegacyContractServesTheReadViewByDerivation() throws Exception {
        CodeKnowledgeSnapshot snapshot = snapshot();
        SnapshotQueryView legacy = new SnapshotQueryView(descriptor(snapshot), snapshot,
                new InMemoryCodeKnowledgeStore(snapshot), 11L);
        LegacyOnlyStore store = new LegacyOnlyStore(legacy);

        SnapshotReadView view = store.loadActiveReadView(PROJECT).orElseThrow();

        assertSame(legacy.descriptor(), view.descriptor());
        assertSame(legacy.snapshot(), view.snapshot());
        assertSame(legacy.queryStore(), view.reader());
        assertEquals(11L, view.buildNanos());
        assertSame(legacy.queryStore().symbolEdgeIndex(PROJECT_ID), view.incomingRelationships());
        assertEquals(Optional.empty(), new LegacyOnlyStore(null).loadActiveReadView(PROJECT));
    }

    @Test
    void theDeprecatedFactoryReusesTheMemoryIndexAndBuildsOneForAnotherReaderWithoutLooping() throws Exception {
        CodeKnowledgeSnapshot snapshot = snapshot();
        SnapshotReadView indexed = SnapshotReadView.indexed(descriptor(snapshot), snapshot);
        SnapshotQueryView reused = SnapshotQueryView.of(indexed);
        assertSame(indexed.reader(), reused.queryStore());
        assertSame(indexed.descriptor(), reused.descriptor());
        assertEquals(indexed.buildNanos(), reused.buildNanos());
        assertSame(reused, SnapshotQueryView.of(indexed), "the legacy view of a read view is made once");

        SnapshotReadView other = new SnapshotReadView(descriptor(snapshot), snapshot,
                new RecordingReader(new InMemoryCodeKnowledgeStore(snapshot)), 3L);
        ReadViewOnlyStore store = new ReadViewOnlyStore(other);
        SnapshotQueryView built = store.loadActiveQueryView(PROJECT).orElseThrow();

        assertNotSame(other.reader(), built.queryStore());
        assertSame(snapshot, built.snapshot());
        assertEquals(Optional.of("a"), built.queryStore().findSymbolById(PROJECT_ID, "a").map(Symbol::id));
        assertEquals(snapshot.symbols().size(), built.indexMetrics().symbolIdEntries());
        assertTrue(built.buildNanos() >= 0L);
        assertSame(built, store.loadActiveQueryView(PROJECT).orElseThrow(), "the memory index is built once");
        assertEquals(2, store.readViewLoads);
        assertThrows(NullPointerException.class, () -> SnapshotQueryView.of(null));
    }

    @Test
    void theNeutralWeightIsTheMemoryIndexWeight() {
        for (long count : new long[] {0L, 1L, 1_000L, Long.MAX_VALUE / 2L}) {
            assertEquals(InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes(count),
                    SnapshotReadView.incomingRelationshipsWeightBytes(count));
        }
        assertThrows(IllegalArgumentException.class, () -> SnapshotReadView.incomingRelationshipsWeightBytes(-1L));
    }

    @Test
    void theNeutralIncomingFactoryIsTheOneShotEdgeIndex() {
        CodeKnowledgeSnapshot snapshot = snapshot();
        IncomingRelationships neutral = IncomingRelationships.of(snapshot);
        InMemoryCodeKnowledgeStore.SymbolEdgeIndex memory = InMemoryCodeKnowledgeStore.SymbolEdgeIndex.of(snapshot);

        assertEquals(ids(memory.incoming("target")), ids(neutral.incoming("target")));
        assertEquals(memory.targetCount(), neutral.targetCount());
        assertThrows(NullPointerException.class, () -> IncomingRelationships.of(null));
    }

    private static List<String> ids(List<Relationship> relationships) {
        return relationships.stream().map(Relationship::id).toList();
    }

    static CodeKnowledgeSnapshot snapshot() {
        Symbol a = symbol("a");
        Symbol b = symbol("b");
        Symbol target = symbol("target");
        return new CodeKnowledgeSnapshot(PROJECT, "snapshot-read-view", List.of(a, b, target), List.of(), List.of(
                resolved("r1", ref("a"), ref("target"), RelationshipKind.CALLS),
                resolved("r2", ref("b"), ref("target"), RelationshipKind.CALLS),
                resolved("r3", ref("a"), new CodeEntityRef(CodeEntityType.SOURCE_FILE, "src/b.java"),
                        RelationshipKind.REFERENCES),
                new Relationship("u1", PROJECT_ID, ref("a"), null, "unknown.u1", RelationshipKind.IMPORTS, null,
                        ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null, ORIGIN, List.of())));
    }

    static SnapshotDescriptor descriptor(CodeKnowledgeSnapshot snapshot) {
        return new SnapshotDescriptor(3, snapshot.snapshotId(), snapshot.snapshotId() + ".bin", "0".repeat(64),
                snapshot.symbols().size(), snapshot.occurrences().size(), snapshot.relationships().size());
    }

    private static CodeEntityRef ref(String id) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, id);
    }

    private static Relationship resolved(String id, CodeEntityRef source, CodeEntityRef target, RelationshipKind kind) {
        return new Relationship(id, PROJECT_ID, source, target, null, kind, null, ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL, null, ORIGIN, List.of());
    }

    private static Symbol symbol(String id) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, PROJECT_ID, null,
                "src/" + id + ".java", null, SymbolKind.CLASS, id, "com.acme." + id, null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }

    /** Lecteur de test qui n'est pas l'index mémoire : il relaie chaque lecture et la note. */
    private static final class RecordingReader implements CodeKnowledgeReader {
        private final CodeKnowledgeReader delegate;
        private final List<String> calls = new ArrayList<>();

        private RecordingReader(CodeKnowledgeReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Symbol> findSymbolById(String projectId, String symbolId) {
            calls.add("findSymbolById");
            return delegate.findSymbolById(projectId, symbolId);
        }

        @Override
        public List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria) {
            calls.add("findSymbols(criteria)");
            return delegate.findSymbols(projectId, criteria);
        }

        @Override
        public boolean lacksModuleAttribution(String projectId) {
            calls.add("lacksModuleAttribution");
            return delegate.lacksModuleAttribution(projectId);
        }

        @Override
        public List<Symbol> findSymbols(String projectId, String query, int limit) {
            calls.add("findSymbols(query)");
            return delegate.findSymbols(projectId, query, limit);
        }

        @Override
        public List<Symbol> findFileSymbols(String projectId, String fileId, int limit) {
            calls.add("findFileSymbols");
            return delegate.findFileSymbols(projectId, fileId, limit);
        }

        @Override
        public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) {
            calls.add("findUsages");
            return delegate.findUsages(projectId, symbolId, limit);
        }

        @Override
        public List<Relationship> findRelationships(String projectId, RelationshipSearchCriteria criteria) {
            calls.add("findRelationships");
            return delegate.findRelationships(projectId, criteria);
        }
    }

    /** Lecteur minimal : il ne redéfinit aucune lecture par défaut. */
    private static final class MinimalReader implements CodeKnowledgeReader {
        @Override
        public Optional<Symbol> findSymbolById(String projectId, String symbolId) {
            return Optional.empty();
        }

        @Override
        public List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria) {
            return List.of();
        }

        @Override
        public List<Symbol> findFileSymbols(String projectId, String fileId, int limit) {
            return List.of();
        }

        @Override
        public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) {
            return List.of();
        }

        @Override
        public List<Relationship> findRelationships(String projectId, RelationshipSearchCriteria criteria) {
            return List.of();
        }
    }

    /** Store écrit pour l'ancien contrat : il ne redéfinit que {@code loadActiveQueryView}. */
    private static final class LegacyOnlyStore extends UnusedPublication {
        private final SnapshotQueryView view;

        private LegacyOnlyStore(SnapshotQueryView view) {
            this.view = view;
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return Optional.ofNullable(view);
        }
    }

    /** Store qui ne sert que la vue neutre ; l'ancienne vue vient de la fabrique dépréciée. */
    private static final class ReadViewOnlyStore extends UnusedPublication {
        private final SnapshotReadView view;
        private int readViewLoads;

        private ReadViewOnlyStore(SnapshotReadView view) {
            this.view = view;
        }

        @Override
        public Optional<SnapshotReadView> loadActiveReadView(UUID projectId) {
            readViewLoads++;
            return Optional.of(view);
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return loadActiveReadView(projectId).map(SnapshotQueryView::of);
        }
    }

    private abstract static class UnusedPublication implements CodeKnowledgeSnapshotStore {
        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            throw new UnsupportedOperationException("legacy symbol snapshots are not used by this test");
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            throw new UnsupportedOperationException("raw snapshots are not used by this test");
        }
    }
}

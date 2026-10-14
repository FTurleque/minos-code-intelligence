package com.minos.store;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AUD-PERF-11 : index dérivé des arêtes entrantes, construit une fois par store et par projet. */
class SymbolEdgeIndexTest {

    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-00000000e011");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000e012");
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @Test
    void incomingRelationshipsAreOrderedBySourceKindThenIdentifier() {
        CodeKnowledgeSnapshot snapshot = snapshot(PROJECT, List.of(
                resolved(PROJECT, "r9", symbol("b"), symbol("target"), RelationshipKind.CALLS),
                resolved(PROJECT, "r2", symbol("b"), symbol("target"), RelationshipKind.CALLS),
                resolved(PROJECT, "r5", symbol("a"), symbol("target"), RelationshipKind.REFERENCES),
                resolved(PROJECT, "r1", symbol("b"), symbol("target"), RelationshipKind.TYPE_DEFINITION),
                resolved(PROJECT, "r3", file("file"), symbol("target"), RelationshipKind.CONTAINS),
                resolved(PROJECT, "r4", symbol("a"), symbol("b"), RelationshipKind.CALLS)));
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore(snapshot);

        InMemoryCodeKnowledgeStore.SymbolEdgeIndex index = store.symbolEdgeIndex(PROJECT.toString());

        assertEquals(List.of("r5", "r1", "r2", "r9", "r3"), ids(index.incoming("target")));
        assertEquals(List.of("r4"), ids(index.incoming("b")));
        assertEquals(List.of(), index.incoming("unknown"));
        assertEquals(2, index.targetCount());
        assertEquals(ids(index.incoming("target")),
                ids(InMemoryCodeKnowledgeStore.SymbolEdgeIndex.of(snapshot).incoming("target")));
    }

    @Test
    void statesUnresolvedKindsAndRelationshipsLeavingTheProjectSymbols() {
        CodeKnowledgeSnapshot snapshot = snapshot(PROJECT, List.of(
                unresolved(PROJECT, "u1", symbol("a"), RelationshipKind.IMPORTS),
                resolved(PROJECT, "f1", symbol("a"), file("file"), RelationshipKind.REFERENCES),
                resolved(PROJECT, "g1", symbol("a"), symbol("ghost"), RelationshipKind.EXTENDS),
                resolved(PROJECT, "s1", file("file"), symbol("a"), RelationshipKind.DECLARES),
                resolved(PROJECT, "ok", symbol("a"), symbol("b"), RelationshipKind.CALLS)));
        InMemoryCodeKnowledgeStore.SymbolEdgeIndex index = new InMemoryCodeKnowledgeStore(snapshot)
                .symbolEdgeIndex(PROJECT.toString());

        assertTrue(index.hasUnresolved(Set.of(RelationshipKind.IMPORTS, RelationshipKind.CALLS)));
        assertFalse(index.hasUnresolved(Set.of(RelationshipKind.CALLS)));
        assertTrue(index.leavesProjectSymbols(Set.of(RelationshipKind.REFERENCES)));
        assertTrue(index.leavesProjectSymbols(Set.of(RelationshipKind.EXTENDS)));
        assertTrue(index.leavesProjectSymbols(Set.of(RelationshipKind.DECLARES)));
        assertFalse(index.leavesProjectSymbols(Set.of(RelationshipKind.CALLS, RelationshipKind.IMPORTS)));
        assertEquals(List.of("g1"), ids(index.incoming("ghost")));
    }

    @Test
    void isBuiltOncePerProjectAndDroppedByWrites() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(symbolOf(PROJECT, "a"), symbolOf(PROJECT, "b"), symbolOf(OTHER, "a")));
        store.putRelationships(List.of(
                resolved(PROJECT, "p1", symbol("a"), symbol("b"), RelationshipKind.CALLS),
                resolved(OTHER, "o1", symbol("b"), symbol("a"), RelationshipKind.CALLS)));

        InMemoryCodeKnowledgeStore.SymbolEdgeIndex first = store.symbolEdgeIndex(PROJECT.toString());
        assertSame(first, store.symbolEdgeIndex(PROJECT.toString()));
        assertEquals(1L, store.symbolEdgeIndexBuilds());
        assertEquals(List.of("p1"), ids(first.incoming("b")));
        assertEquals(List.of(), first.incoming("a"), "a relationship of another project is not indexed");
        assertFalse(first.leavesProjectSymbols(Set.of(RelationshipKind.CALLS)));

        InMemoryCodeKnowledgeStore.SymbolEdgeIndex other = store.symbolEdgeIndex(OTHER.toString());
        assertEquals(List.of("o1"), ids(other.incoming("a")));
        assertTrue(other.leavesProjectSymbols(Set.of(RelationshipKind.CALLS)), "b is not a symbol of OTHER");
        assertEquals(2L, store.symbolEdgeIndexBuilds());

        store.putRelationships(List.of(resolved(PROJECT, "p2", symbol("b"), symbol("a"), RelationshipKind.CALLS)));
        InMemoryCodeKnowledgeStore.SymbolEdgeIndex rebuilt = store.symbolEdgeIndex(PROJECT.toString());
        assertNotSame(first, rebuilt);
        assertEquals(List.of("p2"), ids(rebuilt.incoming("a")));

        store.putSymbols(List.of(symbolOf(PROJECT, "c")));
        assertNotSame(rebuilt, store.symbolEdgeIndex(PROJECT.toString()));
        assertEquals(4L, store.symbolEdgeIndexBuilds());

        store.putOccurrences(List.of());
        store.symbolEdgeIndex(PROJECT.toString());
        assertEquals(4L, store.symbolEdgeIndexBuilds(), "occurrences do not change the index");
        assertThrows(IllegalArgumentException.class, () -> store.symbolEdgeIndex(" "));
    }

    @Test
    void simultaneousFirstReadersShareOneBuild() throws Exception {
        List<Relationship> relationships = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) {
            relationships.add(resolved(PROJECT, "r" + index, symbol("a"), symbol("b"), RelationshipKind.CALLS));
        }
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore(snapshot(PROJECT, relationships));
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<InMemoryCodeKnowledgeStore.SymbolEdgeIndex>> results = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return store.symbolEdgeIndex(PROJECT.toString());
                }));
            }
            start.countDown();
            InMemoryCodeKnowledgeStore.SymbolEdgeIndex shared = results.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<InMemoryCodeKnowledgeStore.SymbolEdgeIndex> result : results) {
                assertSame(shared, result.get(30, TimeUnit.SECONDS));
            }
            assertEquals(2_000, shared.incoming("b").size());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, store.symbolEdgeIndexBuilds());
    }

    @Test
    void weightIsChargedPerRelationshipAndSaturates() {
        long empty = InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes(0L);
        long thousand = InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes(1_000L);

        assertTrue(empty > 0L);
        assertEquals(1_000L * 96L, thousand - empty);
        assertEquals(Long.MAX_VALUE, InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes(Long.MAX_VALUE / 2L));
        assertThrows(IllegalArgumentException.class, () -> InMemoryCodeKnowledgeStore.symbolEdgeIndexWeightBytes(-1L));
    }

    private static List<String> ids(List<Relationship> relationships) {
        return relationships.stream().map(Relationship::id).toList();
    }

    private static CodeKnowledgeSnapshot snapshot(UUID project, List<Relationship> relationships) {
        return new CodeKnowledgeSnapshot(project, "snapshot", List.of(symbolOf(project, "a"), symbolOf(project, "b"),
                symbolOf(project, "target")), List.of(), relationships);
    }

    private static CodeEntityRef symbol(String id) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, id);
    }

    private static CodeEntityRef file(String id) {
        return new CodeEntityRef(CodeEntityType.SOURCE_FILE, id);
    }

    private static Relationship resolved(UUID project, String id, CodeEntityRef source, CodeEntityRef target,
                                         RelationshipKind kind) {
        return new Relationship(id, project.toString(), source, target, null, kind, null, ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL, null, ORIGIN, List.of());
    }

    private static Relationship unresolved(UUID project, String id, CodeEntityRef source, RelationshipKind kind) {
        return new Relationship(id, project.toString(), source, null, "unknown." + id, kind, null,
                ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null, ORIGIN, List.of());
    }

    private static Symbol symbolOf(UUID project, String id) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.toString(), null,
                "src/" + id + ".java", null, SymbolKind.CLASS, id, "com.acme." + id, null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}

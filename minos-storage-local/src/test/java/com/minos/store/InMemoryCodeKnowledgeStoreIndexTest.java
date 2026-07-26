package com.minos.store;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCodeKnowledgeStoreIndexTest {

    @Test
    void qualifiedNameAndFileQueriesUseReconstructibleIndexes() {
        UUID projectId = UUID.randomUUID();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(FileSymbolSnapshotStoreTest.symbols(projectId));

        assertEquals(
                List.of("method-string"),
                store.findSymbols(
                                projectId.toString(),
                                SymbolSearchCriteria.qualifiedName("com.minos.Converter.convert", 10)
                        ).stream()
                        .filter(symbol -> "(java.lang.String)".equals(symbol.signature()))
                        .map(symbol -> symbol.id())
                        .toList()
        );
        assertEquals(
                List.of("method-int", "method-string"),
                store.findFileSymbols(projectId.toString(), "file-converter", 10)
                        .stream()
                        .map(symbol -> symbol.id())
                        .toList()
        );
        assertTrue(store.indexMetrics().qualifiedNameKeys() > 0);
        assertTrue(store.indexMetrics().fileIdKeys() > 0);
    }

    @Test
    void usageAndRelationshipQueriesUseAnchorIndexesWithoutChangingOrdering() {
        UUID projectId = UUID.randomUUID();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(FileSymbolSnapshotStoreTest.symbols(projectId));
        store.putOccurrences(List.of(usage(projectId, "usage-b", 30), usage(projectId, "usage-a", 20)));
        store.putRelationships(List.of(
                relationship(projectId, "rel-b", "method-string", "method-int", 20),
                relationship(projectId, "rel-a", "method-string", "external-clock", 10)
        ));

        assertEquals(
                List.of("usage-a", "usage-b"),
                store.findUsages(projectId.toString(), "method-int", 10)
                        .stream()
                        .map(SymbolOccurrence::id)
                        .toList()
        );
        CodeEntityRef source = new CodeEntityRef(CodeEntityType.SYMBOL, "method-string");
        assertEquals(
                List.of("rel-a", "rel-b"),
                store.findRelationships(
                                projectId.toString(),
                                RelationshipSearchCriteria.outgoing(source, Set.of(RelationshipKind.CALLS), 10)
                        ).stream()
                        .map(Relationship::id)
                        .toList()
        );
        assertEquals(1, store.indexMetrics().resolvedSymbolIdKeys());
        assertTrue(store.indexMetrics().sourceEntityKeys() > 0);
        assertTrue(store.indexMetrics().targetEntityKeys() > 0);
        assertTrue(store.indexMetrics().relationshipKindKeys() > 0);
    }

    @Test
    void snapshotConstructorBuildsAllIndexesOnceFromTheSourceOfTruth() {
        UUID projectId = UUID.randomUUID();
        List<SymbolOccurrence> occurrences = List.of(usage(projectId, "usage", 20));
        List<Relationship> relationships = List.of(
                relationship(projectId, "rel", "method-string", "method-int", 10));
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "snapshot",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                occurrences,
                relationships
        );

        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore(snapshot);

        assertEquals(snapshot.symbols().size(), store.indexMetrics().symbolIdEntries());
        assertEquals(List.of("usage"), store.findUsages(projectId.toString(), "method-int", 10)
                .stream().map(SymbolOccurrence::id).toList());
        assertTrue(store.indexMetrics().indexReferences() >= snapshot.symbols().size());
    }

    private static SymbolOccurrence usage(UUID projectId, String id, int line) {
        return new SymbolOccurrence(
                id,
                projectId.toString(),
                new ResolvedSymbolReference("method-int"),
                location("file-caller", line),
                Set.of(OccurrenceRole.REFERENCE),
                ResolutionStatus.RESOLVED,
                origin(),
                Set.of()
        );
    }

    private static Relationship relationship(
            UUID projectId,
            String id,
            String sourceId,
            String targetId,
            int line
    ) {
        return new Relationship(
                id,
                projectId.toString(),
                new CodeEntityRef(CodeEntityType.SYMBOL, sourceId),
                new CodeEntityRef(CodeEntityType.SYMBOL, targetId),
                null,
                RelationshipKind.CALLS,
                location("file-caller", line),
                ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL,
                null,
                origin(),
                List.of()
        );
    }

    private static SymbolLocation location(String fileId, int line) {
        return new SymbolLocation(fileId, line, 1, line, 10, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static Origin origin() {
        return new Origin("fixture-provider", "TEST", "1", "run", OriginType.OTHER);
    }
}

package com.minos.store;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ProviderReference;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.UnresolvedSymbolReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCodeKnowledgeSnapshotStoreTest {

    @Test
    void roundTripsResolvedAndUnresolvedOccurrencesAndRelationships(@TempDir Path root)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        var symbols = FileSymbolSnapshotStoreTest.symbols(projectId);
        var occurrences = occurrences(projectId);
        var relationships = relationships(projectId);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);

        store.publish(projectId, "knowledge-1", symbols, occurrences, relationships);

        CodeKnowledgeSnapshot loaded = new FileSymbolSnapshotStore(root)
                .loadActiveKnowledge(projectId)
                .orElseThrow();
        assertEquals("knowledge-1", loaded.snapshotId());
        assertEquals(4, loaded.symbols().size());
        assertEquals(List.of("occ-resolved", "occ-unresolved"),
                loaded.occurrences().stream().map(SymbolOccurrence::id).toList());
        assertEquals(List.of("rel-calls", "rel-derived"),
                loaded.relationships().stream().map(Relationship::id).toList());
        assertTrue(loaded.occurrences().getFirst().roles().containsAll(Set.of(
                OccurrenceRole.REFERENCE,
                OccurrenceRole.CALL
        )));
        UnresolvedSymbolReference unresolved = (UnresolvedSymbolReference)
                loaded.occurrences().get(1).symbolRef();
        assertEquals("com.minos.MissingClient", unresolved.qualifiedNameCandidate());
        Relationship derived = loaded.relationships().get(1);
        assertEquals(InformationNature.DERIVED, derived.nature());
        assertEquals(0.75, derived.confidence());
        assertEquals(2, derived.evidence().size());
    }

    @Test
    void writesDeterministicV2BytesAcrossCollectionAndEvidenceOrder(@TempDir Path root)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        var symbols = FileSymbolSnapshotStoreTest.symbols(projectId);
        var occurrences = occurrences(projectId);
        var relationships = relationships(projectId);
        Path first = root.resolve("first");
        Path second = root.resolve("second");

        new FileSymbolSnapshotStore(first).publish(
                projectId, "knowledge", symbols, occurrences, relationships);
        Relationship derived = relationships.get(1);
        Relationship reversedEvidence = new Relationship(
                derived.id(), derived.projectId(), derived.source(), derived.target(),
                derived.unresolvedTarget(), derived.kind(), derived.location(),
                derived.resolutionStatus(), derived.nature(), derived.confidence(),
                derived.origin(), derived.evidence().reversed()
        );
        new FileSymbolSnapshotStore(second).publish(
                projectId,
                "knowledge",
                symbols.reversed(),
                occurrences.reversed(),
                List.of(reversedEvidence, relationships.getFirst())
        );

        assertArrayEquals(knowledgeBytes(first, projectId), knowledgeBytes(second, projectId));
    }

    @Test
    void loadsV1AsKnowledgeWithEmptyM3CollectionsAndCanPromoteV2(@TempDir Path root)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        var symbols = FileSymbolSnapshotStoreTest.symbols(projectId);
        store.publish(projectId, "legacy", symbols);

        CodeKnowledgeSnapshot legacy = store.loadActiveKnowledge(projectId).orElseThrow();
        assertEquals("legacy", legacy.snapshotId());
        assertTrue(legacy.occurrences().isEmpty());
        assertTrue(legacy.relationships().isEmpty());

        store.publish(projectId, "knowledge", symbols, occurrences(projectId), relationships(projectId));
        CodeKnowledgeSnapshot active = store.loadActiveKnowledge(projectId).orElseThrow();
        assertEquals("knowledge", active.snapshotId());
        assertEquals(2, active.occurrences().size());
        assertEquals(2, active.relationships().size());
        assertEquals(4, store.loadActive(projectId).orElseThrow().symbols().size());
    }

    @Test
    void detectsV2CorruptionBeforeDeserialization(@TempDir Path root) throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(
                projectId,
                "knowledge",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                occurrences(projectId),
                relationships(projectId)
        );
        Path snapshot = knowledgeFile(root, projectId);
        Files.write(snapshot, new byte[]{0x01}, StandardOpenOption.APPEND);

        IOException exception = assertThrows(
                IOException.class,
                () -> store.loadActiveKnowledge(projectId)
        );
        assertTrue(exception.getMessage().contains("checksum mismatch"));
    }

    @Test
    void rejectsDuplicateAndCrossProjectM3Facts(@TempDir Path root) throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        SymbolOccurrence occurrence = occurrences(projectId).getFirst();
        Relationship relationship = relationships(projectId).getFirst();

        assertThrows(IllegalArgumentException.class, () -> store.publish(
                projectId,
                "duplicate-occurrence",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(occurrence, occurrence),
                List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> store.publish(
                projectId,
                "duplicate-relationship",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(),
                List.of(relationship, relationship)
        ));
        UUID otherProject = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> store.publish(
                projectId,
                "foreign",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                occurrences(otherProject),
                List.of()
        ));
    }

    private static List<SymbolOccurrence> occurrences(UUID projectId) {
        return List.of(
                new SymbolOccurrence(
                        "occ-resolved",
                        projectId.toString(),
                        new ResolvedSymbolReference("method-int"),
                        location("file-caller", 20),
                        Set.of(OccurrenceRole.REFERENCE, OccurrenceRole.CALL),
                        ResolutionStatus.RESOLVED,
                        origin(),
                        Set.of(new ProviderReference("fixture-provider", "opaque-usage"))
                ),
                new SymbolOccurrence(
                        "occ-unresolved",
                        projectId.toString(),
                        new UnresolvedSymbolReference(
                                "MissingClient",
                                "com.minos.MissingClient",
                                "java",
                                "missing dependency",
                                Set.of(new ProviderReference("fixture-provider", "opaque-missing"))
                        ),
                        location("file-caller", 25),
                        Set.of(OccurrenceRole.REFERENCE),
                        ResolutionStatus.UNRESOLVED,
                        origin(),
                        Set.of()
                )
        );
    }

    private static List<Relationship> relationships(UUID projectId) {
        CodeEntityRef source = symbol("method-string");
        CodeEntityRef target = symbol("method-int");
        Evidence providerEvidence = new Evidence(
                EvidenceType.DIRECT_CALL,
                "direct provider call",
                source,
                target,
                location("file-converter", 12),
                1.0
        );
        Evidence pathEvidence = new Evidence(
                EvidenceType.DERIVATION_PATH,
                "constructor dependency path",
                source,
                target,
                location("file-converter", 12),
                0.75
        );
        return List.of(
                new Relationship(
                        "rel-calls", projectId.toString(), source, target, null,
                        RelationshipKind.CALLS, location("file-converter", 12),
                        ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null,
                        origin(), List.of(providerEvidence)
                ),
                new Relationship(
                        "rel-derived", projectId.toString(), source, target, null,
                        RelationshipKind.DEPENDS_ON, location("file-converter", 12),
                        ResolutionStatus.RESOLVED, InformationNature.DERIVED, 0.75,
                        origin(), List.of(pathEvidence, providerEvidence)
                )
        );
    }

    private static CodeEntityRef symbol(String id) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, id);
    }

    private static SymbolLocation location(String fileId, int line) {
        return new SymbolLocation(fileId, line, 1, line, 10, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static Origin origin() {
        return new Origin("fixture-provider", "TEST", "1", "run-1", OriginType.OTHER);
    }

    private static byte[] knowledgeBytes(Path root, UUID projectId) throws IOException {
        return Files.readAllBytes(knowledgeFile(root, projectId));
    }

    private static Path knowledgeFile(Path root, UUID projectId) throws IOException {
        try (var files = Files.list(root.resolve(projectId.toString()))) {
            return files.filter(path -> path.toString().endsWith(".knowledge"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}

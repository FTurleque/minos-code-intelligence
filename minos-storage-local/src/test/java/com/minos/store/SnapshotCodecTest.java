package com.minos.store;

import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotCodecTest {

    @Test
    void v1RoundTripsIndependentlyFromRepository(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "legacy",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(),
                List.of()
        );
        SnapshotCodec codec = new SnapshotCodecV1();
        Path file = root.resolve("snapshot.symbols");

        SnapshotCodec.SnapshotEncoding encoding = codec.write(file, snapshot);
        CodeKnowledgeSnapshot loaded = codec.read(file);

        assertEquals(1, codec.formatVersion());
        assertEquals(".symbols", codec.fileExtension());
        assertEquals(new SnapshotIntegrityService().checksum(file), encoding.sha256());
        assertEquals(snapshot, loaded);
    }

    @Test
    void v2RoundTripsIndependentlyFromRepository(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                "knowledge",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(),
                List.of()
        );
        SnapshotCodec codec = new SnapshotCodecV2();
        Path file = root.resolve("snapshot.knowledge");

        SnapshotCodec.SnapshotEncoding encoding = codec.write(file, snapshot);
        CodeKnowledgeSnapshot loaded = codec.read(file);

        assertEquals(2, codec.formatVersion());
        assertEquals(".knowledge", codec.fileExtension());
        assertEquals(new SnapshotIntegrityService().checksum(file), encoding.sha256());
        assertEquals(snapshot, loaded);
    }

    @Test
    void v1RejectsM3Collections(@TempDir Path root) {
        UUID projectId = UUID.randomUUID();
        SymbolOccurrence occurrence = new SymbolOccurrence(
                "occ-1",
                projectId.toString(),
                new ResolvedSymbolReference("method-int"),
                new SymbolLocation("file-converter", 8, 1, 8, 2, PositionEncoding.UTF16_CODE_UNITS),
                Set.of(OccurrenceRole.REFERENCE),
                ResolutionStatus.RESOLVED,
                new Origin("fixture-provider", "TEST", "1", "run-1", OriginType.OTHER),
                Set.of()
        );
        CodeKnowledgeSnapshot incompatible = new CodeKnowledgeSnapshot(
                projectId,
                "legacy",
                FileSymbolSnapshotStoreTest.symbols(projectId),
                List.of(occurrence),
                List.of()
        );
        SnapshotCodec codec = new SnapshotCodecV1();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> codec.write(root.resolve("snapshot.symbols"), incompatible)
        );
        assertEquals("snapshot codec v1 supports symbols only", exception.getMessage());
    }
}

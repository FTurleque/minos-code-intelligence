package com.minos.adapter.scip;

import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipSymbolSnapshotImporterIntegrityTest {

    @Test
    void hashDerivedSnapshotIdMustMatchTheFrozenArtifact(@TempDir Path temp) throws Exception {
        Path artifact = temp.resolve("index.scip");
        Files.write(artifact, new byte[]{8, 1});
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("snapshots"));
        ScipSymbolSnapshotRequest request = new ScipSymbolSnapshotRequest(
                projectId,
                "scip-" + "0".repeat(24),
                null,
                "fixture-provider",
                "1",
                "fixture-run",
                Map.of());

        assertThrows(IOException.class,
                () -> new ScipSymbolSnapshotImporter().importSnapshot(artifact, request, store));
        assertTrue(store.loadActiveKnowledge(projectId).isEmpty());
    }
}

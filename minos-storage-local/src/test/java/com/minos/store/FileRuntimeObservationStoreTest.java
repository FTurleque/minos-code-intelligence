package com.minos.store;

import com.minos.dynamic.CorrelatedRuntimeObservation;
import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservation;
import com.minos.dynamic.RuntimeObservationCompleteness;
import com.minos.dynamic.RuntimeObservationSession;
import com.minos.dynamic.RuntimeObservationType;
import com.minos.dynamic.RuntimeResolutionStatus;
import com.minos.dynamic.RuntimeSymbolReference;
import com.minos.dynamic.RuntimeSymbolResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRuntimeObservationStoreTest {

    @Test
    void persistsDeterministicallyAndTreatsTheSameSourceAsIdempotent(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        CorrelatedRuntimeSession session = session(projectId, "run-1", "a".repeat(64));
        FileRuntimeObservationStore store = new FileRuntimeObservationStore(root);

        assertFalse(store.save(session).alreadyPresent());
        assertTrue(store.save(session).alreadyPresent());
        CorrelatedRuntimeSession loaded = new FileRuntimeObservationStore(root)
                .find(projectId, "run-1").orElseThrow();

        assertEquals(session, loaded);
        assertEquals(List.of("run-1"), store.list(projectId).stream()
                .map(value -> value.session().sessionId()).toList());
    }

    @Test
    void refusesSessionIdentityMutationAndCapacityOverflow(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileRuntimeObservationStore store = new FileRuntimeObservationStore(root, 1, 1_000_000, 1_000_000);
        store.save(session(projectId, "run-1", "a".repeat(64)));

        IOException mutation = assertThrows(IOException.class,
                () -> store.save(session(projectId, "run-1", "b".repeat(64))));
        assertTrue(mutation.getMessage().contains("immutable"));
        IOException capacity = assertThrows(IOException.class,
                () -> store.save(session(projectId, "run-2", "c".repeat(64))));
        assertTrue(capacity.getMessage().contains("capacity"));
    }

    @Test
    void detectsPersistedByteTamperingBeforeDeserialization(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileRuntimeObservationStore store = new FileRuntimeObservationStore(root);
        store.save(session(projectId, "run-1", "a".repeat(64)));
        Path file;
        try (var files = Files.list(root.resolve(projectId.toString()))) {
            file = files.filter(value -> value.getFileName().toString().endsWith(".mrt"))
                    .findFirst().orElseThrow();
        }
        Files.write(file, new byte[]{0x01}, StandardOpenOption.APPEND);

        IOException corruption = assertThrows(IOException.class, () -> store.list(projectId));
        assertTrue(corruption.getMessage().contains("checksum mismatch"));
    }

    @Test
    void rejectsAProjectDirectorySymlinkInsteadOfFollowingIt(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path projectEntry = root.resolve("store").resolve(projectId.toString());
        Files.createDirectories(projectEntry.getParent());
        try {
            Files.createSymbolicLink(projectEntry, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }
        FileRuntimeObservationStore store = new FileRuntimeObservationStore(root.resolve("store"));

        IOException rejected = assertThrows(IOException.class,
                () -> store.save(session(projectId, "run-1", "a".repeat(64))));
        assertTrue(rejected.getMessage().contains("symbolic link"));
    }

    private static CorrelatedRuntimeSession session(UUID projectId, String sessionId, String sourceSha) {
        RuntimeSymbolReference reference = new RuntimeSymbolReference("key:service", null, null, null);
        RuntimeObservation observation = new RuntimeObservation(
                RuntimeObservationType.SYMBOL_EXECUTION, reference, null, 3, 100);
        RuntimeObservationSession raw = new RuntimeObservationSession(
                RuntimeObservationSession.FORMAT, sessionId, projectId, "snapshot-1",
                Instant.parse("2026-07-29T06:00:00Z"), Instant.parse("2026-07-29T06:01:00Z"),
                "fixture", "1", "test", RuntimeObservationCompleteness.PARTIAL, List.of(observation));
        RuntimeSymbolResolution resolution = new RuntimeSymbolResolution(
                RuntimeResolutionStatus.RESOLVED, reference, "symbol-1", "key:service",
                "com.acme.Service", List.of(), false);
        return new CorrelatedRuntimeSession(raw, Instant.parse("2026-07-29T07:00:00Z"), sourceSha,
                List.of(new CorrelatedRuntimeObservation(observation, resolution, null)));
    }
}

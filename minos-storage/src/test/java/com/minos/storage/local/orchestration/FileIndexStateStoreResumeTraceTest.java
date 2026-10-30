package com.minos.storage.local.orchestration;

import com.minos.orchestration.IndexingRun.ResumeTrace;
import com.minos.orchestration.IndexingRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 3 : trace de reprise persistée, optionnelle à la lecture. */
class FileIndexStateStoreResumeTraceTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T08:00:00Z");

    @TempDir
    Path root;

    @Test
    void persistsTheResumeTraceAcrossReopen() throws Exception {
        Path stateRoot = root.resolve("state");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        IndexingRun run = new IndexingRun(runId, projectId, IndexingRun.Status.SUCCEEDED, IndexingRun.Phase.COMPLETED,
                CREATED, Optional.of(CREATED.plusSeconds(5)), List.of(), Optional.of("s"), Optional.empty(),
                Optional.of("s"), Optional.of("done"), IndexingRun.CURRENT_FORMAT_VERSION,
                Optional.of(new ResumeTrace(2, 5, 3, Optional.empty())));
        IndexingRun refused = new IndexingRun(UUID.randomUUID(), projectId, IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED, CREATED, Optional.of(CREATED.plusSeconds(5)), List.of(), Optional.of("s"),
                Optional.empty(), Optional.of("s"), Optional.of("done"), IndexingRun.CURRENT_FORMAT_VERSION,
                Optional.of(new ResumeTrace(1, 0, 8, Optional.of("checkpoint older than resume TTL"))));

        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveRun(run);
        store.saveRun(refused);
        FileIndexStateStore reopened = new FileIndexStateStore(stateRoot);

        assertEquals(run, reopened.findRun(runId).orElseThrow());
        assertEquals(refused, reopened.findRun(refused.id()).orElseThrow());
        assertTrue(Files.readString(stateRoot.resolve("runs").resolve(projectId.toString())
                .resolve(runId + ".properties")).contains("resume.attempt=2"));
    }

    @Test
    void runWithoutTraceKeysHasNoTraceAndCompatibilityConstructorNeither() {
        IndexingRun run = new IndexingRun(UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.RUNNING,
                IndexingRun.Phase.PROVIDER_EXECUTION, CREATED, Optional.empty(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), IndexingRun.CURRENT_FORMAT_VERSION);

        assertEquals(Optional.empty(), run.resume());
        assertThrows(IllegalArgumentException.class, () -> new ResumeTrace(0, 0, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ResumeTrace(1, -1, 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ResumeTrace(1, 0, 0, Optional.of(" ")));
    }
}

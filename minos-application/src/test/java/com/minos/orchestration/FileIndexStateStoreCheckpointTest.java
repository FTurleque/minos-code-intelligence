package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 1 : persistance des points de contrôle et compatibilité ascendante (ADR 0039 §1, lot 1). */
class FileIndexStateStoreCheckpointTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String SHA_D = "d".repeat(64);
    private static final Instant CREATED = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2026-09-26T08:05:00Z");

    @TempDir
    Path root;

    @Test
    void persistsEveryCheckpointFieldAndTheRunFormatVersionAcrossReopen() throws Exception {
        Path stateRoot = root.resolve("state");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path rootArtifact = root.resolve("runs/r/scip-java/index.scip").toAbsolutePath().normalize();
        Path scopedArtifact = root.resolve("runs/r/scip-typescript/scopes/module-abc/index.scip")
                .toAbsolutePath().normalize();
        IndexingRun run = new IndexingRun(
                runId, projectId, IndexingRun.Status.RUNNING, IndexingRun.Phase.STAGING, CREATED,
                Optional.empty(),
                List.of(
                        new IndexerExecution(Language.JAVA, "scip-java", rootArtifact, Optional.of(
                                new ExecutionCheckpoint(Path.of(""), "0.10.0", 1_234L, SHA_A, SHA_B,
                                        IndexingMode.FULL, List.of(), COMPLETED))),
                        new IndexerExecution(Language.TYPESCRIPT, "scip-typescript", scopedArtifact, Optional.of(
                                new ExecutionCheckpoint(Path.of("ui/app"), "0.4.0", 77L, SHA_C, SHA_D,
                                        IndexingMode.INCREMENTAL, List.of("src/a.ts", "src/b.ts"),
                                        COMPLETED.plusSeconds(1)))),
                        new IndexerExecution(Language.GO, "scip-go", root.resolve("go.scip").toAbsolutePath().normalize())),
                Optional.of("snapshot-staged"), Optional.of("snapshot-old"), Optional.of("snapshot-old"),
                Optional.of("staging"), IndexingRun.CURRENT_FORMAT_VERSION);

        new FileIndexStateStore(stateRoot).saveRun(run);
        IndexingRun reloaded = new FileIndexStateStore(stateRoot).findRun(runId).orElseThrow();

        assertEquals(run, reloaded);
        assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, reloaded.runFormatVersion());
        assertEquals(Path.of("ui/app"), reloaded.executions().get(1).checkpoint().orElseThrow().projectRelativeRoot());
        assertTrue(reloaded.executions().get(2).checkpoint().isEmpty(),
                "an execution recorded without checkpoint stays without checkpoint");
        String persisted = Files.readString(stateRoot.resolve("runs").resolve(projectId.toString())
                .resolve(runId + ".properties"));
        assertTrue(persisted.contains("runFormatVersion=" + IndexingRun.CURRENT_FORMAT_VERSION), persisted);
        assertTrue(persisted.contains("execution.1.scope=ui/app"), "scope must be persisted portably");
    }

    @Test
    void runWrittenByThePreviousVersionStaysReadableAndIsNeverResumable() throws Exception {
        Path stateRoot = root.resolve("legacy");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FileIndexStateStore initial = new FileIndexStateStore(stateRoot);
        Path file = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        Files.createDirectories(file.getParent());
        // Exact key set written by FileIndexStateStore before R1 lot 1 (no format version, no checkpoint).
        Files.writeString(file, """
                id=%s
                projectId=%s
                status=SUCCEEDED
                phase=COMPLETED
                createdAt=2026-09-01T10:00:00Z
                completedAt=2026-09-01T10:03:00Z
                stagedSnapshotId=snapshot-7
                activeSnapshotBefore=
                activeSnapshotAfter=snapshot-7
                message=completed
                execution.count=1
                execution.0.language=JAVA
                execution.0.indexerId=scip-java
                execution.0.artifact=%s
                """.formatted(runId, projectId, root.resolve("legacy.scip").toAbsolutePath().normalize()
                .toString().replace("\\", "\\\\")));
        Files.writeString(stateRoot.resolve("runs/.by-id").resolve(runId + ".properties"),
                "projectId=" + projectId + "\n");

        IndexingRun run = initial.findRun(runId).orElseThrow();

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(IndexingRun.LEGACY_FORMAT_VERSION, run.runFormatVersion());
        assertEquals(1, run.executions().size());
        assertTrue(run.executions().getFirst().checkpoint().isEmpty());
        assertEquals(List.of(runId), initial.listRuns(projectId).stream().map(IndexingRun::id).toList());

        // Re-saving a legacy run must not silently promote it to the current format.
        initial.saveRun(run);
        IndexingRun resaved = new FileIndexStateStore(stateRoot).findRun(runId).orElseThrow();
        assertEquals(IndexingRun.LEGACY_FORMAT_VERSION, resaved.runFormatVersion());
        assertTrue(resaved.executions().getFirst().checkpoint().isEmpty());
    }

    @Test
    void incompleteOrInvalidCheckpointKeepsTheRunReadableButNeverResumable() throws Exception {
        Path stateRoot = root.resolve("corrupt");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveRun(new IndexingRun(
                runId, projectId, IndexingRun.Status.RUNNING, IndexingRun.Phase.PROVIDER_EXECUTION, CREATED,
                Optional.empty(),
                List.of(new IndexerExecution(Language.JAVA, "scip-java",
                        root.resolve("a.scip").toAbsolutePath().normalize(), Optional.of(
                        new ExecutionCheckpoint(Path.of(""), "0.10.0", 5L, SHA_A, SHA_B,
                                IndexingMode.FULL, List.of(), COMPLETED)))),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                IndexingRun.CURRENT_FORMAT_VERSION));
        Path file = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        String content = Files.readString(file);
        assertTrue(content.contains("execution.0.artifactSha256="), content);

        Files.writeString(file, content.replace("execution.0.artifactSha256=" + SHA_A, ""));
        IndexingRun missingField = store.findRun(runId).orElseThrow();
        assertEquals(IndexingRun.Status.RUNNING, missingField.status());
        assertTrue(missingField.executions().getFirst().checkpoint().isEmpty(),
                "a checkpoint missing one field is dropped, never partially trusted");

        Files.writeString(file, content.replace("execution.0.artifactSha256=" + SHA_A,
                "execution.0.artifactSha256=not-a-digest"));
        IndexingRun invalidField = store.findRun(runId).orElseThrow();
        assertTrue(invalidField.executions().getFirst().checkpoint().isEmpty(),
                "a checkpoint with invalid material is dropped, never partially trusted");
        assertEquals(List.of(runId), store.listRuns(projectId).stream().map(IndexingRun::id).toList());
    }

    @Test
    void unparseableRunFormatVersionDegradesToLegacyInsteadOfBlockingTheRunListing() throws Exception {
        // V4: a corrupt version must never make listRuns throw, which would block every new run.
        Path stateRoot = root.resolve("bad-version");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveRun(new IndexingRun(
                runId, projectId, IndexingRun.Status.RUNNING, IndexingRun.Phase.PROVIDER_EXECUTION, CREATED,
                Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                IndexingRun.CURRENT_FORMAT_VERSION));
        Path file = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        String content = Files.readString(file);

        Files.writeString(file, content.replace("runFormatVersion=2", "runFormatVersion=abc"));
        assertEquals(IndexingRun.LEGACY_FORMAT_VERSION, store.findRun(runId).orElseThrow().runFormatVersion());
        Files.writeString(file, content.replace("runFormatVersion=2", "runFormatVersion=0"));
        assertEquals(IndexingRun.LEGACY_FORMAT_VERSION, store.findRun(runId).orElseThrow().runFormatVersion());
        assertEquals(List.of(runId), store.listRuns(projectId).stream().map(IndexingRun::id).toList());
    }

    @Test
    void checkpointsBeyondTheWriteCapAreDroppedWhileLargeLegacyRunsStayReadable() throws Exception {
        // V5: the run file stays below the property budget by dropping checkpoints past the cap, and
        // a historical run of up to 10 000 checkpoint-less executions is still read (not resumable).
        Path stateRoot = root.resolve("cap");
        UUID projectId = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        ExecutionCheckpoint checkpoint = new ExecutionCheckpoint(Path.of(""), "1", 1L, SHA_A, SHA_B,
                IndexingMode.FULL, List.of(), COMPLETED);
        List<IndexerExecution> checkpointed = new java.util.ArrayList<>();
        for (int index = 0; index <= FileIndexStateStore.MAX_CHECKPOINTED_EXECUTIONS; index++) {
            checkpointed.add(new IndexerExecution(Language.JAVA, "scip-java", Path.of("a" + index), Optional.of(checkpoint)));
        }
        UUID cappedId = UUID.randomUUID();
        store.saveRun(new IndexingRun(cappedId, projectId, IndexingRun.Status.RUNNING,
                IndexingRun.Phase.PROVIDER_EXECUTION, CREATED, Optional.empty(), checkpointed, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), IndexingRun.CURRENT_FORMAT_VERSION));
        IndexingRun capped = store.findRun(cappedId).orElseThrow();
        assertEquals(checkpointed.size(), capped.executions().size(), "every execution is written");
        assertTrue(capped.executions().get(FileIndexStateStore.MAX_CHECKPOINTED_EXECUTIONS - 1).checkpoint().isPresent());
        assertTrue(capped.executions().getLast().checkpoint().isEmpty(), "the execution past the cap has no checkpoint");

        List<IndexerExecution> plain = new java.util.ArrayList<>();
        for (int index = 0; index < FileIndexStateStore.MAX_READABLE_EXECUTIONS; index++) {
            plain.add(new IndexerExecution(Language.JAVA, "scip-java", Path.of("b" + index)));
        }
        UUID largeId = UUID.randomUUID();
        store.saveRun(new IndexingRun(largeId, projectId, IndexingRun.Status.FAILED, IndexingRun.Phase.PROVIDER_EXECUTION,
                CREATED, Optional.of(COMPLETED), plain, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("old"), IndexingRun.LEGACY_FORMAT_VERSION));
        assertEquals(plain.size(), store.findRun(largeId).orElseThrow().executions().size());
    }

    @Test
    void inMemoryStoreKeepsCheckpointsVerbatim() {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        UUID runId = UUID.randomUUID();
        IndexingRun run = new IndexingRun(
                runId, UUID.randomUUID(), IndexingRun.Status.RUNNING, IndexingRun.Phase.PROVIDER_EXECUTION,
                CREATED, Optional.empty(),
                List.of(new IndexerExecution(Language.JAVA, "scip-java", Path.of("index.scip"), Optional.of(
                        new ExecutionCheckpoint(Path.of("core"), "0.10.0", 9L, SHA_A, SHA_B,
                                IndexingMode.FULL, List.of(), COMPLETED)))),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                IndexingRun.CURRENT_FORMAT_VERSION);

        store.saveRun(run);

        assertEquals(run, store.findRun(runId).orElseThrow());
    }
}

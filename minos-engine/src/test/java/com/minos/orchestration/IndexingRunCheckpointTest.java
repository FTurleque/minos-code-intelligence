package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 1 : modèle des points de contrôle par cible (ADR 0039 §1). */
class IndexingRunCheckpointTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final Instant COMPLETED = Instant.parse("2026-09-26T10:00:00Z");

    @Test
    void compatibilityConstructorsDescribeALegacyRunThatIsNeverResumable() {
        IndexerExecution execution = new IndexerExecution(Language.JAVA, "scip-java", Path.of("index.scip"));
        IndexingRun run = new IndexingRun(
                UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.SUCCEEDED, IndexingRun.Phase.COMPLETED,
                COMPLETED, Optional.of(COMPLETED), List.of(execution), Optional.of("s"), Optional.empty(),
                Optional.of("s"), Optional.of("legacy"));

        assertTrue(execution.checkpoint().isEmpty(), "a legacy execution carries no checkpoint");
        assertEquals(IndexingRun.LEGACY_FORMAT_VERSION, run.runFormatVersion());
        assertTrue(IndexingRun.LEGACY_FORMAT_VERSION < IndexingRun.CURRENT_FORMAT_VERSION);
    }

    @Test
    void currentFormatRunCarriesACompleteCheckpointPerExecution() {
        ExecutionCheckpoint checkpoint = fullCheckpoint(Path.of("ui/app"));
        IndexerExecution execution = new IndexerExecution(
                Language.TYPESCRIPT, "scip-typescript", Path.of("index.scip"), Optional.of(checkpoint));
        IndexingRun run = new IndexingRun(
                UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.RUNNING,
                IndexingRun.Phase.PROVIDER_EXECUTION, COMPLETED, Optional.empty(), List.of(execution),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                IndexingRun.CURRENT_FORMAT_VERSION);

        assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, run.runFormatVersion());
        assertEquals(Optional.of(checkpoint), run.executions().getFirst().checkpoint());
        assertEquals(Path.of("ui/app"), checkpoint.projectRelativeRoot());
        assertEquals("0.4.0", checkpoint.providerVersion());
        assertEquals(42L, checkpoint.artifactBytes());
        assertEquals(SHA_A, checkpoint.artifactSha256());
        assertEquals(SHA_B, checkpoint.scopeFingerprint());
        assertEquals(IndexingMode.FULL, checkpoint.mode());
        assertEquals(List.of(), checkpoint.changedFiles());
        assertEquals(COMPLETED, checkpoint.completedAt());
    }

    @Test
    void checkpointNormalizesHexDigestsAndRejectsInvalidMaterial() {
        ExecutionCheckpoint upper = new ExecutionCheckpoint(Path.of(""), "1", 1L,
                SHA_A.toUpperCase(java.util.Locale.ROOT), SHA_B, IndexingMode.FULL, List.of(), COMPLETED);
        assertEquals(SHA_A, upper.artifactSha256());

        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, "not-a-digest", SHA_B, IndexingMode.FULL, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, SHA_A, "zz" + SHA_B.substring(2), IndexingMode.FULL, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), " ", 1L, SHA_A, SHA_B, IndexingMode.FULL, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 0L, SHA_A, SHA_B, IndexingMode.FULL, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES + 1L, SHA_A, SHA_B,
                IndexingMode.FULL, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of("../outside"), "1", 1L, SHA_A, SHA_B, IndexingMode.FULL, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of("").toAbsolutePath(), "1", 1L, SHA_A, SHA_B, IndexingMode.FULL, List.of(), COMPLETED));
    }

    @Test
    void checkpointModeAndChangedFilesMustAgree() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, SHA_A, SHA_B, IndexingMode.NONE, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, SHA_A, SHA_B, IndexingMode.FULL, List.of("src/App.java"), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, SHA_A, SHA_B, IndexingMode.INCREMENTAL, List.of(), COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, SHA_A, SHA_B, IndexingMode.INCREMENTAL,
                List.of("src/B.java", "src/A.java"), COMPLETED), "changed files must be strictly sorted");
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, SHA_A, SHA_B, IndexingMode.INCREMENTAL,
                List.of("src\\A.java"), COMPLETED), "changed files must be portable");

        ExecutionCheckpoint incremental = new ExecutionCheckpoint(
                Path.of("mod"), "1", 1L, SHA_A, SHA_B, IndexingMode.INCREMENTAL,
                List.of("src/A.java", "src/B.java"), COMPLETED);
        assertEquals(List.of("src/A.java", "src/B.java"), incremental.changedFiles());
    }

    @Test
    void checkpointBoundsTheChangedFileListItPersists() {
        List<String> tooMany = java.util.stream.IntStream.range(0, ExecutionCheckpoint.MAX_CHANGED_FILES + 1)
                .mapToObj(index -> String.format("src/F%06d.java", index)).toList();
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCheckpoint(
                Path.of(""), "1", 1L, SHA_A, SHA_B, IndexingMode.INCREMENTAL, tooMany, COMPLETED));
    }

    @Test
    void targetKeyIsStablePortableAndSensitiveToEveryComponent() {
        String key = IndexingRun.targetKey("scip-java", "0.10.0", Path.of("services/billing"));

        assertTrue(key.matches("[0-9a-f]{64}"));
        assertEquals(key, IndexingRun.targetKey("scip-java", "0.10.0", Path.of("services", "billing")));
        assertEquals(key, IndexingRun.targetKey("scip-java", "0.10.0", Path.of("services/./billing")));
        assertNotEquals(key, IndexingRun.targetKey("scip-java", "0.11.0", Path.of("services/billing")));
        assertNotEquals(key, IndexingRun.targetKey("scip-kotlin", "0.10.0", Path.of("services/billing")));
        assertNotEquals(key, IndexingRun.targetKey("scip-java", "0.10.0", Path.of("")));
    }

    @Test
    void runFormatVersionMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new IndexingRun(
                UUID.randomUUID(), UUID.randomUUID(), IndexingRun.Status.RUNNING,
                IndexingRun.Phase.PROVIDER_EXECUTION, COMPLETED, Optional.empty(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0));
    }

    private static ExecutionCheckpoint fullCheckpoint(Path relativeRoot) {
        return new ExecutionCheckpoint(relativeRoot, "0.4.0", 42L, SHA_A, SHA_B, IndexingMode.FULL, List.of(), COMPLETED);
    }
}

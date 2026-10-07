package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q13 — caractérise la copie de {@code requireText} propre à {@code IndexingRunExecutor} avant la mutualisation :
 * c'est la seule à lever une {@link IllegalStateException} (un port de mise en scène qui rend un identifiant blanc
 * est un état interne invalide, pas un argument), et le run persisté porte le nom de cette classe dans son message.
 */
class StagedSnapshotIdValidationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void aBlankStagedSnapshotIdFailsTheRunAsAnIllegalState(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        Path artifact = Files.writeString(root.resolve("index.scip"), "index");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(new IndexingRuntimePorts.IndexerExecutor() {
                    public String indexerId() { return "java-indexer"; }
                    public IndexingRuntimePorts.IndexingArtifact execute(
                            IndexingRuntimePorts.IndexingExecutionRequest request) {
                        return new IndexingRuntimePorts.IndexingArtifact(Language.JAVA, "java-indexer", artifact);
                    }
                }),
                request -> " ",
                (id, runId, snapshotId) -> { },
                store,
                CLOCK
        );

        IndexingRun result = service.execute(projectId, root,
                new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of()));

        assertEquals(IndexingRun.Status.FAILED, result.status());
        assertTrue(result.message().orElseThrow()
                        .contains("IllegalStateException: stagedSnapshotId must not be blank"),
                result.message().orElseThrow());
    }

    private static IndexerSelection selection() {
        IndexerDescriptor descriptor = new IndexerDescriptor("java-indexer", "1", "java-indexer",
                Set.of(Language.JAVA), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerSelection(Language.JAVA, descriptor);
    }
}

package com.minos.cli;

import com.minos.semantic.SemanticIndexService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalStatusCommandTest {

    @Test
    void semanticStatusExposesPersistentVectorStateAsJson() throws Exception {
        SemanticIndexService.Status status = new SemanticIndexService.Status(
                "project-1", "demo", SemanticIndexService.State.READY,
                "snapshot-1", "snapshot-1", "local-hash", "local-hash-v1",
                64, 42, 4096L, List.of("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL"));
        RetrievalStatusCommand command = new RetrievalStatusCommand(
                RetrievalStatusCommand.Mode.SEMANTIC, ignored -> status);
        StringBuilder output = new StringBuilder();

        int exitCode = command.run(new String[]{"status", "demo", "--format", "json"},
                output, new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertTrue(output.toString().contains("\"state\":\"READY\""));
        assertTrue(output.toString().contains("\"semanticAvailable\":true"));
        assertTrue(output.toString().contains("\"documentCount\":42"));
    }

    @Test
    void hybridStatusIsReadyWithStructuredFallbackWhenSemanticIsDisabled() throws Exception {
        SemanticIndexService.Status status = new SemanticIndexService.Status(
                "project-1", "demo", SemanticIndexService.State.DISABLED,
                "snapshot-1", null, null, null, 0, 0, 0L,
                List.of("SEMANTIC_EMBEDDING_PROVIDER_UNAVAILABLE"));
        RetrievalStatusCommand command = new RetrievalStatusCommand(
                RetrievalStatusCommand.Mode.HYBRID, ignored -> status);
        StringBuilder output = new StringBuilder();

        int exitCode = command.run(new String[]{"status", "demo"}, output, new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertTrue(output.toString().contains("state: READY_STRUCTURED_FALLBACK"));
        assertTrue(output.toString().contains("semanticAvailable: false"));
        assertTrue(output.toString().contains("SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED"));
    }

    @Test
    void hybridStatusRequiresAnActiveSnapshot() throws Exception {
        SemanticIndexService.Status status = new SemanticIndexService.Status(
                "project-1", "demo", SemanticIndexService.State.NO_ACTIVE_SNAPSHOT,
                null, null, "local-hash", "local-hash-v1", 64, 0, 0L,
                List.of("ACTIVE_KNOWLEDGE_SNAPSHOT_UNAVAILABLE"));
        RetrievalStatusCommand command = new RetrievalStatusCommand(
                RetrievalStatusCommand.Mode.HYBRID, ignored -> status);
        StringBuilder output = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS,
                command.run(new String[]{"status", "demo"}, output, new StringBuilder()));
        assertTrue(output.toString().contains("state: NO_ACTIVE_SNAPSHOT"));
    }

    @Test
    void helpDoesNotNeedApplicationState() throws Exception {
        StringBuilder output = new StringBuilder();

        int exitCode = MinosCliRunner.runStatelessHelp(
                new String[]{"semantic", "status", "--help"}, output, new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertTrue(output.toString().contains("semantic status <project>"));
    }
}

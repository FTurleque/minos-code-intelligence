package com.minos.adapter.scip;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.io.CommitUncertainException;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Index;
import org.scip_code.scip.Metadata;
import org.scip_code.scip.ToolInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipSymbolSnapshotCommitRecoveryTest {

    @Test
    void activeTargetAfterLostPublishAcknowledgementReturnsCommittedDurabilityPending(@TempDir Path root)
            throws Exception {
        Path indexFile = root.resolve("index.scip");
        Index index = Index.newBuilder()
                .setMetadata(Metadata.newBuilder()
                        .setProjectRoot("file:///fixture")
                        .setToolInfo(ToolInfo.newBuilder().setName("fixture").setVersion("1")))
                .build();
        try (var output = Files.newOutputStream(indexFile)) {
            index.writeTo(output);
        }
        UUID projectId = UUID.randomUUID();
        UncertainAfterCommitStore store = new UncertainAfterCommitStore();

        ScipSymbolSnapshotReport report = new ScipSymbolSnapshotImporter().importSnapshot(
                indexFile,
                new ScipSymbolSnapshotRequest(
                        projectId, "snapshot-target", null, "scip-java", "1", "run", Map.of()),
                store);

        assertEquals(ScipSymbolSnapshotReport.CommitStatus.COMMITTED_DURABILITY_PENDING, report.commitStatus());
        assertTrue(report.commitDiagnostic().contains("acknowledgement"));
        assertEquals("snapshot-target", store.active.orElseThrow().snapshotId());
    }

    private static final class UncertainAfterCommitStore implements CodeKnowledgeSnapshotStore {
        private Optional<CodeKnowledgeSnapshot> active = Optional.empty();

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) throws IOException {
            CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, snapshotId,
                    symbols.stream().toList(), java.util.List.of(), java.util.List.of());
            active = Optional.of(snapshot);
            throw new CommitUncertainException("synthetic committed publication");
        }

        @Override
        public CodeKnowledgeSnapshot publish(
                UUID projectId,
                String snapshotId,
                Collection<Symbol> symbols,
                Collection<SymbolOccurrence> occurrences,
                Collection<Relationship> relationships
        ) throws IOException {
            CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, snapshotId,
                    symbols.stream().toList(), occurrences.stream().toList(), relationships.stream().toList());
            active = Optional.of(snapshot);
            throw new CommitUncertainException("synthetic committed publication");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            return active.map(snapshot -> new SymbolSnapshot(projectId, snapshot.snapshotId(), snapshot.symbols()));
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            return active;
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return Optional.empty();
        }
    }
}

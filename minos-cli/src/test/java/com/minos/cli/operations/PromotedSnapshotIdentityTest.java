package com.minos.cli.operations;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.orchestration.IndexingRun;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * La reprise d'un run dont le snapshot préparé a été promu ne lit que l'identité du snapshot actif : le snapshot
 * n'est jamais chargé.
 */
class PromotedSnapshotIdentityTest {

    private static final UUID PROJECT = UUID.randomUUID();

    @Test
    void unRunEchoueDontLeSnapshotPrepareEstActifEstReconnuSansChargement() throws Exception {
        CountingSnapshotStore snapshots = new CountingSnapshotStore(Optional.of("snapshot-promoted"));

        assertEquals(Optional.of("snapshot-promoted"),
                LocalAutonomousIndexOperations.promotedSnapshotId(failed("snapshot-promoted"), snapshots));
        assertEquals(0, snapshots.loads);
        assertEquals(1, snapshots.identityReads);
    }

    @Test
    void unRunDontLeSnapshotPrepareNEstPasActifNEstPasRepris() throws Exception {
        CountingSnapshotStore otherActive = new CountingSnapshotStore(Optional.of("snapshot-other"));
        CountingSnapshotStore noActive = new CountingSnapshotStore(Optional.empty());

        assertEquals(Optional.empty(),
                LocalAutonomousIndexOperations.promotedSnapshotId(failed("snapshot-promoted"), otherActive));
        assertEquals(Optional.empty(),
                LocalAutonomousIndexOperations.promotedSnapshotId(failed("snapshot-promoted"), noActive));
        assertEquals(0, otherActive.loads + noActive.loads);
    }

    @Test
    void unRunReussiOuSansSnapshotPrepareNeLitRien() throws Exception {
        CountingSnapshotStore snapshots = new CountingSnapshotStore(Optional.of("snapshot-promoted"));
        Instant at = Instant.parse("2026-10-15T08:00:00Z");
        IndexingRun succeeded = new IndexingRun(UUID.randomUUID(), PROJECT, IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED, at, Optional.of(at), List.of(), Optional.of("snapshot-promoted"),
                Optional.empty(), Optional.of("snapshot-promoted"), Optional.empty());
        IndexingRun unstaged = new IndexingRun(UUID.randomUUID(), PROJECT, IndexingRun.Status.FAILED,
                IndexingRun.Phase.PROVIDER_EXECUTION, at, Optional.of(at), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("provider failed"));

        assertEquals(Optional.empty(), LocalAutonomousIndexOperations.promotedSnapshotId(succeeded, snapshots));
        assertEquals(Optional.empty(), LocalAutonomousIndexOperations.promotedSnapshotId(unstaged, snapshots));
        assertEquals(0, snapshots.identityReads + snapshots.loads);
    }

    private static IndexingRun failed(String stagedSnapshotId) {
        Instant at = Instant.parse("2026-10-15T08:00:00Z");
        return new IndexingRun(UUID.randomUUID(), PROJECT, IndexingRun.Status.FAILED, IndexingRun.Phase.PROMOTION,
                at, Optional.of(at), List.of(), Optional.of(stagedSnapshotId), Optional.empty(), Optional.empty(),
                Optional.of("metadata finalization failed"));
    }

    private static final class CountingSnapshotStore implements CodeKnowledgeSnapshotStore {
        private final Optional<String> activeSnapshotId;
        private int loads;
        private int identityReads;

        CountingSnapshotStore(Optional<String> activeSnapshotId) {
            this.activeSnapshotId = activeSnapshotId;
        }

        @Override
        public Optional<String> activeSnapshotId(UUID projectId) {
            identityReads++;
            return activeSnapshotId;
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            loads++;
            return activeSnapshotId.map(id -> new CodeKnowledgeSnapshot(projectId, id, List.of(), List.of(), List.of()));
        }

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("la publication n'est pas utilisée par ce test");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("la publication n'est pas utilisée par ce test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            throw new UnsupportedOperationException("les snapshots de symboles hérités ne sont pas utilisés");
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            throw new UnsupportedOperationException("les vues de requête ne sont pas utilisées par ce test");
        }
    }
}

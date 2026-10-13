package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AUD-PERF-01 : lecture par défaut de l'identifiant du snapshot actif, pour un store qui ne la redéfinit pas.
 * Elle rend l'identifiant du snapshot que le store chargerait, rien sans snapshot actif, et ne masque pas un
 * échec de chargement.
 */
class CodeKnowledgeSnapshotStoreActiveIdFallbackTest {

    private static final UUID PROJECT = UUID.randomUUID();

    @Test
    void theActiveIdOfAStoreThatOnlyLoadsIsTheIdOfTheSnapshotItLoads() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(
                new CodeKnowledgeSnapshot(PROJECT, "snapshot-7", List.of(), List.of(), List.of()));

        assertEquals(Optional.of("snapshot-7"), store.activeSnapshotId(PROJECT));
        assertEquals(store.loadActiveKnowledge(PROJECT).map(CodeKnowledgeSnapshot::snapshotId),
                store.activeSnapshotId(PROJECT));
    }

    @Test
    void theActiveIdIsReadForTheProjectThatWasAsked() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(
                new CodeKnowledgeSnapshot(PROJECT, "snapshot-7", List.of(), List.of(), List.of()));

        store.activeSnapshotId(PROJECT);

        assertEquals(List.of(PROJECT), store.loadedProjects);
    }

    @Test
    void aProjectWithoutAnActiveSnapshotHasNoActiveId() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(null);

        assertEquals(Optional.empty(), store.activeSnapshotId(PROJECT));
    }

    @Test
    void aFailedLoadIsNotReportedAsAnAbsentSnapshot() {
        LoadOnlyStore store = new LoadOnlyStore(null);
        store.failure = new IOException("active snapshot unreadable");

        IOException failure = assertThrows(IOException.class, () -> store.activeSnapshotId(PROJECT));

        assertEquals("active snapshot unreadable", failure.getMessage());
    }

    /** N'implémente que ce que l'interface exige : l'identifiant actif vient de la méthode par défaut. */
    private static final class LoadOnlyStore implements CodeKnowledgeSnapshotStore {
        private final CodeKnowledgeSnapshot active;
        private final List<UUID> loadedProjects = new ArrayList<>();
        private IOException failure;

        LoadOnlyStore(CodeKnowledgeSnapshot active) {
            this.active = active;
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
            if (failure != null) throw failure;
            loadedProjects.add(projectId);
            return Optional.ofNullable(active);
        }

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            throw new UnsupportedOperationException("legacy symbol snapshots are not used by this test");
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            throw new UnsupportedOperationException("query views are not used by this test");
        }
    }
}

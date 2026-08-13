package com.minos.store;

import com.minos.domain.Symbol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeKnowledgeSnapshotInvariantTest {
    @Test
    void duplicateSymbolIdsAreRejected() {
        UUID projectId = UUID.randomUUID();
        Symbol symbol = FileSymbolSnapshotStoreTest.symbols(projectId).getFirst();
        assertThrows(IllegalArgumentException.class, () -> new CodeKnowledgeSnapshot(
                projectId, "duplicate", List.of(symbol, symbol), List.of(), List.of()));
    }
}

package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticIndexBudgetTest {

    @Test
    void rejectsDocumentCountDuringConstruction() throws Exception {
        SemanticIndexBudget.Tracker tracker = new SemanticIndexBudget(2, 1024, 1024).tracker(4);
        tracker.account(document("a", "one"));
        tracker.account(document("b", "two"));
        assertEquals(2, tracker.documents());
        assertThrows(IOException.class, () -> tracker.account(document("c", "three")));
    }

    @Test
    void accountsActualDoubleArrayHeapWidth() throws Exception {
        SemanticIndexBudget.Tracker tracker = new SemanticIndexBudget(10, 1024, 32).tracker(4);
        tracker.account(document("a", "one"));
        assertEquals(32, tracker.vectorBytes());
        assertThrows(IOException.class, () -> tracker.account(document("b", "two")));
    }

    private static SemanticDocument document(String key, String content) {
        return new SemanticDocument(
                "id-" + key, key, "project", "snapshot", SemanticDocumentKind.SYMBOL,
                "source-" + key, null, 0, 0, content, "checksum-" + key);
    }
}

package com.minos.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceBudgetPolicyTest {

    @Test
    void acceptsExactBoundaryAndRejectsNextFile() throws Exception {
        SourceBudgetPolicy.Tracker tracker = new SourceBudgetPolicy(2, 10).tracker("test");
        tracker.accountRegularFile(4);
        tracker.accountRegularFile(6);

        assertEquals(2, tracker.files());
        assertEquals(10, tracker.bytes());
        assertThrows(IOException.class, () -> tracker.accountRegularFile(0));
    }

    @Test
    void accountsBytesAsTheyAreConsumed() throws Exception {
        SourceBudgetPolicy.Tracker tracker = new SourceBudgetPolicy(2, 5).tracker("test");
        tracker.accountFile();
        tracker.accountBytes(3);
        tracker.accountBytes(2);
        assertEquals(5, tracker.bytes());
        assertThrows(IOException.class, () -> tracker.accountBytes(1));
    }

    @Test
    void boundsTraversalEvenWhenEntriesAreIgnored() throws Exception {
        SourceBudgetPolicy.Tracker tracker = new SourceBudgetPolicy(1, 1).tracker("test");
        for (int index = 0; index < 8; index++) tracker.accountTraversalEntry();
        assertEquals(8, tracker.traversalEntries());
        assertThrows(IOException.class, tracker::accountTraversalEntry);
    }
}

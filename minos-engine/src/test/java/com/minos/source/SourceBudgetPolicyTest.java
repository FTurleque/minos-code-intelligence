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
    void rejectsByteOverflowBeforeFurtherWork() throws Exception {
        SourceBudgetPolicy.Tracker tracker = new SourceBudgetPolicy(10, 10).tracker("test");
        tracker.accountRegularFile(10);
        assertThrows(IOException.class, () -> tracker.accountRegularFile(1));
    }
}

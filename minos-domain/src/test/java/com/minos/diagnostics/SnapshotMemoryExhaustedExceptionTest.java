package com.minos.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The public refusal of a snapshot load for lack of memory: an I/O error whose message always says so first. */
class SnapshotMemoryExhaustedExceptionTest {

    @Test
    void itIsAnIoExceptionThatKeepsItsPublicMessage() {
        String message = SnapshotMemoryExhaustedException.MESSAGE_PREFIX
                + ": its view needs an estimated 10 bytes and the view memory budget is 5 bytes";

        SnapshotMemoryExhaustedException refusal = new SnapshotMemoryExhaustedException(message);

        assertInstanceOf(IOException.class, refusal);
        assertEquals(message, refusal.getMessage());
        assertFalse(PublicErrorMessages.looksSensitive(refusal.getMessage()));
    }

    @Test
    void theMessageMustStartWithThePublicPrefix() {
        assertEquals("insufficient memory to load this snapshot", SnapshotMemoryExhaustedException.MESSAGE_PREFIX);
        assertThrows(IllegalArgumentException.class, () -> new SnapshotMemoryExhaustedException("out of memory"));
        assertThrows(NullPointerException.class, () -> new SnapshotMemoryExhaustedException(null));
    }
}

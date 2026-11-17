package com.minos.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public refusal of a snapshot load for lack of scratch space: two byte counts and the setting, no path. */
class SnapshotScratchSpaceExhaustedExceptionTest {

    @Test
    void itIsAnIoExceptionWhoseMessageGivesBothSizesAndTheSetting() {
        SnapshotScratchSpaceExhaustedException refusal = new SnapshotScratchSpaceExhaustedException(300L, 200L);

        assertInstanceOf(IOException.class, refusal);
        assertTrue(refusal.getMessage().startsWith("insufficient scratch space to load this snapshot: "),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("300 bytes"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("200 bytes available"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(SnapshotScratchSpaceExhaustedException.DOCKER_SETTING));
        assertFalse(PublicErrorMessages.looksSensitive(refusal.getMessage()), refusal.getMessage());
        assertEquals(300L, refusal.payloadBytes());
        assertEquals(200L, refusal.availableBytes());
    }

    @Test
    void negativeSizesAreReportedAsZero() {
        SnapshotScratchSpaceExhaustedException refusal = new SnapshotScratchSpaceExhaustedException(-1L, -5L);

        assertEquals(0L, refusal.payloadBytes());
        assertEquals(0L, refusal.availableBytes());
        assertTrue(refusal.getMessage().contains("needs 0 bytes"), refusal.getMessage());
    }
}

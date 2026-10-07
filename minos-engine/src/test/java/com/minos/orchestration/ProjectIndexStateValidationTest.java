package com.minos.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Q13 — caractérise le message fixe de {@code ProjectIndexState.requireText} (« text value must not be blank »,
 * sans nom de champ) avant la mutualisation : le même texte sert au snapshot actif et au détail.
 */
class ProjectIndexStateValidationTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    @Test
    void aBlankActiveSnapshotIdUsesTheFixedMessage() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ProjectIndexState(
                UUID.randomUUID(), ProjectIndexState.Availability.READY, Optional.of(" "),
                Optional.empty(), NOW, Optional.empty()));

        assertEquals("text value must not be blank", failure.getMessage());
    }

    @Test
    void aBlankDetailUsesTheFixedMessage() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ProjectIndexState(
                UUID.randomUUID(), ProjectIndexState.Availability.FAILED, Optional.empty(),
                Optional.empty(), NOW, Optional.of("")));

        assertEquals("text value must not be blank", failure.getMessage());
    }

    @Test
    void presentTextIsKeptAsIs() {
        ProjectIndexState state = new ProjectIndexState(
                UUID.randomUUID(), ProjectIndexState.Availability.READY, Optional.of(" snapshot "),
                Optional.empty(), NOW, Optional.of(" detail "));

        assertEquals(Optional.of(" snapshot "), state.activeSnapshotId());
        assertEquals(Optional.of(" detail "), state.detail());
    }
}

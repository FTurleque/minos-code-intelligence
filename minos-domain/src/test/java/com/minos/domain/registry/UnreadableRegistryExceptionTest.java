package com.minos.domain.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le signal « N entrées sont illisibles » : un dénombrement et une conséquence, jamais un chemin ni un texte de fichier. */
class UnreadableRegistryExceptionTest {

    private static final DegradedEntry FIRST = DegradedEntry.of("11111111-1111-1111-1111-111111111111", "registry entry is unreadable");
    private static final DegradedEntry SECOND = DegradedEntry.of("22222222-2222-2222-2222-222222222222", "registry entry is unreadable");

    @Test
    void theMessageCountsTheEntriesAndStatesTheConsequence() {
        assertEquals("1 registry entry is unreadable, so it cannot be told whether this project exists",
                UnreadableRegistryException.of(List.of(FIRST), "it cannot be told whether this project exists").getMessage());
        assertEquals("2 registry entries are unreadable, so the project name cannot be resolved",
                UnreadableRegistryException.of(List.of(FIRST, SECOND), "the project name cannot be resolved").getMessage());
    }

    @Test
    void itCarriesTheDegradedEntriesTheRegistryProducedAndNothingElse() {
        UnreadableRegistryException exception = UnreadableRegistryException.of(List.of(FIRST, SECOND), "x");

        assertEquals(List.of(FIRST, SECOND), exception.unreadable());
        assertTrue(!exception.getMessage().contains(FIRST.entry()), "the message carries a count, not entry names");
    }

    @Test
    void anEmptyListIsNotUnreadable() {
        assertThrows(IllegalArgumentException.class, () -> UnreadableRegistryException.of(List.of(), "x"));
    }
}

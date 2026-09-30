package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Q13 — caractérise le message fixe de {@code SharedCacheLeaseRegistry.requireText} avant la mutualisation :
 * « lease key must not be blank », y compris pour la description du registre (message historique conservé).
 */
class SharedCacheLeaseRegistryValidationTest {

    @Test
    void aBlankCacheKeyIsRefusedBeforeAnyLocking(@TempDir Path root) {
        SharedCacheLeaseRegistry registry = new SharedCacheLeaseRegistry(root, Duration.ofSeconds(5), "cache");

        IllegalArgumentException acquire = assertThrows(IllegalArgumentException.class, () -> registry.acquire(" "));
        IllegalArgumentException release = assertThrows(IllegalArgumentException.class, () -> registry.release(""));
        IllegalArgumentException held = assertThrows(IllegalArgumentException.class, () -> registry.isHeld(null));

        assertEquals("lease key must not be blank", acquire.getMessage());
        assertEquals("lease key must not be blank", release.getMessage());
        assertEquals("lease key must not be blank", held.getMessage());
    }

    @Test
    void aBlankDescriptionUsesTheSameFixedMessage(@TempDir Path root) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SharedCacheLeaseRegistry(root, Duration.ofSeconds(5), " "));

        assertEquals("lease key must not be blank", failure.getMessage());
    }
}

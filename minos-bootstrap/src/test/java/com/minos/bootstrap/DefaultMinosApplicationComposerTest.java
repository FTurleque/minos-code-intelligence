package com.minos.bootstrap;

import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationComposer;
import com.minos.application.MinosApplicationComposers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A2 / ADR 0042 — minos-bootstrap enregistre exactement une racine de composition, utilisée par open(). */
class DefaultMinosApplicationComposerTest {

    @Test
    void theModuleRegistersExactlyOneCompositionRoot() {
        assertEquals(1, ServiceLoader.load(MinosApplicationComposer.class).stream().count());
        assertInstanceOf(DefaultMinosApplicationComposer.class, MinosApplicationComposers.resolve());
    }

    @Test
    void openComposesTheLocalApplicationThroughTheDiscoveredRoot(@TempDir Path temp) throws Exception {
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            assertEquals("local", application.storageBackendId());
        }
    }
}

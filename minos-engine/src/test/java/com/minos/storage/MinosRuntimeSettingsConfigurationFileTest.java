package com.minos.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code config/minos.properties} : absent => configuration vide ; present sans etre un fichier regulier => refus. */
class MinosRuntimeSettingsConfigurationFileTest {

    private static final String PROPERTY = "minos.test.configuration.file.property";

    @Test
    void anAbsentConfigurationFileLoadsAnEmptyConfiguration(@TempDir Path home) throws Exception {
        MinosRuntimeSettings settings = MinosRuntimeSettings.load(home);

        assertNotNull(settings);
        assertNull(settings.value(PROPERTY, "MINOS_TEST_CONFIGURATION_FILE_PROPERTY"));
    }

    @Test
    void aRegularConfigurationFileIsApplied(@TempDir Path home) throws Exception {
        Path config = Files.createDirectories(home.resolve("config"));
        Files.writeString(config.resolve("minos.properties"), PROPERTY + "=configured\n");

        assertTrue("configured".equals(
                MinosRuntimeSettings.load(home).value(PROPERTY, "MINOS_TEST_CONFIGURATION_FILE_PROPERTY")));
    }

    @Test
    void aDirectoryInPlaceOfTheConfigurationFileIsRefused(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve("config").resolve("minos.properties"));

        IOException refused = assertThrows(IOException.class, () -> MinosRuntimeSettings.load(home));

        assertFalse(refused.getMessage().contains(home.toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains("MINOS runtime configuration"), refused.getMessage());
    }

    @Test
    void aDanglingLinkInPlaceOfTheConfigurationFileIsRefused(@TempDir Path home) throws Exception {
        Path config = Files.createDirectories(home.resolve("config"));
        Path link = config.resolve("minos.properties");
        if (!createSymbolicLink(link, home.resolve("missing-target.properties"))) return;

        IOException refused = assertThrows(IOException.class, () -> MinosRuntimeSettings.load(home));

        assertFalse(refused.getMessage().contains(home.toString()), refused.getMessage());
    }

    @Test
    void aLinkToARegularFileInPlaceOfTheConfigurationFileIsRefused(@TempDir Path home) throws Exception {
        Path config = Files.createDirectories(home.resolve("config"));
        Path target = Files.writeString(home.resolve("outside.properties"), PROPERTY + "=outside\n");
        if (!createSymbolicLink(config.resolve("minos.properties"), target)) return;

        assertThrows(IOException.class, () -> MinosRuntimeSettings.load(home));
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }
}

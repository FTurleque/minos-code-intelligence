package com.minos.storage;

import com.minos.registry.ProjectPathMapping.RuntimeLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L'emplacement d'exécution est un fait du processus : jamais lu dans le fichier partagé du MINOS_HOME. */
class MinosRuntimeSettingsRuntimeLocationTest {

    private static final Path HOME = Path.of("home");

    private static MinosRuntimeSettings settings(Properties file, Map<String, String> environment, Properties system) {
        return MinosRuntimeSettings.testing(HOME, file, environment, system);
    }

    private static Properties with(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }

    @Test
    void nothingConfiguredMeansNative() {
        assertEquals(RuntimeLocation.NATIVE, settings(new Properties(), Map.of(), new Properties()).runtimeLocation());
    }

    @Test
    void theEnvironmentVariableSelectsDocker() {
        assertEquals(RuntimeLocation.DOCKER, settings(new Properties(),
                Map.of("MINOS_RUNTIME_LOCATION", "docker"), new Properties()).runtimeLocation());
    }

    @Test
    void theSystemPropertyWinsOverTheEnvironment() {
        assertEquals(RuntimeLocation.NATIVE, settings(new Properties(),
                Map.of("MINOS_RUNTIME_LOCATION", "docker"), with("minos.runtime.location", "native")).runtimeLocation());
    }

    @Test
    void theSharedConfigurationFileIsNotConsulted() {
        assertEquals(RuntimeLocation.NATIVE, settings(with("minos.runtime.location", "docker"),
                Map.of(), new Properties()).runtimeLocation());
    }

    @Test
    void anUnknownLocationIsRefusedByName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> settings(
                new Properties(), Map.of("MINOS_RUNTIME_LOCATION", "podman"), new Properties()).runtimeLocation());

        assertTrue(failure.getMessage().contains("podman"), failure.getMessage());
    }
}

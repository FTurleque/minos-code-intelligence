package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Liste d'autorisation de {@link ProviderProcessEnvironment} : le test pose lui-même l'environnement du
 * {@link ProcessBuilder}, il ne lit jamais celui de l'hôte.
 */
class ProviderProcessEnvironmentAllowlistTest {

    @Test
    void applyReplacesTheWholeEnvironmentOfTheBuilderByTheAllowlistAndTheDeclaredValues() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin");
        builder.environment().put("TZ", "UTC");
        builder.environment().put("MINOS_REMOTE_TOKEN", "remote-secret");
        builder.environment().put("NPM_TOKEN", "npm-secret");
        builder.environment().put("UNRELATED", "value");

        ProviderProcessEnvironment.apply(builder, Map.of("PROVIDER_CACHE", "/cache", "TZ", "Europe/Paris"));

        Map<String, String> environment = builder.environment();
        assertEquals("/usr/bin", environment.get("PATH"));
        assertEquals("Europe/Paris", environment.get("TZ"), "a declared value overrides the inherited one");
        assertEquals("/cache", environment.get("PROVIDER_CACHE"));
        assertNull(environment.get("MINOS_REMOTE_TOKEN"));
        assertNull(environment.get("NPM_TOKEN"));
        assertNull(environment.get("UNRELATED"));
        assertEquals(3, environment.size(), "nothing else may survive: " + environment.keySet());
    }

    @Test
    void anInheritedEntryWithoutKeyOrValueIsDroppedInsteadOfFailingTheLaunch() {
        Map<String, String> inherited = new HashMap<>();
        inherited.put(null, "orphan value");
        inherited.put("PATH", null);
        inherited.put("LANG", "C.UTF-8");

        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(inherited, Map.of());

        assertEquals(Map.of("LANG", "C.UTF-8"), sanitized);
    }

    @Test
    void theSanitizedEnvironmentCannotBeAmendedAfterwards() {
        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(Map.of("PATH", "/usr/bin"), Map.of());

        assertThrows(UnsupportedOperationException.class, () -> sanitized.put("MINOS_TEAM_TOKEN", "secret"));
        assertFalse(sanitized.containsKey("MINOS_TEAM_TOKEN"));
    }

    @Test
    void missingArgumentsAreRefusedBeforeTheEnvironmentIsTouched() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin");
        Map<String, String> empty = Map.of();

        assertThrows(NullPointerException.class, () -> ProviderProcessEnvironment.apply(null, empty));
        assertThrows(NullPointerException.class, () -> ProviderProcessEnvironment.apply(builder, null));
        assertThrows(NullPointerException.class, () -> ProviderProcessEnvironment.sanitize(null, empty));
        assertThrows(NullPointerException.class,
                () -> ProviderProcessEnvironment.applyForTrustedLauncher(builder, null));
        assertThrows(NullPointerException.class,
                () -> ProviderProcessEnvironment.applyForTrustedLauncher(null, empty));

        assertEquals(Map.of("PATH", "/usr/bin"), Map.copyOf(builder.environment()),
                "a refused call must leave the environment of the builder as it was");
    }
}

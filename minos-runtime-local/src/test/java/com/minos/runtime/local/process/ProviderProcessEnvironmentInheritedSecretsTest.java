package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The secret-name pattern wins over every list: a name that matches it never reaches a provider or a
 * trusted launcher, whether the process declared it or inherited it, and whatever an allowlist says.
 * Two guards keep that true: the behaviour (an allowlist that names a secret still forwards nothing) and the
 * data (no inherited allowlist contains such a name).
 */
class ProviderProcessEnvironmentInheritedSecretsTest {

    private static final List<String> SECRET_NAMES = List.of(
            "EXAMPLE_TOKEN_FILE",
            "example_secret",
            "DATABASE_PASSWORD",
            "service_api_key",
            "CLOUD_CREDENTIAL_FILE",
            "MINOS_TEAM_TOKEN",
            "GITHUB_ACTIONS",
            "NPM_AUTH");

    private static Map<String, String> inheritedWithSecrets() {
        java.util.LinkedHashMap<String, String> inherited = new java.util.LinkedHashMap<>();
        inherited.put("PATH", "/usr/bin");
        inherited.put("JAVA_HOME", "/opt/jdk");
        SECRET_NAMES.forEach(name -> inherited.put(name, "fixture"));
        return inherited;
    }

    /** An allowlist that, by mistake, would admit every inherited name. */
    private static final Predicate<String> ADMITS_EVERYTHING = name -> true;

    @Test
    void noInheritedAllowlistNamesASecret() {
        List<String> offenders = ProviderProcessEnvironment.inheritedAllowlistNames()
                .filter(ProviderProcessEnvironment::isDeniedName)
                .toList();

        assertEquals(List.of(), offenders, "inherited allowlists must not contain a secret-looking name");
    }

    @Test
    void theAllowlistsAreNotEmpty() {
        assertTrue(ProviderProcessEnvironment.inheritedAllowlistNames().count() > 60);
    }

    @Test
    void aSecretNamedVariableAdmittedByAnAllowlistIsNotForwardedToAProvider() {
        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(
                inheritedWithSecrets(), Map.of(), ADMITS_EVERYTHING);

        assertEquals(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/opt/jdk"), sanitized);
    }

    @Test
    void aSecretNamedVariableAdmittedByAnAllowlistIsNotForwardedToATrustedLauncher() {
        Map<String, String> environment = ProviderProcessEnvironment.trustedLauncherEnvironment(
                inheritedWithSecrets(), Map.of(), ADMITS_EVERYTHING);

        assertEquals(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/opt/jdk"), environment);
    }

    @Test
    void anOrdinaryInheritedVariableIsStillForwarded() {
        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(
                Map.of("PATH", "/usr/bin", "JAVA_HOME", "/opt/jdk", "EXAMPLE_TOKEN_FILE", "fixture"),
                Map.of());

        assertEquals("/usr/bin", sanitized.get("PATH"));
        assertEquals("/opt/jdk", sanitized.get("JAVA_HOME"));
        assertFalse(sanitized.containsKey("EXAMPLE_TOKEN_FILE"));
    }

    @Test
    void theRealProcessEnvironmentOfALauncherNeverCarriesASecretName() {
        ProcessBuilder builder = new ProcessBuilder("launcher");
        builder.environment().clear();
        builder.environment().putAll(inheritedWithSecrets());

        ProviderProcessEnvironment.applyForTrustedLauncher(builder, Map.of());

        Set<String> names = builder.environment().keySet();
        assertTrue(names.stream().noneMatch(ProviderProcessEnvironment::isDeniedName), names::toString);
    }
}

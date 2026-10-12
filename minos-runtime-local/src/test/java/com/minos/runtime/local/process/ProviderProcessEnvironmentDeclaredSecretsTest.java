package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A variable a process plan declares is forwarded to untrusted provider code. A name that looks like a
 * secret or a MINOS/CI credential is never forwarded, whatever declared it: the deny pattern wins over the
 * declaration exactly as it wins over the inherited allowlist.
 */
class ProviderProcessEnvironmentDeclaredSecretsTest {

    private static Map<String, String> declaredWithSecrets() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("CARGO_TARGET_DIR", "/run/target");
        declared.put("GITHUB_TOKEN", "fixture");
        declared.put("MINOS_TEAM_KEY_1", "fixture");
        declared.put("NPM_AUTH", "fixture");
        declared.put("DATABASE_PASSWORD", "fixture");
        declared.put("service_api_key", "fixture");
        declared.put("AWS_SECRET_ACCESS", "fixture");
        declared.put("CLOUD_CREDENTIAL_FILE", "fixture");
        return declared;
    }

    @Test
    void aDeclaredVariableNamedLikeASecretIsNotForwardedBySanitize() {
        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(Map.of(), declaredWithSecrets());

        assertEquals(Map.of("CARGO_TARGET_DIR", "/run/target"), sanitized);
    }

    @Test
    void aDeclaredVariableNamedLikeASecretIsNotForwardedToTheProviderProcess() {
        ProcessBuilder builder = new ProcessBuilder("provider");
        builder.environment().clear();

        ProviderProcessEnvironment.apply(builder, declaredWithSecrets());

        assertEquals(Map.of("CARGO_TARGET_DIR", "/run/target"), Map.copyOf(builder.environment()));
    }

    @Test
    void aDeclaredVariableNamedLikeASecretIsNotForwardedToATrustedLauncher() {
        ProcessBuilder builder = new ProcessBuilder("launcher");
        builder.environment().clear();

        ProviderProcessEnvironment.applyForTrustedLauncher(builder, declaredWithSecrets());

        assertEquals(Map.of("CARGO_TARGET_DIR", "/run/target"), Map.copyOf(builder.environment()));
    }

    @Test
    void anOrdinaryDeclaredVariableStillOverridesTheInheritedOne() {
        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(
                Map.of("PATH", "/usr/bin"), Map.of("PATH", "/managed/bin", "npm_config_cache", "/cache"));

        assertEquals(Map.of("PATH", "/managed/bin", "npm_config_cache", "/cache"), sanitized);
    }

    @Test
    void theInstallationEnvironmentRefusesTheSameDeclaredNames() {
        ProcessBuilder builder = new ProcessBuilder("installer");
        builder.environment().clear();

        InstallationProcessEnvironment.apply(builder, declaredWithSecrets());

        assertEquals(Map.of("CARGO_TARGET_DIR", "/run/target"), Map.copyOf(builder.environment()));
    }
}

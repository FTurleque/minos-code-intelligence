package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderProcessEnvironmentTest {

    @Test
    void dropsParentSecretsAndArbitraryVariablesWhileKeepingOnlyRuntimeAllowlist() {
        Map<String, String> inherited = new LinkedHashMap<>();
        inherited.put("PATH", "/usr/bin");
        inherited.put("JAVA_HOME", "/jdk");
        inherited.put("HOME", "/home/operator");
        inherited.put("UNRELATED_PARENT_SETTING", "must-not-leak");
        inherited.put("MINOS_TEAM_TOKEN", "team-secret");
        inherited.put("MINOS_POSTGRES_PASSWORD", "db-secret");
        inherited.put("GITHUB_TOKEN", "github-secret");
        inherited.put("AWS_SECRET_ACCESS_KEY", "aws-secret");

        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(inherited, Map.of());

        assertEquals("/usr/bin", sanitized.get("PATH"));
        assertEquals("/jdk", sanitized.get("JAVA_HOME"));
        if (CommandLocator.isWindows()) {
            assertEquals("/home/operator", sanitized.get("HOME"),
                    "Windows PowerShell/AppContainer startup may inherit the non-secret HOME profile path");
        } else {
            assertFalse(sanitized.containsKey("HOME"));
        }
        assertFalse(sanitized.containsKey("UNRELATED_PARENT_SETTING"));
        assertFalse(sanitized.containsKey("MINOS_TEAM_TOKEN"));
        assertFalse(sanitized.containsKey("MINOS_POSTGRES_PASSWORD"));
        assertFalse(sanitized.containsKey("GITHUB_TOKEN"));
        assertFalse(sanitized.containsKey("AWS_SECRET_ACCESS_KEY"));
    }

    @Test
    void explicitProviderEnvironmentIsPreservedAndCanOverrideAllowlistedValues() {
        Map<String, String> sanitized = ProviderProcessEnvironment.sanitize(
                Map.of("PATH", "/parent", "MINOS_TEAM_TOKEN", "secret"),
                Map.of("PATH", "/provider", "PROVIDER_CACHE", "/cache"));

        assertEquals("/provider", sanitized.get("PATH"));
        assertEquals("/cache", sanitized.get("PROVIDER_CACHE"));
        assertFalse(sanitized.containsKey("MINOS_TEAM_TOKEN"));
        assertTrue(sanitized.size() >= 2);
    }
}

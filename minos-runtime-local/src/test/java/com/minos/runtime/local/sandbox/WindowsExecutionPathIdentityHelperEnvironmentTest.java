package com.minos.runtime.local.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Windows path identity helper is a trusted PowerShell program, but the JVM that starts it may carry
 * control-plane keys, hosted tokens and CI credentials. The helper receives the trusted-launcher allowlist
 * of {@code ProviderProcessEnvironment} and its two targets, never the environment of the JVM.
 */
class WindowsExecutionPathIdentityHelperEnvironmentTest {

    private static Map<String, String> helperEnvironmentFrom(Map<String, String> parent) {
        ProcessBuilder builder = new ProcessBuilder("powershell.exe");
        builder.environment().clear();
        builder.environment().putAll(parent);
        WindowsExecutionPathIdentityProvider.applyHelperEnvironment(
                builder, Path.of("registered-root"), Path.of("project-root"));
        return builder.environment();
    }

    @Test
    void noSecretOfTheJvmEnvironmentReachesTheHelper() {
        Map<String, String> environment = helperEnvironmentFrom(Map.of(
                "SystemRoot", "C:\\Windows",
                "MINOS_TEAM_KEY_1", "team-key",
                "MINOS_TEAM_TOKEN", "team-token",
                "MINOS_REMOTE_TOKEN", "remote-token",
                "GITHUB_TOKEN", "github-token",
                "NPM_TOKEN", "npm-token",
                "PGPASSWORD", "database-password",
                "AWS_SECRET_ACCESS_KEY", "cloud-secret",
                "CLOUD_CREDENTIAL_FILE", "credential-file"));

        assertEquals(Map.of(
                "SystemRoot", "C:\\Windows",
                "MINOS_REGISTERED_PATH_IDENTITY_TARGET", "registered-root",
                "MINOS_PROJECT_PATH_IDENTITY_TARGET", "project-root"), Map.copyOf(environment));
    }

    @Test
    void theHelperDoesNotInheritTheModulePathNorTheToolchainVariablesOfTheJvm() {
        Map<String, String> environment = helperEnvironmentFrom(Map.of(
                "PSModulePath", "C:\\untrusted\\Modules",
                "JAVA_HOME", "C:\\jdk",
                "MAVEN_OPTS", "-Xmx1g",
                "TEMP", "C:\\Temp",
                "TMP", "C:\\Temp"));

        assertFalse(environment.containsKey("PSModulePath"), "PowerShell resolves its own module path");
        assertFalse(environment.containsKey("JAVA_HOME"));
        assertFalse(environment.containsKey("MAVEN_OPTS"));
        assertEquals("C:\\Temp", environment.get("TEMP"));
        assertEquals("C:\\Temp", environment.get("TMP"));
    }

    @Test
    void theTargetsOfTheCallerWinOverInheritedValuesOfTheSameName() {
        Map<String, String> environment = helperEnvironmentFrom(Map.of(
                "MINOS_REGISTERED_PATH_IDENTITY_TARGET", "inherited",
                "minos_project_path_identity_target", "inherited"));

        assertEquals("registered-root", environment.get("MINOS_REGISTERED_PATH_IDENTITY_TARGET"));
        assertEquals("project-root", environment.get("MINOS_PROJECT_PATH_IDENTITY_TARGET"));
        assertFalse(environment.containsKey("minos_project_path_identity_target"));
    }
}

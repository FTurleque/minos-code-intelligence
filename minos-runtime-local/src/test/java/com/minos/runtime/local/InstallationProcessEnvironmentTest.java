package com.minos.runtime.local;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallationProcessEnvironmentTest {

    private static ProcessBuilder builderWith(Map<String, String> environment) {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().clear();
        builder.environment().putAll(environment);
        return builder;
    }

    @Test
    void controlPlaneAndCiSecretsAreNeverInherited() {
        ProcessBuilder builder = builderWith(Map.of(
                "PATH", "/usr/bin",
                "MINOS_TEAM_KEY_ALPHA", "k",
                "MINOS_TEAM_TOKEN", "t",
                "GITHUB_TOKEN", "g",
                "NPM_TOKEN", "n",
                "AWS_SECRET_ACCESS_KEY", "a",
                "UNRELATED", "u"));

        InstallationProcessEnvironment.apply(builder, Map.of());

        Map<String, String> environment = builder.environment();
        assertEquals("/usr/bin", environment.get("PATH"));
        for (String secret : new String[] {"MINOS_TEAM_KEY_ALPHA", "MINOS_TEAM_TOKEN", "GITHUB_TOKEN", "NPM_TOKEN",
                "AWS_SECRET_ACCESS_KEY", "UNRELATED"}) {
            assertFalse(environment.containsKey(secret), secret + " leaked");
        }
    }

    @Test
    void aSecretPatternWinsOverTheDeclaredValues() {
        ProcessBuilder builder = builderWith(Map.of("PATH", "/usr/bin"));

        InstallationProcessEnvironment.apply(builder, Map.of(
                "GOPROXY", "https://proxy.golang.org",
                "MINOS_TEAM_TOKEN", "declared",
                "Some_Password", "declared"));

        assertEquals("https://proxy.golang.org", builder.environment().get("GOPROXY"));
        assertFalse(builder.environment().containsKey("MINOS_TEAM_TOKEN"));
        assertFalse(builder.environment().containsKey("Some_Password"));
    }

    @Test
    void proxiesCachesAndTrustAreKept() {
        ProcessBuilder builder = builderWith(Map.of(
                "PATH", "/usr/bin",
                "HTTPS_PROXY", "http://proxy:3128",
                "https_proxy", "http://proxy:3128",
                "NO_PROXY", "localhost",
                "SSL_CERT_FILE", "/etc/ssl/ca.pem",
                "NODE_EXTRA_CA_CERTS", "/etc/ssl/corporate.pem",
                "REQUESTS_CA_BUNDLE", "/etc/ssl/bundle.pem",
                "npm_config_cache", "/cache/npm",
                "NUGET_PACKAGES", "/cache/nuget",
                "COURSIER_CACHE", "/cache/cs"));

        InstallationProcessEnvironment.apply(builder, Map.of());

        Map<String, String> environment = builder.environment();
        assertEquals("http://proxy:3128", environment.get("HTTPS_PROXY"));
        assertEquals("http://proxy:3128", environment.get("https_proxy"));
        assertEquals("localhost", environment.get("NO_PROXY"));
        assertEquals("/etc/ssl/ca.pem", environment.get("SSL_CERT_FILE"));
        assertEquals("/etc/ssl/corporate.pem", environment.get("NODE_EXTRA_CA_CERTS"));
        assertEquals("/etc/ssl/bundle.pem", environment.get("REQUESTS_CA_BUNDLE"));
        assertEquals("/cache/npm", environment.get("npm_config_cache"));
        assertEquals("/cache/nuget", environment.get("NUGET_PACKAGES"));
        assertEquals("/cache/cs", environment.get("COURSIER_CACHE"));
    }

    @Test
    void homeIsKeptBecauseNpmGoAndCoursierNeedIt() {
        ProcessBuilder builder = builderWith(Map.of("PATH", "/usr/bin", "HOME", "/home/dev", "USER", "dev"));

        InstallationProcessEnvironment.apply(builder, Map.of());

        assertEquals("/home/dev", builder.environment().get("HOME"));
        if (!CommandLocator.isWindows()) {
            assertEquals("dev", builder.environment().get("USER"));
        }
    }

    @Test
    void theDenyPatternMatchesSecretsAndSparesTheCacheDirectory() {
        assertTrue(InstallationProcessEnvironment.isDenied("NPM_TOKEN"));
        assertTrue(InstallationProcessEnvironment.isDenied("minos_anything"));
        assertTrue(InstallationProcessEnvironment.isDenied("GITHUB_ACTIONS"));
        assertTrue(InstallationProcessEnvironment.isDenied("DB_Credential"));
        assertFalse(InstallationProcessEnvironment.isDenied("npm_config_cache"));
        assertFalse(InstallationProcessEnvironment.isDenied("GOPROXY"));
        assertFalse(InstallationProcessEnvironment.isDenied("PATH"));
    }
}

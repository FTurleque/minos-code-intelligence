package com.minos.runtime.local.wsl;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WslCommandEnvironmentTest {

    private static final Map<String, String> INHERITED = Map.of(
            "SystemRoot", "C:\\Windows",
            "WINDIR", "C:\\Windows",
            "TEMP", "C:\\Temp",
            "TMP", "C:\\Temp",
            "USERPROFILE", "C:\\Users\\someone",
            "LOCALAPPDATA", "C:\\Users\\someone\\AppData\\Local",
            "PATH", "C:\\tools;C:\\Windows\\System32",
            "MINOS_TEAM_TOKEN", "team-secret",
            "GITHUB_TOKEN", "github-secret",
            "WSLENV", "MINOS_TEAM_TOKEN/u:GITHUB_TOKEN/u");

    @Test
    void secretsAndInheritedWslenvNeverReachTheCommand() {
        Map<String, String> environment = WslCommandEnvironment.build(INHERITED);

        assertFalse(environment.containsKey("MINOS_TEAM_TOKEN"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertEquals("", environment.get("WSLENV"), "WSLENV must be present and empty");
        assertFalse(environment.values().contains("team-secret"));
        assertFalse(environment.values().contains("github-secret"));
    }

    @Test
    void onlyTheRouteAllowlistIsKeptAndPathIsTheSystemDirectory() {
        Map<String, String> environment = WslCommandEnvironment.build(INHERITED);

        assertEquals(Set.of("SystemRoot", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "PATH", "WSLENV"),
                environment.keySet());
        assertEquals("C:\\Windows\\System32", environment.get("PATH"));
        assertEquals("C:\\Users\\someone", environment.get("USERPROFILE"));
    }

    @Test
    void allowlistLookupIgnoresTheCaseOfWindowsNames() {
        Map<String, String> environment = WslCommandEnvironment.build(Map.of(
                "SYSTEMROOT", "C:\\Windows\\",
                "temp", "C:\\Temp",
                "wslenv", "GITHUB_TOKEN/u"));

        assertEquals("C:\\Windows\\", environment.get("SystemRoot"));
        assertEquals("C:\\Windows\\System32", environment.get("PATH"));
        assertEquals("C:\\Temp", environment.get("TEMP"));
        assertEquals("", environment.get("WSLENV"));
        assertEquals(4, environment.size());
    }

    @Test
    void withoutSystemRootNoPathIsInvented() {
        Map<String, String> environment = WslCommandEnvironment.build(Map.of("PATH", "C:\\tools", "GITHUB_TOKEN", "g"));

        assertEquals(Map.of("WSLENV", ""), environment);
    }

    @Test
    void applyReplacesTheWholeBuilderEnvironment() {
        ProcessBuilder builder = new ProcessBuilder();
        builder.environment().clear();
        builder.environment().putAll(INHERITED);
        builder.environment().put("NPM_TOKEN", "n");

        WslCommandEnvironment.apply(builder);

        Map<String, String> environment = builder.environment();
        assertFalse(environment.containsKey("MINOS_TEAM_TOKEN"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertFalse(environment.containsKey("NPM_TOKEN"));
        assertEquals("", environment.get("WSLENV"));
        assertTrue(environment.containsKey("PATH"));
    }
}

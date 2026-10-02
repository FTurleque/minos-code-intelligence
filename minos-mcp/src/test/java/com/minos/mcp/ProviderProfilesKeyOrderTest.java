package com.minos.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Q6: each provider profile of {@code providerProfiles} was copied with {@code Map.copyOf}, which
 * discarded the order of its nine keys and drew a new one at every JVM start.
 */
class ProviderProfilesKeyOrderTest {

    private static final List<String> PROFILE_KEYS = List.of(
            "id", "version", "languages", "buildSystems", "capabilities", "conformanceScorePercent",
            "limitations", "runtimeState", "runtimeDiagnostics");

    @Test
    void everyProfileKeepsItsDeclaredKeyOrderAndSortedCapabilities(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname='fixture'\nversion='0.1.0'\n");
        Files.writeString(project.resolve("src/main.py"), "value = 1\n");
        MinosApplication application = MinosApplication.open(temp.resolve("home"));
        application.projectRegistry().registerProject(project, "python-fixture");
        MinosApplicationMcpBackend backend = new MinosApplicationMcpBackend(application);

        for (String json : List.of(backend.projectStructure("python-fixture"), backend.indexStatus("python-fixture"))) {
            JsonNode profiles = new ObjectMapper().readTree(json).get("providerProfiles");
            assertFalse(profiles.isEmpty());
            for (JsonNode profile : profiles) {
                assertEquals(PROFILE_KEYS, keys(profile), "profile " + profile.get("id"));
                List<String> capabilities = keys(profile.get("capabilities"));
                List<String> sorted = new ArrayList<>(capabilities);
                Collections.sort(sorted);
                assertEquals(sorted, capabilities, "capabilities of " + profile.get("id"));
            }
        }
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

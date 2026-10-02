package com.minos.intellij.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q23: since the reliability sprint, {@code minos project list} exits 3 when some registry entries are unreadable,
 * with valid and complete JSON for the projects it could read. The plugin used to accept only exit 0, so one damaged
 * project made it lose the healthy project that is open.
 */
class MinosProjectListTest {

    private static final String OPEN_PROJECT_ROOT = "/work/open";

    @Test
    void aPartialInventoryIsAcceptedForProjectList() {
        assertTrue(MinosProjectList.ACCEPTED_EXIT_CODES.contains(0));
        assertTrue(MinosProjectList.ACCEPTED_EXIT_CODES.contains(3), "exit 3 is a partial result, not a failure");
        assertFalse(MinosProjectList.ACCEPTED_EXIT_CODES.contains(1));
        assertFalse(MinosProjectList.ACCEPTED_EXIT_CODES.contains(2));
    }

    @Test
    void theExitCodeOfAPartialInventoryIsNotAcceptedForOtherCommands() {
        // The plugin keeps rejecting 3 everywhere else: only project list is defined to return it with usable output.
        MinosProtocolException failure = assertThrows(MinosProtocolException.class,
                () -> MinosJsonOutput.parse(3, "{}", "warning: partial", Set.of(0)));

        assertTrue(failure.getMessage().contains("exit 3"), failure.getMessage());
    }

    @Test
    void theHealthyOpenProjectIsResolvedFromAnExit3Inventory() throws Exception {
        JsonObject output = MinosJsonOutput.parse(3, partialInventory(), "warning: project inventory is partial",
                MinosProjectList.ACCEPTED_EXIT_CODES);

        MinosProjectList.Resolution resolution = MinosProjectList.resolve(output, OPEN_PROJECT_ROOT::equals, OPEN_PROJECT_ROOT);

        assertEquals("11111111-1111-1111-1111-111111111111", resolution.project().get("id").getAsString());
        assertEquals(1, resolution.unreadable(), "the user is told about the damaged entry, not left with a silent success");
    }

    @Test
    void aHealthyRegistryResolvesWithNothingToReport() throws Exception {
        JsonObject output = MinosJsonOutput.parse(0, healthyInventory(), "", MinosProjectList.ACCEPTED_EXIT_CODES);

        MinosProjectList.Resolution resolution = MinosProjectList.resolve(output, OPEN_PROJECT_ROOT::equals, OPEN_PROJECT_ROOT);

        assertEquals("11111111-1111-1111-1111-111111111111", resolution.project().get("id").getAsString());
        assertEquals(0, resolution.unreadable());
    }

    @Test
    void anOpenProjectNotFoundBesideUnreadableEntriesIsNotReportedAsUnregistered() {
        JsonObject output = partial("/work/other", 2);

        MinosProtocolException failure = assertThrows(MinosProtocolException.class,
                () -> MinosProjectList.resolve(output, OPEN_PROJECT_ROOT::equals, OPEN_PROJECT_ROOT));

        assertFalse(failure instanceof MinosProjectNotRegisteredException,
                "it may be one of the unreadable entries: telling the user to `project add` it would be a wrong answer");
        assertTrue(failure.getMessage().contains("2 registry entries are unreadable"), failure.getMessage());
        assertTrue(failure.getMessage().contains("minos project list"), failure.getMessage());
    }

    @Test
    void anOpenProjectNotFoundInAHealthyRegistryIsStillUnregistered() {
        JsonObject output = partial("/work/other", 0);

        assertThrows(MinosProjectNotRegisteredException.class,
                () -> MinosProjectList.resolve(output, OPEN_PROJECT_ROOT::equals, OPEN_PROJECT_ROOT));
    }

    @Test
    void theUnreadableCountIsReadFromTheDegradedCounterOfTheInventory() {
        assertEquals(0, MinosProjectList.unreadableCount(new JsonObject()));
        assertEquals(3, MinosProjectList.unreadableCount(partial("/x", 3)));
    }

    /** What {@code minos project list --format json} prints on a registry with one damaged entry (exit 3). */
    private static String partialInventory() {
        return """
                {"count":2,"projects":[
                  {"id":"11111111-1111-1111-1111-111111111111","name":"open","rootPath":"/work/open","indexState":"READY"},
                  {"id":"22222222-2222-2222-2222-222222222222","name":"-","rootPath":"-","indexState":"UNREADABLE"}],
                 "degradedCount":1,
                 "degraded":[{"entry":"22222222-2222-2222-2222-222222222222","reason":"registry entry is unreadable"}]}
                """;
    }

    private static String healthyInventory() {
        return """
                {"count":1,"projects":[
                  {"id":"11111111-1111-1111-1111-111111111111","name":"open","rootPath":"/work/open","indexState":"READY"}]}
                """;
    }

    private static JsonObject partial(String rootPath, int degraded) {
        JsonObject project = new JsonObject();
        project.addProperty("id", "33333333-3333-3333-3333-333333333333");
        project.addProperty("rootPath", rootPath);
        JsonArray projects = new JsonArray();
        projects.add(project);
        JsonObject root = new JsonObject();
        root.add("projects", projects);
        if (degraded > 0) root.addProperty("degradedCount", degraded);
        return root;
    }
}

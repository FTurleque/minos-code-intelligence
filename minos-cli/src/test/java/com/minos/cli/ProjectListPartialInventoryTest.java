package com.minos.cli;

import com.minos.application.ProjectOperations;
import com.minos.registry.DegradedEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q8 : {@code minos project list} distingue « tout va bien » (code 0, sortie inchangée) de « inventaire partiel »
 * (code 3, compteur et raisons affichés en texte comme en JSON). Les autres commandes gardent leurs codes.
 */
class ProjectListPartialInventoryTest {

    private static final int PARTIAL = 3;
    private static final String FORMAT = "--format";
    private static final String JSON = "json";
    private static final String UNREADABLE = "UNREADABLE";
    private static final String HEALTHY_ID = UUID.randomUUID().toString();
    private static final String DAMAGED_ID = UUID.randomUUID().toString();
    private static final String DAMAGED_REASON = "registry entry is unreadable (DateTimeParseException)";

    @Test
    void aPartialInventoryExitsWithThePartialCodeAndShowsTheCounterInText() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubOperations(partial()));
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = command.run(new String[]{"list"}, output, error);

        assertEquals(PARTIAL, exit, output.toString());
        assertTrue(output.toString().contains(HEALTHY_ID + "\tdemo\tNEVER_INDEXED\tproject-root"), output.toString());
        assertTrue(output.toString().contains(DAMAGED_ID + "\t-\t" + UNREADABLE), "the damaged project is a row: " + output);
        assertTrue(output.toString().contains("degraded: 1"), output.toString());
        assertTrue(output.toString().contains(DAMAGED_ID + ": " + DAMAGED_REASON), output.toString());
        assertTrue(error.toString().contains("1 of 2"), "a warning reaches stderr: " + error);
    }

    @Test
    void aPartialInventoryExitsWithThePartialCodeAndShowsTheCounterInJson() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubOperations(partial()));
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = command.run(new String[]{"list", FORMAT, JSON}, output, error);

        assertEquals(PARTIAL, exit, output.toString());
        assertTrue(output.toString().contains("\"count\":2"), output.toString());
        assertTrue(output.toString().contains("\"degradedCount\":1"), output.toString());
        assertTrue(output.toString().contains("\"degraded\":[{\"entry\":\"" + DAMAGED_ID + "\",\"reason\":\""
                + DAMAGED_REASON + "\"}]"), output.toString());
        assertTrue(output.toString().contains("\"indexState\":\"" + UNREADABLE + "\""), output.toString());
        assertTrue(error.toString().contains("1 of 2"), error.toString());
    }

    @Test
    void aHealthyInventoryKeepsExitZeroAndAnOutputWithoutAnyCounter() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubOperations(healthy()));
        StringBuilder text = new StringBuilder();
        StringBuilder json = new StringBuilder();
        StringBuilder error = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS, command.run(new String[]{"list"}, text, error));
        assertEquals(FindSymbolCommand.SUCCESS, command.run(new String[]{"list", FORMAT, JSON}, json, error));

        assertEquals(HEALTHY_ID + "\tdemo\tNEVER_INDEXED\tproject-root\n", text.toString());
        assertTrue(json.toString().startsWith("{\"count\":1,\"projects\":[{\"id\":\"" + HEALTHY_ID + "\""), json.toString());
        assertFalse(json.toString().contains("degraded"), "no stray counter: " + json);
        assertEquals("", error.toString(), "a healthy listing writes nothing on stderr");
    }

    @Test
    void anEmptyRegistryIsStillASuccess() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubOperations(new ProjectOperations.ProjectInventory(List.of(), List.of())));
        StringBuilder output = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS, command.run(new String[]{"list"}, output, new StringBuilder()));
        assertEquals("No projects registered.\n", output.toString());
    }

    @Test
    void aRegistryThatCannotBeListedAtAllStillExitsWithTheExecutionError() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubOperations(null));
        StringBuilder error = new StringBuilder();

        int exit = command.run(new String[]{"list"}, new StringBuilder(), error);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exit);
        assertTrue(error.toString().startsWith("error: project list failed"), error.toString());
    }

    @Test
    void aBadOptionStillExitsWithTheUsageError() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubOperations(partial()));

        int exit = command.run(new String[]{"list", "--bogus"}, new StringBuilder(), new StringBuilder());

        assertEquals(FindSymbolCommand.USAGE_ERROR, exit);
    }

    @Test
    void theOtherProjectCommandsKeepTheirExitCodesWhateverTheInventoryLooksLike() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubOperations(partial()));

        assertEquals(FindSymbolCommand.SUCCESS,
                command.runInspectAlias(new String[]{"demo"}, new StringBuilder(), new StringBuilder()));
        assertEquals(FindSymbolCommand.SUCCESS,
                command.runIndexStatus(new String[]{"demo"}, new StringBuilder(), new StringBuilder()));
    }

    private static ProjectOperations.ProjectInventory healthy() {
        return new ProjectOperations.ProjectInventory(List.of(healthyView()), List.of());
    }

    private static ProjectOperations.ProjectInventory partial() {
        return new ProjectOperations.ProjectInventory(List.of(healthyView(), damagedView()),
                List.of(new DegradedEntry(DAMAGED_ID, DAMAGED_REASON)));
    }

    private static ProjectOperations.ProjectView healthyView() {
        return new ProjectOperations.ProjectView(HEALTHY_ID, "demo", "project-root", true, List.of(), List.of(), 0,
                "NEVER_INDEXED", null, null, null, null);
    }

    private static ProjectOperations.ProjectView damagedView() {
        return new ProjectOperations.ProjectView(DAMAGED_ID, "-", "-", false, List.of(), List.of(), 0,
                UNREADABLE, null, null, null, null);
    }

    /** {@code inventory == null} : le registre ne peut pas être listé du tout. */
    private static final class StubOperations implements ProjectOperations {
        private final ProjectInventory stored;

        private StubOperations(ProjectInventory stored) {
            this.stored = stored;
        }

        @Override public ProjectView addProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ProjectView> listProjects() throws IOException { return inventory().projects(); }
        @Override public ProjectInventory inventory() throws IOException {
            if (stored == null) throw new IOException("registry directory is unreadable");
            return stored;
        }
        @Override public ProjectView inspectProject(String projectIdentifier) { return healthyView(); }
        @Override public IndexImportResult importScip(String projectIdentifier, Path indexFile, String providerId,
                                                      String providerVersion, String moduleId, String snapshotId) {
            throw new UnsupportedOperationException();
        }
    }
}

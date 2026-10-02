package com.minos.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.application.ProjectOperations;
import com.minos.orchestration.IndexingMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le motif de refus de reprise est un texte public : il traverse la même règle que les autres diagnostics, y compris
 * pour les caractères de contrôle qu'un terminal exécuterait.
 */
class IndexCommandRefusalReasonTest {

    private static final String ESCAPE = "\u001b[31m";
    private static final String CONTROLS = "bell\u0007 nul\u0000 bidi\u202e end " + ESCAPE + "red";

    @Test
    void aReasonCarryingAnAbsolutePathIsRedactedInBothOutputs() throws Exception {
        assertRedacted("checkpoint unreadable at C:\\Users\\someone\\.minos\\state.json");
        assertRedacted("checkpoint unreadable at /home/someone/.minos/state.json");
    }

    @Test
    void aReasonCarryingControlCharactersIsPrintableInTextAndStaysValidJson() throws Exception {
        String reason = "checkpoint damaged: " + CONTROLS;

        String text = run(reason, "text");
        String json = run(reason, "json");

        assertNoControlCharacter(text.replace("\n", ""));
        assertNoControlCharacter(json.replace("\n", ""));
        assertTrue(text.contains("refused (checkpoint damaged: bell_ nul_ bidi_ end _[31mred)"), text);
        JsonNode parsed = new ObjectMapper().readTree(json);
        assertEquals("checkpoint damaged: bell_ nul_ bidi_ end _[31mred",
                parsed.path("resumed").path("refusalReason").asText());
    }

    @Test
    void aReasonWithAPathAndControlCharactersLeaksNeitherAndAnAbsentReasonStaysNull() throws Exception {
        String reason = "damaged " + ESCAPE + " C:\\Users\\someone\\x";
        assertRedacted(reason);

        JsonNode parsed = new ObjectMapper().readTree(run(null, "json"));
        assertNull(parsed.path("resumed").path("refusalReason").textValue());
        assertTrue(parsed.path("resumed").path("refusalReason").isNull());
    }

    private static void assertRedacted(String reason) throws Exception {
        String text = run(reason, "text");
        String json = run(reason, "json");
        for (String output : List.of(text, json)) {
            assertFalse(output.contains("someone"), output);
            assertFalse(output.contains("state.json"), output);
            assertNoControlCharacter(output.replace("\n", ""));
        }
        assertEquals("internal diagnostic redacted",
                new ObjectMapper().readTree(json).path("resumed").path("refusalReason").asText());
    }

    private static void assertNoControlCharacter(String output) {
        output.codePoints().forEach(codePoint -> assertFalse(
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT,
                "control character U+" + Integer.toHexString(codePoint) + " in: " + output));
    }

    private static String run(String refusal, String format) throws Exception {
        IndexCommand command = new IndexCommand(new StubProjects(), new StubAutonomous(refusal));
        StringBuilder output = new StringBuilder();
        assertEquals(0, command.run(new String[]{"demo", "--format", format}, output, new StringBuilder()));
        return output.toString();
    }

    private record StubAutonomous(String refusal) implements AutonomousIndexOperations {
        @Override public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
            return plan();
        }

        @Override public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
            ResumeView resumed = refusal == null ? new ResumeView(2, 5, 3, null) : new ResumeView(1, 0, 8, refusal);
            return new IndexExecutionView(plan(), "run-1", "SUCCEEDED", "snapshot-1", true, null, resumed);
        }

        @Override public List<ProviderView> providers() { return List.of(); }

        @Override public ProviderView installProvider(String providerId) { throw new UnsupportedOperationException(); }

        private static IndexPlanView plan() {
            return new IndexPlanView("project-id", "demo", "C:/demo", List.of("JAVA"), List.of("MAVEN"),
                    List.of("scip-java"), List.of(), IndexingMode.FULL, List.of("NO_ACTIVE_INDEX"), List.of(), false);
        }
    }

    private static final class StubProjects implements ProjectOperations {
        @Override public ProjectView addProject(Path rootPath, String displayName) { throw new UnsupportedOperationException(); }
        @Override public List<ProjectView> listProjects() { return List.of(); }
        @Override public ProjectView inspectProject(String projectIdentifier) { throw new UnsupportedOperationException(); }
        @Override public IndexImportResult importScip(String projectIdentifier, Path indexFile, String providerId,
                                                      String providerVersion, String moduleId, String snapshotId) {
            throw new UnsupportedOperationException();
        }
    }
}

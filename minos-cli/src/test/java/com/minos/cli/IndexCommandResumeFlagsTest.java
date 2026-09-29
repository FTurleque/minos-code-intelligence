package com.minos.cli;

import com.minos.application.ProjectOperations;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingResumePolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 3 : `minos index` reprend par défaut, `--no-resume`, `--resume-only`, sortie `resumed`. */
class IndexCommandResumeFlagsTest {

    @Test
    void resumeIsTheDefaultAndFlagsSelectThePolicy() throws Exception {
        StubAutonomous autonomous = new StubAutonomous();
        IndexCommand command = new IndexCommand(new StubProjects(), autonomous);

        assertEquals(0, command.run(new String[]{"demo"}, new StringBuilder(), new StringBuilder()));
        assertEquals(0, command.run(new String[]{"demo", "--no-resume"}, new StringBuilder(), new StringBuilder()));
        assertEquals(0, command.run(new String[]{"demo", "--resume-only"}, new StringBuilder(), new StringBuilder()));
        assertEquals(List.of(IndexingResumePolicy.RESUME, IndexingResumePolicy.NO_RESUME, IndexingResumePolicy.RESUME_ONLY),
                autonomous.policies);

        StringBuilder error = new StringBuilder();
        assertEquals(FindSymbolCommand.USAGE_ERROR, command.run(
                new String[]{"demo", "--no-resume", "--resume-only"}, new StringBuilder(), error));
        assertTrue(error.toString().contains("--no-resume"), error.toString());
    }

    @Test
    void executionOutputExposesTheResumeOutcomeInTextAndJson() throws Exception {
        StubAutonomous autonomous = new StubAutonomous();
        IndexCommand command = new IndexCommand(new StubProjects(), autonomous);

        StringBuilder json = new StringBuilder();
        assertEquals(0, command.run(new String[]{"demo", "--format", "json"}, json, new StringBuilder()));
        assertTrue(json.toString().contains("\"resumed\":{\"attempt\":2,\"reusedTargets\":5,\"reexecutedTargets\":3,"
                + "\"refusalReason\":null}"), json.toString());

        StringBuilder text = new StringBuilder();
        assertEquals(0, command.run(new String[]{"demo"}, text, new StringBuilder()));
        assertTrue(text.toString().contains("resumed: attempt 2, 5/8 targets reused, 3 re-executed"), text.toString());

        autonomous.refusal = "checkpoint older than resume TTL";
        StringBuilder refused = new StringBuilder();
        command.run(new String[]{"demo"}, refused, new StringBuilder());
        assertTrue(refused.toString().contains("resumed: refused (checkpoint older than resume TTL), 8 re-executed"),
                refused.toString());

        autonomous.noTrace = true;
        StringBuilder none = new StringBuilder();
        command.run(new String[]{"demo", "--format", "json"}, none, new StringBuilder());
        assertTrue(none.toString().contains("\"resumed\":null"), none.toString());
    }

    @Test
    void usageDocumentsThatAFailureDuringAReopenedAttemptConsumesTheCheckpoints() {
        // V18: user-facing statement of the ADR 0039 rule "explicit provider failure = FAILED".
        String usage = IndexCommand.usage();
        assertTrue(usage.contains("--no-resume"), usage);
        assertTrue(usage.contains("--resume-only"), usage);
        assertTrue(usage.contains("consumes"), usage);
    }

    @Test
    void resumeOnlyFailureIsReportedAsAnExecutionError() throws Exception {
        StubAutonomous autonomous = new StubAutonomous();
        autonomous.failResumeOnly = true;
        IndexCommand command = new IndexCommand(new StubProjects(), autonomous);
        StringBuilder error = new StringBuilder();

        int code = command.run(new String[]{"demo", "--resume-only"}, new StringBuilder(), error);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, code);
        assertTrue(error.toString().contains("resume"), error.toString());
    }

    private static final class StubAutonomous implements AutonomousIndexOperations {
        final List<IndexingResumePolicy> policies = new ArrayList<>();
        String refusal;
        boolean noTrace;
        boolean failResumeOnly;

        @Override public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
            return plan();
        }

        @Override public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
            return execute(projectIdentifier, providerOverride, forceFull, IndexingResumePolicy.RESUME);
        }

        @Override public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull,
                                                    IndexingResumePolicy policy) {
            policies.add(policy);
            if (failResumeOnly && policy == IndexingResumePolicy.RESUME_ONLY) {
                throw new IllegalStateException("resume-only indexing refused: no interrupted run to resume");
            }
            ResumeView resumed = noTrace ? null
                    : refusal != null ? new ResumeView(1, 0, 8, refusal) : new ResumeView(2, 5, 3, null);
            return new IndexExecutionView(plan(), "run-1", "SUCCEEDED", "snapshot-1", true, null, resumed);
        }

        @Override public List<ProviderView> providers() {
            return List.of(new ProviderView("scip-java", "1", "READY", "cs", List.of()));
        }

        @Override public ProviderView installProvider(String providerId) { return providers().getFirst(); }

        private static IndexPlanView plan() {
            return new IndexPlanView("project-id", "demo", "C:/demo", List.of("JAVA"), List.of("MAVEN"),
                    List.of("scip-java"), List.of(new ProviderView("scip-java", "1", "READY", "cs", List.of())),
                    IndexingMode.FULL, List.of("NO_ACTIVE_INDEX"), List.of(), false);
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

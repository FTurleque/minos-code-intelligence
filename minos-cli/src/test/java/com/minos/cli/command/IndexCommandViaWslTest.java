package com.minos.cli.command;

import com.minos.application.ProjectOperations;
import com.minos.application.indexing.IndexingResumeChoice;
import com.minos.cli.operations.AutonomousIndexOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `minos index --via-wsl`: usage refusals before any work, and delegation to the WSL2 route when it is offered. */
class IndexCommandViaWslTest {

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "the WSL2 route is a Windows-only option")
    void aWindowsBackendWithoutTheRouteRefusesNamingTheOptionAndDoesNothing() throws Exception {
        StubAutonomous autonomous = new StubAutonomous(false);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int code = new IndexCommand(new StubProjects(), autonomous).run(new String[]{"demo", "--via-wsl"}, output, error);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, code);
        assertTrue(error.toString().contains(AutonomousIndexOperations.VIA_WSL_UNAVAILABLE), error.toString());
        assertEquals("", output.toString());
        assertEquals(0, autonomous.localRuns + autonomous.plans, "nothing may be planned or executed locally");
    }

    @Test
    @EnabledOnOs(value = {OS.LINUX, OS.MAC}, disabledReason = "on Windows the option reaches the backend")
    void offWindowsTheOptionIsAUsageErrorThatNamesItBeforeReachingTheBackend() throws Exception {
        StubAutonomous autonomous = new StubAutonomous(true);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int code = new IndexCommand(new StubProjects(), autonomous).run(new String[]{"demo", "--via-wsl"}, output, error);

        assertEquals(FindSymbolCommand.USAGE_ERROR, code);
        assertTrue(error.toString().startsWith("error: --via-wsl is only available on a Windows installation"),
                error.toString());
        assertEquals("", output.toString());
        assertEquals(List.of(), autonomous.wslDistributions);
        assertEquals(0, autonomous.localRuns + autonomous.plans);
    }

    @Test
    void theRouteIsNotCombinableWithAScipImportAndTheMessageNamesBothOptions() throws Exception {
        StringBuilder error = new StringBuilder();
        StubAutonomous autonomous = new StubAutonomous(true);

        int code = new IndexCommand(new StubProjects(), autonomous).run(
                new String[]{"demo", "--via-wsl", "--scip", "index.scip", "--provider", "scip-java"},
                new StringBuilder(), error);

        assertEquals(FindSymbolCommand.USAGE_ERROR, code);
        assertTrue(error.toString().contains("--via-wsl cannot be combined with --scip"), error.toString());
        assertEquals(List.of(), autonomous.wslDistributions);
    }

    @Test
    void theRouteIsNotCombinableWithADryRun() throws Exception {
        StringBuilder error = new StringBuilder();
        StubAutonomous autonomous = new StubAutonomous(true);

        int code = new IndexCommand(new StubProjects(), autonomous).run(
                new String[]{"demo", "--via-wsl", "--dry-run"}, new StringBuilder(), error);

        assertEquals(FindSymbolCommand.USAGE_ERROR, code);
        assertTrue(error.toString().contains("--via-wsl cannot be combined with --dry-run"), error.toString());
        assertEquals(0, autonomous.plans);
    }

    @Test
    void aDistributionRequiresTheRouteAndMustBeAPlainName() throws Exception {
        StubAutonomous autonomous = new StubAutonomous(true);
        IndexCommand command = new IndexCommand(new StubProjects(), autonomous);

        StringBuilder withoutRoute = new StringBuilder();
        assertEquals(FindSymbolCommand.USAGE_ERROR,
                command.run(new String[]{"demo", "--wsl-distro", "Ubuntu"}, new StringBuilder(), withoutRoute));
        assertTrue(withoutRoute.toString().contains("--wsl-distro requires --via-wsl"), withoutRoute.toString());

        for (String invalid : new String[]{"Ubuntu;rm", "-d", "a b", "../x"}) {
            StringBuilder error = new StringBuilder();
            assertEquals(FindSymbolCommand.USAGE_ERROR, command.run(
                    new String[]{"demo", "--via-wsl", "--wsl-distro", invalid}, new StringBuilder(), error), invalid);
            assertTrue(error.toString().contains("--wsl-distro must be a WSL distribution name"), error.toString());
        }
        assertEquals(List.of(), autonomous.wslDistributions);
        assertEquals(0, autonomous.localRuns);
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "the WSL2 route is a Windows-only option")
    void anOfferedRouteReceivesTheDistributionAndTheResumeChoice() throws Exception {
        StubAutonomous autonomous = new StubAutonomous(true);
        IndexCommand command = new IndexCommand(new StubProjects(), autonomous);
        StringBuilder json = new StringBuilder();

        assertEquals(0, command.run(new String[]{"demo", "--via-wsl", "--wsl-distro", "Ubuntu-24.04", "--no-resume",
                "--format", "json"}, json, new StringBuilder()));
        assertEquals(0, command.run(new String[]{"demo", "--via-wsl"}, new StringBuilder(), new StringBuilder()));

        assertEquals(List.of("Ubuntu-24.04", "<default>"), autonomous.wslDistributions);
        assertEquals(List.of(IndexingResumeChoice.NO_RESUME, IndexingResumeChoice.RESUME), autonomous.wslPolicies);
        assertEquals(0, autonomous.localRuns, "the route must not fall back to the native execution");
        assertTrue(json.toString().contains("\"status\":\"SUCCEEDED\""), json.toString());
    }

    @Test
    void usageDocumentsTheRouteAsWindowsOnly() {
        String usage = IndexCommand.usage();
        assertTrue(usage.contains("--via-wsl"), usage);
        assertTrue(usage.contains("--wsl-distro"), usage);
        assertTrue(usage.contains("Windows only"), usage);
    }

    private static final class StubAutonomous implements AutonomousIndexOperations {
        private final boolean offersRoute;
        final List<String> wslDistributions = new ArrayList<>();
        final List<IndexingResumeChoice> wslPolicies = new ArrayList<>();
        int localRuns;
        int plans;

        StubAutonomous(boolean offersRoute) {
            this.offersRoute = offersRoute;
        }

        @Override public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
            plans++;
            return plan();
        }

        @Override public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
            localRuns++;
            return execution();
        }

        @Override public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull,
                                                    IndexingResumeChoice policy) {
            localRuns++;
            return execution();
        }

        @Override public IndexExecutionView executeViaWsl(String projectIdentifier, String providerOverride,
                                                          boolean forceFull, IndexingResumeChoice resumeChoice,
                                                          String distribution) throws Exception {
            if (!offersRoute) {
                return AutonomousIndexOperations.super.executeViaWsl(
                        projectIdentifier, providerOverride, forceFull, resumeChoice, distribution);
            }
            wslDistributions.add(distribution == null ? "<default>" : distribution);
            wslPolicies.add(resumeChoice);
            return execution();
        }

        @Override public List<ProviderView> providers() {
            return List.of(new ProviderView("scip-java", "1", "READY", "cs", List.of()));
        }

        @Override public ProviderView installProvider(String providerId) { return providers().getFirst(); }

        private static IndexExecutionView execution() {
            return new IndexExecutionView(plan(), "run-1", "SUCCEEDED", "snapshot-1", true, null, null);
        }

        private static IndexPlanView plan() {
            return new IndexPlanView("project-id", "demo", "C:/demo", List.of("JAVA"), List.of("MAVEN"),
                    List.of("scip-java"), List.of(new ProviderView("scip-java", "1", "READY", "cs", List.of())),
                    "FULL", List.of("NO_ACTIVE_INDEX"), List.of(), false);
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

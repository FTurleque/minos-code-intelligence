package com.minos.cli.command;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectOperations;
import com.minos.application.team.TeamControlPlane;
import com.minos.cli.operations.AutonomousIndexOperations;
import com.minos.cli.support.CliExitCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A command asked directly for its help prints its usage and exits with {@link CliExitCodes#SUCCESS}, before it
 * needs any operation; a command given an unknown action exits with {@link CliExitCodes#USAGE_ERROR}.
 */
class CommandHelpExitCodesTest {

    private final StringBuilder output = new StringBuilder();
    private final StringBuilder error = new StringBuilder();

    @Test
    void ideHelpSucceedsAndAnUnknownActionIsAUsageError() throws IOException {
        assertEquals(CliExitCodes.SUCCESS, new IdeCommand().run(new String[] {"--help"}, output, error));
        assertTrue(output.toString().contains("handshake"), output.toString());

        assertEquals(CliExitCodes.USAGE_ERROR, new IdeCommand().run(new String[] {"unknown"}, output, error));
        assertTrue(error.toString().contains("expected `ide handshake`"), error.toString());
    }

    @Test
    void retrievalStatusHelpSucceedsWithAndWithoutTheAction() throws IOException {
        RetrievalStatusCommand command = new RetrievalStatusCommand(RetrievalStatusCommand.Mode.SEMANTIC,
                reference -> {
                    throw new AssertionError("help reads no status");
                });

        assertEquals(CliExitCodes.SUCCESS, command.run(new String[] {"--help"}, output, error));
        assertEquals(CliExitCodes.SUCCESS, command.run(new String[] {"status", "--help"}, output, error));
        assertEquals("", error.toString());
    }

    @Test
    void nexusExportHelpSucceedsWithoutExporting() throws IOException {
        NexusExportCommand command = new NexusExportCommand(root -> {
            throw new AssertionError("help exports nothing");
        });

        assertEquals(CliExitCodes.SUCCESS, command.run(new String[] {"--help"}, output, error));
        assertEquals("", error.toString());
    }
    @Test
    void projectTeamAndIdeIntelligenceHelpSucceedBeforeAnyOperation() throws IOException {
        ProjectOperations projects = (ProjectOperations) Proxy.newProxyInstance(
                ProjectOperations.class.getClassLoader(), new Class<?>[] {ProjectOperations.class},
                (self, method, arguments) -> {
                    throw new AssertionError("help runs no project operation");
                });
        Supplier<TeamControlPlane> team = () -> {
            throw new AssertionError("help opens no team control plane");
        };
        Supplier<MinosApplication> application = () -> {
            throw new AssertionError("help opens no application");
        };

        assertEquals(CliExitCodes.SUCCESS, new ProjectCommand(projects).run(new String[] {"--help"}, output, error));
        assertEquals(CliExitCodes.SUCCESS, new TeamCommand(team, () -> null).run(new String[] {"--help"}, output,
                error));
        assertEquals(CliExitCodes.SUCCESS, new IdeIntelligenceCommand(application).run(new String[] {"--help"},
                output, error));
        assertEquals("", error.toString());
    }

    @Test
    void toolsInstallRendersTheInstalledProviderAndSucceeds() throws IOException {
        AutonomousIndexOperations.ProviderView installed =
                new AutonomousIndexOperations.ProviderView("scip-java", "0.13.1", "READY", null, List.of(), true);
        ToolsCommand command = new ToolsCommand(new AutonomousIndexOperations() {
            @Override
            public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("plan must not be called");
            }

            @Override
            public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("execute must not be called");
            }

            @Override
            public List<ProviderView> providers() {
                throw new AssertionError("providers must not be called");
            }

            @Override
            public ProviderView installProvider(String providerId) {
                assertEquals("scip-java", providerId);
                return installed;
            }
        });

        assertEquals(CliExitCodes.SUCCESS, command.run(new String[] {"install", "scip-java"}, output, error));
        assertTrue(output.toString().contains("scip-java"), output.toString());
    }
}

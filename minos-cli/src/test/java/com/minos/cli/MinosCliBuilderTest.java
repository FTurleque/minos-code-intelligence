package com.minos.cli;

import com.minos.application.ProjectOperations;
import com.minos.application.ProjectSymbolQuery;
import com.minos.git.GitIntelligence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A4 (ADR 0045) : {@code MinosCli.builder(...)} reproduit le câblage des anciens constructeurs. Un collaborateur
 * non fourni vaut l'ancien {@code null} : sa commande répond « not configured in this CLI bootstrap » (code 1) ;
 * une commande câblée analyse ses options (option inconnue : code 2) sans appeler ses collaborateurs.
 */
class MinosCliBuilderTest {

    private static final List<String> OPTIONAL_COMMANDS = List.of(
            "project", "inspect", "index", "import-scip", "index-status", "tools", "doctor", "providers",
            "architecture", "impact", "git-activity", "nexus-export", "remote", "runtime", "team");

    @Test
    void symbolQueryAloneLeavesEveryOptionalCommandUnconfigured() throws IOException {
        MinosCli cli = MinosCli.builder(unused(ProjectSymbolQuery.class)).build();

        for (String command : OPTIONAL_COMMANDS) {
            StringBuilder error = new StringBuilder();
            assertEquals(FindSymbolCommand.EXECUTION_ERROR, cli.run(new String[]{command}, new StringBuilder(), error), command);
            assertEquals("error: " + command + " is not configured in this CLI bootstrap\n", error.toString(), command);
        }
    }

    @Test
    void doctorNeedsBothAutonomousOperationsAndHome() throws IOException {
        AutonomousIndexOperations operations = unused(AutonomousIndexOperations.class);

        MinosCli withoutHome = MinosCli.builder(unused(ProjectSymbolQuery.class)).autonomousOperations(operations).build();
        assertUnconfigured(withoutHome, "doctor");
        assertWired(withoutHome, "tools");

        MinosCli withHome = MinosCli.builder(unused(ProjectSymbolQuery.class))
                .autonomousOperations(operations).home(Path.of(".")).build();
        assertWired(withHome, "doctor");
    }

    @Test
    void gitActivityNeedsBothProjectOperationsAndGitIntelligence() throws IOException {
        ProjectOperations projects = unused(ProjectOperations.class);

        MinosCli withoutGit = MinosCli.builder(unused(ProjectSymbolQuery.class)).projectOperations(projects).build();
        assertUnconfigured(withoutGit, "git-activity");
        assertWired(withoutGit, "project");
        assertWired(withoutGit, "index-status");

        MinosCli withGit = MinosCli.builder(unused(ProjectSymbolQuery.class))
                .projectOperations(projects).gitIntelligence(unused(GitIntelligence.class)).build();
        assertWired(withGit, "git-activity");
    }

    @Test
    void rejectsNullCollaboratorsInsteadOfTreatingThemAsAbsent() {
        NullPointerException missingQuery = assertThrows(NullPointerException.class, () -> MinosCli.builder(null));
        assertEquals("symbolQuery", missingQuery.getMessage());

        MinosCli.Builder builder = MinosCli.builder(unused(ProjectSymbolQuery.class));
        assertThrows(NullPointerException.class, () -> builder.projectOperations(null));
        assertThrows(NullPointerException.class, () -> builder.architectureQuery(null));
        assertThrows(NullPointerException.class, () -> builder.impactQuery(null));
        assertThrows(NullPointerException.class, () -> builder.nexusExportCommand(null));
        assertThrows(NullPointerException.class, () -> builder.autonomousOperations(null));
        assertThrows(NullPointerException.class, () -> builder.home(null));
        assertThrows(NullPointerException.class, () -> builder.providerPlatformService(null));
        assertThrows(NullPointerException.class, () -> builder.gitIntelligence(null));
        assertThrows(NullPointerException.class, () -> builder.remoteIndexOperations(null));
        assertThrows(NullPointerException.class, () -> builder.runtimeIntelligenceService(null));
        assertThrows(NullPointerException.class, () -> builder.hostedControlPlaneService(null));
        assertThrows(NullPointerException.class, () -> builder.resumeStatus(null));
    }

    private static void assertUnconfigured(MinosCli cli, String command) throws IOException {
        StringBuilder error = new StringBuilder();
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, cli.run(new String[]{command}, new StringBuilder(), error), command);
        assertEquals("error: " + command + " is not configured in this CLI bootstrap\n", error.toString(), command);
    }

    private static void assertWired(MinosCli cli, String command) throws IOException {
        StringBuilder error = new StringBuilder();
        assertEquals(FindSymbolCommand.USAGE_ERROR,
                cli.run(new String[]{command, "--a4-unknown-option"}, new StringBuilder(), error), command + ": " + error);
    }

    private static <T> T unused(Class<T> contract) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> {
                    throw new AssertionError("the CLI must not call " + contract.getSimpleName() + "." + method.getName());
                }));
    }
}

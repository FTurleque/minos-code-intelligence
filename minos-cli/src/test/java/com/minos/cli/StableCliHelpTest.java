package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q11 : {@code --help} est traité avant toute ouverture de {@code MINOS_HOME}, pour TOUTES les
 * commandes. La liste des commandes n'est pas recopiée ici : elle vient de la table de routes de
 * {@link MinosCli} (plus {@code semantic} et {@code hybrid}, traitées par le lanceur), de sorte
 * qu'une commande ajoutée sans aide sans état fait échouer ce test.
 */
class StableCliHelpTest {

    @TempDir Path root;

    private void assertStatelessHelp(String... arguments) throws Exception {
        Path home = root.resolve("absent-home-" + String.join("-", arguments).replaceAll("[^a-z-]", "_"));
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        String context = String.join(" ", arguments);
        assertEquals(0, MinosLauncher.run(home, arguments, output, error), context + " -> " + error);
        assertTrue(output.toString().startsWith("Usage:"), context + " -> " + output);
        assertEquals("", error.toString(), context);
        assertFalse(Files.exists(home), context + " opened MINOS_HOME");
        assertTrue(MinosCliRunner.isStatelessHelpRequest(arguments), context);
    }

    @Test
    void everyCommandAnswersHelpWithoutOpeningMinosHome() throws Exception {
        Set<String> commands = MinosCliRunner.statelessHelpCommands();
        assertTrue(commands.contains("doctor"), "doctor must answer --help without opening MINOS_HOME");
        for (String command : commands) {
            assertStatelessHelp(command, "--help");
            assertStatelessHelp(command, "-h");
        }
    }

    @Test
    void theTopLevelHelpAndTheSubCommandHelpsAreStatelessToo() throws Exception {
        List<String[]> invocations = List.of(
                new String[]{"--help"},
                new String[]{"project", "add", "--help"},
                new String[]{"project", "list", "--help"},
                new String[]{"project", "inspect", "--help"},
                new String[]{"semantic", "status", "--help"},
                new String[]{"hybrid", "status", "--help"});
        for (String[] invocation : invocations) {
            assertStatelessHelp(invocation);
        }
    }

    @Test
    void doctorHelpShowsTheUsageInsteadOfRunningTheDiagnostic() throws Exception {
        StringBuilder output = new StringBuilder();
        assertEquals(0, MinosLauncher.run(root.resolve("home"), new String[]{"doctor", "--help"}, output,
                new StringBuilder()));
        assertEquals(DoctorCommand.usage() + "\n", output.toString());
    }

    @Test
    void theCommandTableMatchesTheTopLevelUsage() {
        for (String command : MinosCliRunner.statelessHelpCommands()) {
            assertTrue(MinosCli.usage().contains(command), command + " is routed but missing from the usage");
        }
    }

    @Test
    void aHelpTokenAmongOtherArgumentsIsNotAHelpRequest() {
        assertFalse(MinosCliRunner.isStatelessHelpRequest(new String[]{"find-symbol", "p", "--help"}));
        assertFalse(MinosCliRunner.isStatelessHelpRequest(new String[]{"doctor", "--format", "--help"}));
        assertFalse(MinosCliRunner.isStatelessHelpRequest(new String[]{"unknown-command", "--help"}));
    }
}

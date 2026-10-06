package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.registry.ProjectRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static com.minos.cli.CliArgumentRules.command;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q11 : {@code ide <opération>} obéit aux règles uniformes et sépare l'analyse (code 2) de
 * l'exécution : une {@link IllegalArgumentException} venue d'un service est une erreur d'exécution
 * (code 1), jamais une erreur d'usage.
 */
class IdeIntelligenceCommandTest {

    @TempDir Path home;

    @Test
    void uniformRules() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home.resolve("rules"));
        rules.check(command("ide program-graph", "ide program-graph p")
                .integer("--max-nodes", 1, 100_000, 10).integer("--max-edges", 1, 500_000, 10)
                .choice("--format", "json"));
        rules.check(command("ide impact-v2", "ide impact-v2 p S")
                .integerAnyRange("--max-depth", 3).integerAnyRange("--max-results", 5).choice("--format", "json"));
        rules.check(command("ide security-paths", "ide security-paths p")
                .text("--source-node", "n1").integerAnyRange("--max-depth", 3).integerAnyRange("--max-results", 5)
                .choice("--format", "json"));
        rules.check(command("ide semantic-index-status", "ide semantic-index-status p").choice("--format", "json"));
        rules.check(command("ide semantic-index-sync", "ide semantic-index-sync p").choice("--format", "json"));
        rules.check(command("ide semantic-search", "ide semantic-search p Q")
                .integerAnyRange("--limit", 5).decimal("--minimum-score", "0.5").choice("--format", "json"));
        rules.check(command("ide hybrid-search", "ide hybrid-search p Q")
                .integerAnyRange("--limit", 5).decimal("--minimum-score", "0.5").choice("--format", "json"));
        rules.check(command("ide hybrid-context", "ide hybrid-context p Q")
                .integerAnyRange("--max-documents", 2).integerAnyRange("--max-tokens", 1000)
                .integerAnyRange("--max-tokens-per-document", 50).choice("--format", "json"));
    }

    private final StringBuilder output = new StringBuilder();
    private final StringBuilder error = new StringBuilder();

    private int run(String... arguments) throws IOException {
        output.setLength(0);
        error.setLength(0);
        MinosApplication application = MinosApplication.builder(home.resolve("app")).build();
        return MinosCliRunner.run(application, arguments, output, error);
    }

    @Test
    void aFailureRaisedByAServiceIsAnExecutionErrorNotAUsageError() throws IOException {
        String[][] invocations = {
                {"ide", "semantic-index-status", "unknown-project", "--format", "json"},
                {"ide", "program-graph", "unknown-project"},
                {"ide", "impact-v2", "unknown-project", "S"},
                {"ide", "security-paths", "unknown-project"},
                {"ide", "semantic-search", "unknown-project", "query"},
                {"ide", "hybrid-context", "unknown-project", "query"},
        };
        for (String[] invocation : invocations) {
            int code = run(invocation);
            String context = String.join(" ", invocation) + " -> " + code + " " + error;
            assertEquals(1, code, context);
            assertTrue(error.toString().startsWith("error: "), context);
            assertFalse(error.toString().contains("Usage:"), context);
        }
    }

    /** MINOS-AUD-C16 : une erreur d'E/S ou d'exécution d'un service est une erreur de la commande, pas un échec de démarrage. */
    @Test
    void anIoFailureOfAServiceIsAnExecutionErrorNotABootstrapFailure() throws Exception {
        ProjectRegistry brokenRegistry = (ProjectRegistry) Proxy.newProxyInstance(
                ProjectRegistry.class.getClassLoader(), new Class<?>[]{ProjectRegistry.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                                : method.getName().equals("equals") ? proxy == arguments[0] : "broken-registry";
                    }
                    throw new IOException("registry storage failure");
                });
        MinosApplication application = MinosApplication.builder(home.resolve("app"))
                .projectRegistry(brokenRegistry).build();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        int code = new IdeIntelligenceCommand(application).run(
                new String[]{"semantic-index-status", "some-project"}, out, err);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, code, err.toString());
        assertTrue(err.toString().startsWith("error: "), err.toString());
        assertFalse(err.toString().contains("bootstrap"), err.toString());
    }

    @Test
    void aRuntimeFailureOfAServiceIsAnExecutionErrorToo() throws Exception {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        int code = new IdeIntelligenceCommand(() -> {
            throw new UncheckedIOException(new IOException("storage failure"));
        }).run(new String[]{"semantic-index-status", "some-project"}, out, err);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, code, err.toString());
        assertTrue(err.toString().startsWith("error: "), err.toString());
        assertFalse(err.toString().contains("bootstrap"), err.toString());
    }

    /** Un MINOS_HOME qui ne s'ouvre pas n'est pas un échec de la commande : le relais traverse jusqu'au lanceur. */
    @Test
    void aMinosHomeThatCannotBeOpenedStillCrossesTheCommandToTheLauncher() {
        LazyApplication lazy = LazyApplication.opening(home.resolve("never-opened"), () -> {
            throw new IOException("cannot open MINOS_HOME");
        });
        StringBuilder err = new StringBuilder();

        LazyApplication.OpenFailure failure = org.junit.jupiter.api.Assertions.assertThrows(
                LazyApplication.OpenFailure.class,
                () -> new IdeIntelligenceCommand(lazy::get).run(
                        new String[]{"semantic-index-status", "p"}, new StringBuilder(), err));

        assertEquals("cannot open MINOS_HOME", failure.failure().getMessage());
        assertEquals("", err.toString(), "the command itself reports nothing: the launcher says « bootstrap failed »");
    }

    /** MINOS-AUD-C05 : un plafond total bas seul est accepté (le plafond par document par défaut s'y plie). */
    @Test
    void aLowMaxTokensAloneIsAcceptedAndReachesTheProjectResolution() throws IOException {
        for (String maxTokens : new String[]{"128", "200", "799"}) {
            int code = run("ide", "hybrid-context", "unknown-project", "query", "--max-tokens", maxTokens);

            assertEquals(1, code, maxTokens);
            assertTrue(error.toString().contains("unknown project"), maxTokens + " -> " + error);
            assertFalse(error.toString().contains("maxTokensPerDocument"), maxTokens + " -> " + error);
        }
    }

    @Test
    void anExplicitlyInconsistentPairIsStillRefusedWithAnActionableMessage() throws IOException {
        int code = run("ide", "hybrid-context", "unknown-project", "query",
                "--max-tokens", "500", "--max-tokens-per-document", "900");

        assertEquals(2, code, "an inconsistent explicit pair is an argument error, as before this change");
        assertTrue(error.toString().contains("maxTokensPerDocument must be between 32 and maxTokens"), error.toString());
    }

    @Test
    void aBoundCheckedByTheAnalysisIsAUsageErrorWithTheUsage() throws IOException {
        String[][] invocations = {
                {"ide", "program-graph", "p", "--max-nodes", "0"},
                {"ide", "program-graph", "p", "--max-edges", "500001"},
                {"ide", "impact-v2", "p", "S", "--max-depth", "0"},
                {"ide", "impact-v2", "p", "S", "--max-results", "10001"},
                {"ide", "security-paths", "p", "--max-results", "1001"},
                {"ide", "semantic-search", "p", "q", "--limit", "0"},
                {"ide", "semantic-search", "p", "q", "--minimum-score", "2"},
                {"ide", "hybrid-search", "p", "q", "--minimum-score", "-0.5"},
                {"ide", "hybrid-context", "p", "q", "--max-tokens", "10"},
                {"ide", "semantic-search", "p", "q", "--format", "text"},
        };
        for (String[] invocation : invocations) {
            int code = run(invocation);
            String context = String.join(" ", invocation) + " -> " + code + " " + error;
            assertEquals(2, code, context);
            assertTrue(error.toString().contains("Usage: minos ide"), context);
        }
        assertEquals(2, run("ide", "program-graph", "p", "--max-nodes", "0"));
        assertTrue(error.toString().startsWith("error: --max-nodes must be between 1 and 100000"), error.toString());
    }

    @Test
    void anOperandThatStartsWithASingleDashIsAValueForFreeText() throws IOException {
        int code = run("ide", "semantic-search", "unknown-project", "-leading-dash");
        assertEquals(1, code, error.toString());
        assertFalse(error.toString().contains("Usage:"), error.toString());
        assertEquals(2, run("ide", "semantic-search", "unknown-project", "--leading-dashes"));
        assertTrue(error.toString().contains("semantic-search requires <project> <query>"), error.toString());
    }
}

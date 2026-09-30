package com.minos.cli;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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

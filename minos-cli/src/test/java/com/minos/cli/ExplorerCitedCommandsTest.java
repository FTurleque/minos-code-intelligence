package com.minos.cli;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code minos ...} command the exploration page or its user guide tells the reader to run is a command
 * this CLI recognizes: an actionable message never names a command the launcher refuses as unknown.
 */
class ExplorerCitedCommandsTest {

    /** The files whose text sends the reader to a command, from the reactor root. */
    private static final List<String> SOURCES = List.of(
            "minos-explorer/src/main/resources/com/minos/explorer/web/app.js",
            "docs/user/semantic-explorer.md");

    /** A command quoted in a message («…») or in code (`…`), or alone on a line of a code block. */
    private static final Pattern CITED = Pattern.compile(
            "(?:[«`]\\s?|(?m)^)minos ((?:[a-z][a-z-]*)(?: [a-z][a-z-]*)*)");

    /** What the launcher answers when it does not know the command or the operation it is given. */
    private static final List<String> REFUSALS = List.of(
            "unknown command", "unknown IDE intelligence operation", "Usage: minos <command> [arguments]");

    @Test
    void everyCommandCitedByTheExplorationPageAndItsGuideIsRecognizedByTheCli(@TempDir Path home) throws Exception {
        Map<String, Set<String>> cited = new LinkedHashMap<>();
        for (String source : SOURCES) {
            String text = Files.readString(reactorRoot().resolve(source), StandardCharsets.UTF_8);
            Matcher matcher = CITED.matcher(text);
            while (matcher.find()) {
                cited.computeIfAbsent(matcher.group(1), ignored -> new TreeSet<>()).add(source);
            }
        }
        List<String> refused = new ArrayList<>();
        for (Map.Entry<String, Set<String>> command : cited.entrySet()) {
            String answer = answerOf(home, command.getKey());
            for (String refusal : REFUSALS) {
                if (answer.contains(refusal)) {
                    refused.add("minos " + command.getKey() + " (cited by " + command.getValue() + "): "
                            + answer.lines().findFirst().orElse(""));
                    break;
                }
            }
        }

        assertEquals(List.of(), refused, "commands the page or its guide cites and the CLI refuses as unknown");
        for (String expected : List.of("explorer serve", "index", "project add", "ide semantic-index-sync")) {
            assertTrue(cited.containsKey(expected), "the cited commands are read from the sources: " + cited.keySet());
        }
    }

    @Test
    void aCommandTheCliDoesNotKnowIsToldApartFromOneItKnows(@TempDir Path home) {
        assertTrue(REFUSALS.stream().anyMatch(answerOf(home, "semantic-index-sync")::contains));
        assertTrue(REFUSALS.stream().anyMatch(answerOf(home, "ide no-such-operation")::contains));
        assertTrue(REFUSALS.stream().noneMatch(answerOf(home, "ide semantic-index-sync")::contains));
        assertTrue(REFUSALS.stream().noneMatch(answerOf(home, "index")::contains));
    }

    /** What the launcher writes when the command is run on an empty home, with a project that does not exist. */
    private static String answerOf(Path home, String command) {
        List<String> arguments = new ArrayList<>(List.of(command.split(" ")));
        arguments.add("no-such-project");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        MinosLauncher.launch(arguments.toArray(String[]::new),
                Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.resolve("minos-home").toString()),
                new Properties(), new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8), MinosApplication::open, MinosLauncher::run);
        return error.toString(StandardCharsets.UTF_8) + output.toString(StandardCharsets.UTF_8);
    }

    private static Path reactorRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int level = 0; level < 4 && candidate != null; level++, candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("minos-explorer")) && Files.isDirectory(candidate.resolve("docs"))) {
                return candidate;
            }
        }
        throw new IOException("reactor root not found from " + Path.of("").toAbsolutePath());
    }
}

package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q8 de bout en bout, sur un vrai {@code MINOS_HOME} : un fichier du registre abîmé ne fait plus échouer
 * {@code minos project list} ; le projet abîmé est une ligne, il est compté, et la commande sort en 3.
 */
class ProjectListDamagedRegistryIntegrationTest {

    private static final int PARTIAL = 3;
    private static final String PROJECT = "project";
    private static final String FORMAT = "--format";
    private static final String JSON = "json";
    private static final String LIST = "list";
    private static final Pattern IDENTIFIER = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @TempDir
    Path temp;

    @Test
    void oneDamagedRegistryFileLeavesTheOtherProjectsListedAndTheExitCodePartial() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path first = Files.createDirectories(temp.resolve("first"));
        Path second = Files.createDirectories(temp.resolve("second"));
        Path third = Files.createDirectories(temp.resolve("third"));
        for (Path root : new Path[]{first, second, third}) {
            assertEquals(0, run(home, PROJECT, "add", root.toString(), "--name", root.getFileName().toString()).exit());
        }
        Result before = run(home, PROJECT, LIST, FORMAT, JSON);
        assertEquals(0, before.exit(), before.error());
        assertFalse(before.output().contains("degraded"), "a healthy registry carries no counter: " + before.output());

        String damagedId = firstIdentifier(before.output());
        Path file = home.resolve("registry").resolve("projects").resolve(damagedId + ".properties");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("createdAt=.*", "createdAt=not-an-instant"), StandardCharsets.UTF_8);

        Result json = run(home, PROJECT, LIST, FORMAT, JSON);
        assertEquals(PARTIAL, json.exit(), json.output() + json.error());
        assertTrue(json.output().contains("\"count\":3"), json.output());
        assertTrue(json.output().contains("\"degradedCount\":1"), json.output());
        assertTrue(json.output().contains("\"entry\":\"" + damagedId + "\""), json.output());
        assertTrue(json.output().contains("\"indexState\":\"UNREADABLE\""), json.output());
        assertTrue(json.error().contains("1 of 3"), json.error());
        assertNoAbsolutePath(json, temp);

        Result text = run(home, PROJECT, LIST);
        assertEquals(PARTIAL, text.exit(), text.output() + text.error());
        assertTrue(text.output().contains("degraded: 1"), text.output());
        assertTrue(text.output().contains(damagedId + "\t-\tUNREADABLE"), text.output());
        assertNoAbsolutePath(text, home.resolve("registry"));
    }

    @Test
    void theHealthyProjectsOfADamagedRegistryStayInspectableByIdentifier() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        for (String name : new String[]{"alpha", "beta"}) {
            Path root = Files.createDirectories(temp.resolve(name));
            assertEquals(0, run(home, PROJECT, "add", root.toString(), "--name", name).exit());
        }
        Result before = run(home, PROJECT, LIST, FORMAT, JSON);
        Matcher matcher = IDENTIFIER.matcher(before.output());
        assertTrue(matcher.find());
        String damagedId = matcher.group(1);
        assertTrue(matcher.find());
        String healthyId = matcher.group(1);
        Path file = home.resolve("registry").resolve("projects").resolve(damagedId + ".properties");
        Files.writeString(file, "id=" + damagedId + "\n", StandardCharsets.UTF_8);

        Result inspect = run(home, "inspect", healthyId, FORMAT, JSON);

        assertEquals(0, inspect.exit(), inspect.error());
        assertTrue(inspect.output().contains("\"id\":\"" + healthyId + "\""), inspect.output());
    }

    @Test
    void anEmptyRegistryIsStillExitZero() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));

        Result result = run(home, PROJECT, LIST);

        assertEquals(0, result.exit(), result.error());
        assertEquals("No projects registered.\n", result.output());
    }

    private static String firstIdentifier(String json) {
        Matcher matcher = IDENTIFIER.matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    /** Ce que la commande dit d'une entrée dégradée ne porte aucun chemin absolu (le répertoire du test en est un). */
    private static void assertNoAbsolutePath(Result result, Path directory) {
        String degraded = result.output().contains("degraded") ? result.output().substring(result.output().indexOf("degraded")) : "";
        assertFalse((degraded + result.error()).contains(directory.toString()), degraded + result.error());
    }

    private static Result run(Path home, String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exit = MinosLauncher.run(home, arguments, output, error);
        return new Result(exit, output.toString(), error.toString());
    }

    private record Result(int exit, String output, String error) {
    }
}

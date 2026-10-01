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
 * Q24 de bout en bout, sur un vrai {@code MINOS_HOME} : ce que chaque commande fait devant une entrée de registre
 * abîmée est écrit (CLI-SUIVI.md § 5) et figé ici. Une commande ne mélange jamais « absent » et « illisible », et rien
 * n'est écarté en silence : ce qui est ignoré est compté et affiché.
 */
class RegistryToleranceCliTest {

    private static final int PARTIAL = 3;
    private static final Pattern IDENTIFIER = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @TempDir
    Path temp;

    private Path home;
    private Path alphaRoot;
    private String alphaId;
    private String betaId;

    /** Un registre de deux projets, {@code alpha} et {@code beta} ; {@code beta} a son fichier abîmé quand {@code damageBeta}. */
    private void registry(boolean damageBeta) throws Exception {
        home = Files.createDirectories(temp.resolve("home"));
        alphaRoot = Files.createDirectories(temp.resolve("alpha"));
        Path betaRoot = Files.createDirectories(temp.resolve("beta"));
        assertEquals(0, run("project", "add", alphaRoot.toString(), "--name", "alpha").exit());
        assertEquals(0, run("project", "add", betaRoot.toString(), "--name", "beta").exit());
        Matcher ids = IDENTIFIER.matcher(run("project", "list", "--format", "json").output());
        while (ids.find()) {
            String id = ids.group(1);
            String content = Files.readString(entry(id), StandardCharsets.UTF_8);
            if (content.contains("displayName=alpha")) alphaId = id;
            if (content.contains("displayName=beta")) betaId = id;
        }
        if (alphaId == null || betaId == null) throw new IllegalStateException("could not tell the two projects apart");
        if (damageBeta) damage(betaId);
    }

    private Path entry(String id) {
        return home.resolve("registry").resolve("projects").resolve(id + ".properties");
    }

    private void damage(String id) throws Exception {
        Path file = entry(id);
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replaceAll("createdAt=.*", "createdAt=\u001b[2Jevil\u0007"), StandardCharsets.UTF_8);
    }

    @Test
    void aNameFoundBesideADamagedEntryIsAnsweredButTheVerdictSaysItIsIncomplete() throws Exception {
        registry(true);

        for (String[] command : new String[][]{
                {"inspect", "alpha", "--format", "json"}, {"project", "inspect", "alpha", "--format", "json"},
                {"index-status", "alpha", "--format", "json"}}) {
            Result result = run(command);
            String context = String.join(" ", command) + " -> " + result.output() + result.error();
            assertEquals(PARTIAL, result.exit(), context);
            assertTrue(result.output().contains(alphaId), context);
            assertTrue(result.error().contains("warning: 1 registry entry is unreadable, "
                    + "so this name cannot be proven unique"), context);
            assertClean(result);
        }
    }

    @Test
    void aNameNotFoundBesideADamagedEntryIsNotReportedAsAbsent() throws Exception {
        registry(true);

        Result result = run("inspect", "ghost");

        assertEquals(PARTIAL, result.exit(), result.output() + result.error());
        assertTrue(result.error().contains("1 registry entry is unreadable, "
                + "so it cannot be told whether this project exists"), result.error());
        assertFalse(result.error().contains("unknown project"), "unreadable is not absent: " + result.error());
        assertClean(result);
    }

    @Test
    void aNameNotFoundInAHealthyRegistryIsStillAbsent() throws Exception {
        registry(false);

        Result result = run("inspect", "ghost");

        assertEquals(1, result.exit(), result.output() + result.error());
        assertTrue(result.error().contains("unknown project: ghost"), result.error());
        assertFalse(result.error().contains("unreadable"), result.error());
    }

    @Test
    void anIdentifierBesideADamagedEntryIsUnaffected() throws Exception {
        registry(true);

        Result result = run("inspect", alphaId, "--format", "json");

        assertEquals(0, result.exit(), result.output() + result.error());
        assertEquals("", result.error());
    }

    @Test
    void projectAddRefusesAndSaysWhy() throws Exception {
        registry(true);
        Path gamma = Files.createDirectories(temp.resolve("gamma"));

        Result result = run("project", "add", gamma.toString(), "--name", "gamma");

        assertEquals(1, result.exit(), result.output() + result.error());
        assertTrue(result.error().contains("1 registry entry is unreadable, "
                + "so the uniqueness of the registration cannot be guaranteed"), result.error());
        assertClean(result);
        assertEquals(2, Files.list(home.resolve("registry").resolve("projects")).count(), "nothing was registered");
    }

    @Test
    void everyOtherCommandByNameStaysStrictWithAnActionableMessage() throws Exception {
        registry(true);

        for (String[] command : new String[][]{
                {"find-symbol", "alpha", "Foo"}, {"architecture", "alpha"}, {"impact", "alpha", "Foo"}}) {
            Result result = run(command);
            String context = String.join(" ", command) + " -> " + result.output() + result.error();
            assertEquals(1, result.exit(), context);
            assertTrue(result.error().contains("1 registry entry is unreadable, "
                    + "so the project name cannot be resolved with certainty; use its UUID"), context);
            assertClean(result);
        }
        assertFalse(run("architecture", alphaId).error().contains("unreadable"),
                "by UUID the name rule does not apply");
    }

    @Test
    void nexusExportStaysStrictAndSaysWhy() throws Exception {
        registry(true);

        Result result = run("nexus-export", "--root", alphaRoot.toString());

        assertEquals(1, result.exit(), result.output() + result.error());
        assertTrue(result.error().contains("1 registry entry is unreadable, "
                + "so the project of this root cannot be located with certainty"), result.error());
        assertClean(result);
    }

    @Test
    void anEntirelyDamagedRegistryIsStillAPartialInventoryThatListsEveryDamagedEntry() throws Exception {
        registry(false);
        damage(alphaId);
        damage(betaId);

        Result json = run("project", "list", "--format", "json");

        assertEquals(PARTIAL, json.exit(), json.output() + json.error());
        assertTrue(json.output().contains("\"degradedCount\":2"), json.output());
        assertTrue(json.error().contains("2 of 2"), json.error());
        // The inventory shows the damaged entries, with the terminal-safe reason that DegradedEntry builds.
        assertNoControl(json);
    }

    @Test
    void aRegistryThatCannotBeListedAtAllIsAFailureNotAPartialInventory() throws Exception {
        registry(false);
        Path projects = home.resolve("registry").resolve("projects");
        for (Path file : Files.list(projects).toList()) Files.delete(file);
        Files.delete(projects);
        Files.writeString(projects, "not a directory", StandardCharsets.UTF_8);

        // Through the launcher, as a user runs it: the storage cannot even be opened.
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream error = new java.io.ByteArrayOutputStream();
        int exit;
        try (java.io.PrintStream out = new java.io.PrintStream(output, true, StandardCharsets.UTF_8);
             java.io.PrintStream err = new java.io.PrintStream(error, true, StandardCharsets.UTF_8)) {
            exit = MinosLauncher.launch(new String[]{"project", "list"},
                    java.util.Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString()),
                    new java.util.Properties(), out, err, com.minos.application.MinosApplication::open,
                    MinosLauncher::run);
        }

        String message = error.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit, message);
        assertTrue(message.startsWith("error: MINOS bootstrap failed:"), message);
        assertFalse(message.contains("degraded"), message);
    }

    /** Ni caractère de contrôle, ni texte du fichier abîmé dans ce que la commande dit, ni chemin absolu sur l'erreur. */
    private void assertClean(Result result) {
        assertNoControl(result);
        assertFalse(result.error().contains(temp.toString()), "absolute path in: " + result.error());
        for (String stream : new String[]{result.output(), result.error()}) {
            assertFalse(stream.contains("evil"), "text of the damaged file leaked: " + stream);
        }
    }

    private static void assertNoControl(Result result) {
        for (String stream : new String[]{result.output(), result.error()}) {
            assertTrue(stream.chars().noneMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\t'), stream);
        }
    }

    private Result run(String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exit = MinosLauncher.run(home, arguments, output, error);
        return new Result(exit, output.toString(), error.toString());
    }

    private record Result(int exit, String output, String error) {
    }
}

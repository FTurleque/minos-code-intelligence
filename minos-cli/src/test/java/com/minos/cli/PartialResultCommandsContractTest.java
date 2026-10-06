package com.minos.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q25 : la liste des commandes qui peuvent rendre 3, lue par les scripts, est épinglée au comportement réel de la CLI.
 *
 * <p>{@code scripts/lib/partial-result-commands.json} est la seule liste (docs/audit/Q25-Q26-SUIVI.md § 2.3). Un script
 * qui la lit n'est juste que si la liste l'est : chaque commande listée rend bien 3 devant un registre abîmé, et les
 * autres commandes par nom ne le rendent jamais. Ajouter un 3 à une commande sans la lister, ou lister une commande qui
 * ne le rend pas, fait échouer cette garde.</p>
 */
class PartialResultCommandsContractTest {

    private static final int PARTIAL = 3;
    private static final List<String> INVENTORY = List.of("project", "list");

    @TempDir
    Path temp;

    private Path home;

    @Test
    void theSpecDeclaresTheCodeTheCliActuallyUsesAndAtLeastOneCommand() throws Exception {
        JsonNode spec = spec();

        assertEquals(PARTIAL, spec.get("partialResultExitCode").asInt());
        assertTrue(commands(spec).contains(INVENTORY), "without `project list` every consumer would treat 3 as a failure again");
        for (List<String> command : commands(spec)) {
            assertTrue(command.size() <= 2, "a command is one word, or `project <verb>`: " + command);
        }
    }

    /**
     * Les commandes de lecture viennent de la garde de câblage (elle-même alimentée par la table de routes) : une
     * commande ajoutée à la CLI y est classée, donc sondée ici. Elle rend 3 si et seulement si elle est listée.
     */
    @Test
    void aCommandExitsWithThePartialCodeBesideADamagedEntryIfAndOnlyIfItIsListed() throws Exception {
        registryWithADamagedEntry();
        List<List<String>> listed = commands(spec());
        List<String> mismatches = new ArrayList<>();
        int probed = 0;

        for (List<String> forms : LazyWiringGuardTest.readInvocations().values()) {
            for (String form : forms) {
                List<String> arguments = new ArrayList<>();
                for (String token : form.split(" ")) {
                    arguments.add(LazyWiringGuardTest.PROJECT.equals(token) ? "alpha"
                            : LazyWiringGuardTest.ROOT.equals(token) ? temp.resolve("alpha").toString() : token);
                }
                boolean isListed = listed.stream().anyMatch(prefix -> arguments.size() >= prefix.size()
                        && arguments.subList(0, prefix.size()).equals(prefix));
                Result result = run(arguments);
                probed++;
                if ((result.exit() == PARTIAL) != isListed) {
                    mismatches.add(String.join(" ", arguments) + " -> exit " + result.exit()
                            + (isListed ? " (listed, expected 3)" : " (not listed, must not exit 3)") + " " + result.error());
                }
            }
        }

        assertTrue(probed > 20, "the table of read commands was not probed: " + probed);
        assertEquals(List.of(), mismatches, "scripts/lib/partial-result-commands.json and the CLI disagree");
    }

    private void registryWithADamagedEntry() throws Exception {
        home = Files.createDirectories(temp.resolve("home"));
        Path alpha = Files.createDirectories(temp.resolve("alpha"));
        Path beta = Files.createDirectories(temp.resolve("beta"));
        assertEquals(0, run(List.of("project", "add", alpha.toString(), "--name", "alpha")).exit());
        assertEquals(0, run(List.of("project", "add", beta.toString(), "--name", "beta")).exit());
        Path projects = home.resolve("registry").resolve("projects");
        try (var files = Files.list(projects)) {
            for (Path file : files.toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.contains("displayName=beta")) {
                    Files.writeString(file, content.replaceAll("createdAt=.*", "createdAt=not-a-date"), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private Result run(List<String> arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exit = MinosLauncher.run(home, arguments.toArray(String[]::new), output, error);
        return new Result(exit, output.toString(), error.toString());
    }

    private static List<List<String>> commands(JsonNode spec) {
        List<List<String>> commands = new ArrayList<>();
        for (JsonNode command : spec.get("commands")) commands.add(List.of(command.asText().split(" ")));
        return commands;
    }

    private static JsonNode spec() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && candidate != null; depth++, candidate = candidate.getParent()) {
            Path file = candidate.resolve("scripts").resolve("lib").resolve("partial-result-commands.json");
            if (Files.isRegularFile(file) && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        throw new IOException("scripts/lib/partial-result-commands.json not found from " + Path.of("").toAbsolutePath());
    }

    private record Result(int exit, String output, String error) {
    }
}

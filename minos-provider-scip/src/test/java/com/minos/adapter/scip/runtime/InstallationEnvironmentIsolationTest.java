package com.minos.adapter.scip.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les commandes d'installation et de sondage (coursier, npm, dotnet, go) ne doivent recevoir aucun secret
 * de MINOS. Le test lance une JVM fille dont l'environnement contient les secrets, puis fait passer par
 * chacune des trois méthodes {@code run} un petit programme Java qui imprime l'environnement reçu.
 */
class InstallationEnvironmentIsolationTest {

    private static final String[] SECRETS = {
            "MINOS_TEAM_KEY_ALPHA", "MINOS_TEAM_TOKEN", "GITHUB_TOKEN", "NPM_TOKEN"};

    @Test
    void noInstallationOrProbeCommandReceivesAMinosOrCiSecret(@TempDir Path temp) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                Child.class.getName(), temp.toString());
        for (String secret : SECRETS) {
            builder.environment().put(secret, "valeur-secrete-" + secret);
        }
        builder.environment().put("HTTPS_PROXY", "http://proxy.example:3128");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        for (String report : List.of("java.env", "python.env", "polyglot.env", "polyglot-probe.env")) {
            String environment = Files.readString(temp.resolve(report));
            assertTrue(environment.toUpperCase(java.util.Locale.ROOT).contains("PATH="), report + " sans PATH :\n" + environment);
            for (String secret : SECRETS) {
                assertFalse(environment.contains(secret), report + " reçoit " + secret);
            }
            assertFalse(environment.contains("valeur-secrete"), report);
        }
    }

    /** Runs in a child JVM so that the secrets really are present in the process environment. */
    public static final class Child {
        public static void main(String[] arguments) throws Exception {
            Path directory = Path.of(arguments[0]);
            List<String> command = List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), Printer.class.getName());
            Duration timeout = Duration.ofSeconds(60);

            ManagedScipProviderRuntimeManager.run(command, directory, directory.resolve("java.log"), timeout);
            Files.move(directory.resolve("java.log"), directory.resolve("java.env"));
            ManagedScipPythonRuntimeManager.run(command, directory, directory.resolve("python.log"), timeout);
            Files.move(directory.resolve("python.log"), directory.resolve("python.env"));
            Files.writeString(directory.resolve("polyglot.env"),
                    ManagedPolyglotScipRuntimeManager.run(command, directory, timeout,
                            Map.of("GOPROXY", "https://proxy.golang.org", "MINOS_TEAM_TOKEN", "declared")).output());
            Files.writeString(directory.resolve("polyglot-probe.env"),
                    ManagedPolyglotScipRuntimeManager.run(command, directory, timeout, Map.of()).output());
        }
    }

    /** Prints the environment it received, one {@code NAME=value} per line. */
    public static final class Printer {
        public static void main(String[] arguments) {
            System.getenv().forEach((name, value) -> System.out.println(name + "=" + value));
        }
    }
}

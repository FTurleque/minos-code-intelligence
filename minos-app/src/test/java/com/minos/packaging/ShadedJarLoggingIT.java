package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les erreurs journalisées par les bibliothèques (SDK MCP, JGit) passent par SLF4J ; le jar ombré doit
 * les écrire sur stderr, jamais les perdre (slf4j-nop) et jamais les mettre sur stdout, réservé au
 * protocole MCP.
 */
class ShadedJarLoggingIT {

    private static final String SERVICE_FILE = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider";
    private static final String MARKER = "erreur-de-bibliotheque-visible";

    @Test
    void theShadedJarHasExactlyTheJulSlf4jProvider() throws Exception {
        try (ZipFile jar = new ZipFile(shadedJar().toFile())) {
            ZipEntry entry = jar.getEntry(SERVICE_FILE);
            assertNotNull(entry, "aucun fournisseur SLF4J dans le jar ombré");
            try (InputStream input = jar.getInputStream(entry)) {
                List<String> providers = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                        .lines().map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
                assertEquals(List.of("org.slf4j.jul.JULServiceProvider"), providers);
            }
        }
    }

    @Test
    void aLibraryErrorReachesStderrAndNeverStdout() throws Exception {
        Path classes = Path.of(LoggingProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(),
                "-cp", shadedJar() + System.getProperty("path.separator") + classes,
                LoggingProbe.class.getName())
                .start();

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(completed, "la sonde de journalisation n'a pas terminé");
        assertEquals(0, process.exitValue(), stderr);
        assertTrue(stderr.contains(MARKER), "l'erreur journalisée est perdue :\n" + stderr);
        assertFalse(stdout.contains(MARKER), "journal sur stdout, réservé au protocole MCP :\n" + stdout);
        assertFalse(stderr.contains("No SLF4J providers were found"), stderr);
    }

    private static Path shadedJar() {
        return Path.of(System.getProperty("minos.shaded.jar")).toAbsolutePath().normalize();
    }

    /** Exécutée contre le jar ombré : journalise une erreur par SLF4J, comme le SDK MCP et JGit. */
    public static final class LoggingProbe {
        public static void main(String[] arguments) {
            org.slf4j.LoggerFactory.getLogger("probe").error(MARKER);
        }
    }
}

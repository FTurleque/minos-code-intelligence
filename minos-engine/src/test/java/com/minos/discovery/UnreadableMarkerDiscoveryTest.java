package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q14 : un fichier marqueur qui existe mais ne peut pas être ouvert ne fait pas échouer la découverte : il est
 * traité comme absent, ce que le flux a toujours fait. Le silence en revanche est levé : une trace WARNING nomme
 * le fichier (relatif au projet) et la classe de l'exception, une seule fois par fichier, et au plus une dizaine
 * par découverte. Les cas normaux restent muets : marqueur absent, entrée de répertoire (la découverte sonde
 * aussi des répertoires, que l'ouverture confinée refuse).
 */
class UnreadableMarkerDiscoveryTest {

    @Test
    void anUnreadableMarkerIsJournaledOnceAndDiscoveryContinues(@TempDir Path root) throws Exception {
        project(root);
        unreadable(Files.writeString(root.resolve("pom.xml"), "<project/>"));

        try (LogCapture logs = new LogCapture(ProjectIgnorePolicy.class)) {
            ProjectDiscovery discovery = new ProjectDiscoveryService().discover(root);

            // Le flux ne change pas : le marqueur illisible est absent, le reste est découvert.
            assertEquals(Set.of(BuildSystem.GRADLE), discovery.buildSystems());
            assertEquals(Set.of(Language.JAVA), discovery.languages());
            List<String> warnings = logs.warnings();
            assertEquals(1, warnings.size(), "un fichier, une trace : " + warnings);
            assertTrue(warnings.getFirst().contains("'pom.xml'"), warnings.getFirst());
            assertTrue(warnings.getFirst().contains("AccessDeniedException"), warnings.getFirst());
            assertFalse(warnings.getFirst().contains(root.toString()), "aucun chemin absolu : " + warnings.getFirst());
        }
    }

    @Test
    void manyUnreadableMarkersProduceABoundedNumberOfTraces(@TempDir Path root) throws Exception {
        project(root);
        for (int index = 0; index < 40; index++) {
            Path module = Files.createDirectories(root.resolve("module-" + index));
            unreadable(Files.writeString(module.resolve("package.json"), "{}"));
        }

        try (LogCapture logs = new LogCapture(ProjectIgnorePolicy.class)) {
            new ProjectDiscoveryService().discover(root);

            int reported = logs.warnings().size();
            assertTrue(reported >= 1, "l'échec n'est pas silencieux");
            assertTrue(reported <= 11, "dix traces au plus, plus une de synthèse : " + reported);
        }
    }

    @Test
    void anAbsentMarkerAndDirectoryEntriesAreTheNormalCaseAndStaySilent(@TempDir Path root) throws Exception {
        project(root);
        // Un répertoire qui porte le nom d'un marqueur : refusé par l'ouverture confinée, sans être une erreur.
        Files.createDirectory(root.resolve("pom.xml"));

        try (LogCapture logs = new LogCapture(ProjectIgnorePolicy.class)) {
            ProjectDiscovery discovery = new ProjectDiscoveryService().discover(root);

            assertEquals(Set.of(BuildSystem.GRADLE), discovery.buildSystems());
            assertEquals(Set.of(Language.JAVA), discovery.languages());
            assertEquals(List.of(), logs.warnings());
        }
    }

    @Test
    void theReportOfUnreadableFilesIsDeduplicatedAndBounded(@TempDir Path root) throws Exception {
        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);
        IOException failure = new IOException("private detail " + root);

        try (LogCapture logs = new LogCapture(ProjectIgnorePolicy.class)) {
            policy.reportUnreadable(Path.of("a", "pom.xml"), failure);
            policy.reportUnreadable(Path.of("a", "pom.xml"), failure);
            for (int index = 0; index < 100; index++) {
                policy.reportUnreadable(Path.of("module-" + index, "pom.xml"), failure);
            }

            List<String> warnings = logs.warnings();
            assertEquals(11, warnings.size(), "dix traces, plus une de synthèse : " + warnings);
            assertEquals(1, warnings.stream().filter(message -> message.contains("'" + Path.of("a", "pom.xml") + "'")).count());
            assertTrue(warnings.getLast().contains("more than 10 unreadable project files"), warnings.getLast());
            assertFalse(String.join("\n", warnings).contains("private detail"), "jamais le message de l'exception");
            assertFalse(String.join("\n", warnings).contains(root.toString()), "jamais de chemin absolu");
        }
    }

    /** Un fichier qui existe mais ne s'ouvre pas ; le test est ignoré là où on ne peut pas le produire (administrateur). */
    private static void unreadable(Path file) throws IOException {
        Assumptions.assumeTrue(UnreadableFile.deny(file), "impossible de rendre un fichier illisible ici");
    }

    /** Un projet Gradle minimal, découvrable : un marqueur lisible et un source Java. */
    private static void project(Path root) throws IOException {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path sources = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(sources.resolve("App.java"), "class App { }\n");
    }
}

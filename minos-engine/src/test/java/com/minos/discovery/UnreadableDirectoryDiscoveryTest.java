package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.testsupport.LogCapture;
import com.minos.testsupport.UnreadableDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-D01 : un seul répertoire illisible rendait le projet inindexable. Le JDK ouvre un répertoire AVANT
 * {@code preVisitDirectory} : un répertoire illisible va directement à {@code visitFileFailed}, même s'il est ignoré
 * ou durci. Un répertoire ignoré ou durci est écarté avec une trace bornée ; un répertoire non ignoré reste un échec
 * fail-closed, mais lisible et du même type d'exception ; la racine illisible échoue toujours. Les tests s'ignorent
 * pour un compte root ou administrateur élevé, qui peut encore lister le répertoire.
 */
class UnreadableDirectoryDiscoveryTest {

    @Test
    void anIgnoredUnreadableDirectoryIsSkippedAndTheProjectIsDiscovered(@TempDir Path root) throws Exception {
        Path project = javaProject(root);
        Files.writeString(project.resolve(".gitignore"), "pgdata/\n");
        Path pgdata = Files.createDirectories(project.resolve("pgdata"));
        Files.writeString(pgdata.resolve("PG_VERSION"), "16");

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(pgdata);
             LogCapture logs = new LogCapture(ProjectIgnorePolicy.class)) {
            ProjectDiscovery discovery = new ProjectDiscoveryService().discover(project);

            assertEquals(Set.of(BuildSystem.MAVEN), discovery.buildSystems());
            assertEquals(Set.of(Language.JAVA), discovery.languages());
            List<String> warnings = logs.warnings();
            assertEquals(1, warnings.size(), "one trace for the skipped directory: " + warnings);
            assertTrue(warnings.getFirst().contains("'pgdata'"), warnings.getFirst());
            assertFalse(warnings.getFirst().contains(project.toString()), "no absolute path: " + warnings.getFirst());
        }
    }

    @Test
    void aMinosignoredUnreadableDirectoryIsSkipped(@TempDir Path root) throws Exception {
        Path project = javaProject(root);
        Files.writeString(project.resolve(".minosignore"), "/volumes/\n");
        Path volumes = Files.createDirectories(project.resolve("volumes/db"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(volumes)) {
            ProjectDiscovery discovery = new ProjectDiscoveryService().discover(project);

            assertEquals(Set.of(Language.JAVA), discovery.languages());
        }
    }

    @Test
    void aHardenedUnreadableDirectoryIsSkippedWithoutAnyIgnoreRule(@TempDir Path root) throws Exception {
        Path project = javaProject(root);
        Path nodeModules = Files.createDirectories(project.resolve("node_modules"));
        Files.writeString(nodeModules.resolve("package.json"), "{}");

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(nodeModules)) {
            ProjectDiscovery discovery = new ProjectDiscoveryService().discover(project);

            assertEquals(Set.of(Language.JAVA), discovery.languages());
            assertFalse(discovery.buildSystems().contains(BuildSystem.NPM), "a hardened directory contributes nothing");
        }
    }

    @Test
    void aNestedIgnoredUnreadableDirectoryIsSkipped(@TempDir Path root) throws Exception {
        Path project = javaProject(root);
        Files.writeString(project.resolve(".gitignore"), "cache/\n");
        Path cache = Files.createDirectories(project.resolve("modules/app/cache"));
        Files.createDirectories(project.resolve("modules/app/src"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(cache)) {
            assertEquals(Set.of(Language.JAVA), new ProjectDiscoveryService().discover(project).languages());
        }
    }

    @Test
    void anUnreadableDirectoryThatIsNotIgnoredStillFailsButWithAnActionableMessageOfTheSameType(@TempDir Path root)
            throws Exception {
        Path project = javaProject(root);
        Path database = Files.createDirectories(project.resolve("data/db"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(database)) {
            IOException failure = assertThrows(AccessDeniedException.class,
                    () -> new ProjectDiscoveryService().discover(project));

            String message = failure.getMessage();
            assertTrue(message.contains("data/db"), "names the directory, relative to the project: " + message);
            assertTrue(message.contains(".minosignore"), "suggests the way out: " + message);
            assertFalse(message.contains(project.toString()), "no absolute path in the message: " + message);
            assertFalse(message.contains(root.toString()), message);
        }
    }

    /** La sortie documentée dans le dépannage : ajouter le répertoire à .minosignore transforme l'échec en écart. */
    @Test
    void addingTheDirectoryToMinosignoreAsDocumentedTurnsTheFailureIntoASkip(@TempDir Path root) throws Exception {
        Path project = javaProject(root);
        Path database = Files.createDirectories(project.resolve("data/db"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(database)) {
            assertThrows(AccessDeniedException.class, () -> new ProjectDiscoveryService().discover(project));

            // What the documented command does: echo 'data/db/' >> .minosignore
            Files.writeString(project.resolve(".minosignore"), "data/db/\n");

            assertEquals(Set.of(Language.JAVA), new ProjectDiscoveryService().discover(project).languages());
        }
    }

    @Test
    void anUnreadableProjectRootStillFails(@TempDir Path root) throws Exception {
        Path project = javaProject(root);

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(project)) {
            assertThrows(IOException.class, () -> new ProjectDiscoveryService().discover(project));
        }
    }

    private static Path javaProject(Path root) throws IOException {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/java/A.java"), "class A {}");
        return project;
    }
}

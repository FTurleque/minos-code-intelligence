package com.minos.incremental;

import com.minos.testsupport.UnreadableDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-D01 pour l'empreinte : un répertoire illisible ignoré ou durci ne fait plus échouer la capture (ni de la
 * racine, ni d'un scope, ni du répertoire d'outillage {@code .mvn/}) et ne contribue aucun fichier au hachage ; un
 * répertoire non ignoré échoue de façon actionnable ; la racine du scope illisible échoue toujours. Ignorés pour un
 * compte root ou administrateur élevé.
 */
class ProjectFingerprintUnreadableDirectoryTest {

    private final ProjectFingerprintService service = new ProjectFingerprintService();

    @Test
    void anIgnoredUnreadableDirectoryDoesNotFailTheCaptureAndContributesNothing(@TempDir Path root) throws Exception {
        Path project = project(root);
        Files.writeString(project.resolve(".gitignore"), "pgdata/\n");
        Path pgdata = Files.createDirectories(project.resolve("pgdata"));
        Files.writeString(pgdata.resolve("PG_VERSION"), "16");
        ProjectFingerprint readable = service.capture(project);

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(pgdata)) {
            ProjectFingerprint unreadable = service.capture(project);

            assertEquals(readable, unreadable, "an ignored directory is invisible to the fingerprint, readable or not");
            assertTrue(unreadable.files().stream().noneMatch(file -> file.relativePath().startsWith("pgdata")));
        }
    }

    @Test
    void aHardenedUnreadableDirectoryDoesNotFailTheCapture(@TempDir Path root) throws Exception {
        Path project = project(root);
        Path target = Files.createDirectories(project.resolve("target/classes"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(target)) {
            assertEquals(2, service.capture(project).files().size(), "pom.xml and App.java only");
        }
    }

    @Test
    void aScopeCaptureSkipsAnIgnoredUnreadableDirectoryToo(@TempDir Path root) throws Exception {
        Path project = project(root);
        Files.writeString(project.resolve(".gitignore"), "cache/\n");
        Path moduleSources = Files.createDirectories(project.resolve("modules/app/src"));
        Files.writeString(moduleSources.resolve("B.java"), "class B {}");
        Path cache = Files.createDirectories(project.resolve("modules/app/cache"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(cache)) {
            ProjectFingerprint scoped = service.captureScope(project, Path.of("modules/app"));

            assertTrue(scoped.files().stream().anyMatch(file -> file.relativePath().equals("modules/app/src/B.java")));
        }
    }

    @Test
    void anIgnoredUnreadableDirectoryInsideTheToolingDirectoryIsSkipped(@TempDir Path root) throws Exception {
        Path project = project(root);
        Files.writeString(project.resolve(".gitignore"), ".mvn/cache/\n");
        Files.createDirectories(project.resolve("modules/app/src"));
        Path wrapper = Files.createDirectories(project.resolve(".mvn/wrapper"));
        Files.writeString(wrapper.resolve("maven-wrapper.properties"), "distributionUrl=x");
        Path cache = Files.createDirectories(project.resolve(".mvn/cache"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(cache)) {
            ProjectFingerprint scoped = service.captureScope(project, Path.of("modules/app"));

            assertTrue(scoped.files().stream().anyMatch(file -> file.relativePath().endsWith("maven-wrapper.properties")),
                    "the readable part of the tooling directory still counts");
        }
    }

    @Test
    void anUnreadableDirectoryThatIsNotIgnoredStillFailsWithAnActionableMessageOfTheSameType(@TempDir Path root)
            throws Exception {
        Path project = project(root);
        Path database = Files.createDirectories(project.resolve("data/db"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(database)) {
            IOException failure = assertThrows(AccessDeniedException.class, () -> service.capture(project));

            assertTrue(failure.getMessage().contains("data/db"), failure.getMessage());
            assertTrue(failure.getMessage().contains(".minosignore"), failure.getMessage());
            assertFalse(failure.getMessage().contains(project.toString()), failure.getMessage());
        }
    }

    @Test
    void anUnreadableScopeRootStillFails(@TempDir Path root) throws Exception {
        Path project = project(root);
        Path scope = Files.createDirectories(project.resolve("modules/app"));

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(scope)) {
            assertThrows(IOException.class, () -> service.captureScope(project, Path.of("modules/app")));
        }
    }

    private static Path project(Path root) throws IOException {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/java/App.java"), "class App {}");
        return project;
    }
}

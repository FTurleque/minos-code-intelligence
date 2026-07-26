package com.minos.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFingerprintServiceTest {

    private final ProjectFingerprintService service = new ProjectFingerprintService();

    @Test
    void capturesVisibleContentDeterministicallyWithoutDependingOnAbsolutePathOrTimestamps(@TempDir Path root)
            throws Exception {
        Path first = root.resolve("first");
        Path second = root.resolve("second");
        createEquivalentProject(first);
        createEquivalentProject(second);

        ProjectFingerprint firstCapture = service.capture(first);
        ProjectFingerprint secondCapture = service.capture(second);
        ProjectChangeSet noChanges = service.compare(firstCapture, secondCapture);

        assertEquals(firstCapture, service.capture(first));
        assertEquals(firstCapture.projectSha256(), secondCapture.projectSha256());
        assertEquals(firstCapture.buildSha256(), secondCapture.buildSha256());
        assertFalse(noChanges.projectChanged());
        assertFalse(noChanges.buildDefinitionChanged());
        assertEquals(0, noChanges.changedFileCount());
        assertEquals(5, noChanges.unchangedFiles().size());
        assertEquals(
                List.of(".gitignore", ".minosignore", "package.json", "pom.xml", "src/main/java/App.java"),
                firstCapture.files().stream().map(FileFingerprint::relativePath).toList()
        );

        Path source = first.resolve("src/main/java/App.java");
        Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        ProjectFingerprint afterTimestampChange = service.capture(first);
        assertEquals(firstCapture, afterTimestampChange);

        Files.writeString(first.resolve("ignored.log"), "changed ignored content");
        Files.writeString(first.resolve("target/generated.txt"), "changed generated content");
        assertEquals(firstCapture, service.capture(first));
    }

    @Test
    void classifiesAddedModifiedDeletedAndUnchangedFilesAndDetectsBuildChanges(@TempDir Path root)
            throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("pom.xml"), "<project><version>1</version></project>");
        Files.writeString(root.resolve("src/A.java"), "class A { int value = 1; }");
        Files.writeString(root.resolve("src/Keep.java"), "class Keep {}");
        Files.writeString(root.resolve("src/Delete.java"), "class Delete {}");

        ProjectFingerprint before = service.capture(root);

        Files.writeString(root.resolve("pom.xml"), "<project><version>2</version></project>");
        Files.writeString(root.resolve("src/A.java"), "class A { int value = 2; }");
        Files.delete(root.resolve("src/Delete.java"));
        Files.writeString(root.resolve("src/New.java"), "class New {}");

        ProjectFingerprint after = service.capture(root);
        ProjectChangeSet changes = service.compare(before, after);

        assertTrue(changes.projectChanged());
        assertTrue(changes.buildDefinitionChanged());
        assertEquals(4, changes.changedFileCount());
        assertEquals(List.of("src/New.java"), changes.addedFiles());
        assertEquals(List.of("pom.xml", "src/A.java"), changes.modifiedFiles());
        assertEquals(List.of("src/Delete.java"), changes.deletedFiles());
        assertEquals(List.of("src/Keep.java"), changes.unchangedFiles());

        Path gradle = root.resolve("gradle");
        Files.createDirectories(gradle);
        Files.writeString(gradle.resolve("build.gradle.kts"), "plugins { java }");
        ProjectFingerprint gradleBefore = service.capture(gradle);
        Files.writeString(gradle.resolve("build.gradle.kts"), "plugins { java-library }");
        assertTrue(service.compare(gradleBefore, service.capture(gradle)).buildDefinitionChanged());

        Path custom = root.resolve("custom");
        Files.createDirectories(custom);
        Files.writeString(custom.resolve("custom.build"), "version=1");
        ProjectFingerprintService customService = new ProjectFingerprintService(
                new BuildDescriptorPolicy(Set.of("custom.build")));
        ProjectFingerprint customBefore = customService.capture(custom);
        Files.writeString(custom.resolve("custom.build"), "version=2");
        assertTrue(customService.compare(customBefore, customService.capture(custom)).buildDefinitionChanged());
    }

    @Test
    void sourceOnlyChangeDoesNotInventABuildDefinitionChange(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("src/App.java"), "class App {}");
        ProjectFingerprint before = service.capture(root);

        Files.writeString(root.resolve("src/App.java"), "class App { void changed() {} }");
        ProjectFingerprint after = service.capture(root);
        ProjectChangeSet changes = service.compare(before, after);

        assertTrue(changes.projectChanged());
        assertFalse(changes.buildDefinitionChanged());
        assertEquals(List.of("src/App.java"), changes.modifiedFiles());
        assertEquals(List.of("pom.xml"), changes.unchangedFiles());
    }

    @Test
    void fingerprintsRootIgnoreControlFilesEvenWhenTheyIgnoreThemselves(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve(".minosignore"), ".minosignore\n*.tmp\n");
        ProjectFingerprint before = service.capture(root);

        Files.writeString(root.resolve(".minosignore"), ".minosignore\n*.log\n");
        ProjectFingerprint after = service.capture(root);
        ProjectChangeSet changes = service.compare(before, after);

        assertNotEquals(before.projectSha256(), after.projectSha256());
        assertTrue(changes.projectChanged());
        assertFalse(changes.buildDefinitionChanged());
        assertEquals(List.of(".minosignore"), changes.modifiedFiles());
        assertTrue(after.files().stream().anyMatch(file -> ".minosignore".equals(file.relativePath())));
    }

    private static void createEquivalentProject(Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve("secret"));
        Files.writeString(root.resolve(".gitignore"), "*.log\n");
        Files.writeString(root.resolve(".minosignore"), "secret/**\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("package.json"), "{\"name\":\"fixture\"}");
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}");
        Files.writeString(root.resolve("ignored.log"), "ignored");
        Files.writeString(root.resolve("target/generated.txt"), "generated");
        Files.writeString(root.resolve("secret/token.txt"), "secret");
    }
}

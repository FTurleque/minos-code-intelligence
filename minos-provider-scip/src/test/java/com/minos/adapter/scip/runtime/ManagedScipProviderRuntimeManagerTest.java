package com.minos.adapter.scip.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedScipProviderRuntimeManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void probesPinnedScipJavaWithSystemJdkAndExplicitMainClass() {
        Path coursier = temporaryDirectory.resolve("cs.exe");

        List<String> command = ManagedScipProviderRuntimeManager.scipJavaInstallationProbe(coursier);

        assertEquals(List.of(
                coursier.toString(),
                "launch", "org.scip-code:scip-java:0.13.1",
                "--jvm", "system",
                "--main", "org.scip_code.scip_java.ScipJava",
                "--", "--version"
        ), command);
    }

    @Test
    void requiresThePinnedVersionInTheInstallationProbeLog() throws IOException {
        Path validLog = temporaryDirectory.resolve("valid.log");
        Path invalidLog = temporaryDirectory.resolve("invalid.log");
        Files.writeString(validLog, "scip-java version 0.13.1\n");
        Files.writeString(invalidLog, "scip-java version 0.12.3\n");

        assertDoesNotThrow(() -> ManagedScipProviderRuntimeManager.requireExpectedScipJavaVersion(validLog));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ManagedScipProviderRuntimeManager.requireExpectedScipJavaVersion(invalidLog));
        assertTrue(failure.getMessage().contains("scip-java version 0.13.1"));
    }

    @Test
    void resolvesGitBashBesideTheGitInstallationInsteadOfAnUnrelatedBash() throws IOException {
        Path git = temporaryDirectory.resolve("Git").resolve("cmd").resolve("git.exe");
        Path gitBash = temporaryDirectory.resolve("Git").resolve("bin").resolve("bash.exe");
        Files.createDirectories(git.getParent());
        Files.createDirectories(gitBash.getParent());
        Files.createFile(git);
        Files.createFile(gitBash);

        assertEquals(gitBash.toAbsolutePath().normalize(),
                ManagedScipProviderRuntimeManager.gitBashForGit(git).orElseThrow());
    }
}

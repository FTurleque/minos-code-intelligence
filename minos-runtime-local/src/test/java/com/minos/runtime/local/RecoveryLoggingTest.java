package com.minos.runtime.local;

import com.minos.testsupport.LogCapture;
import com.minos.testsupport.UnreadableDirectory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les échecs de récupération de fichiers sont journalisés sans chemin absolu ni cause (AUD-QUA-06). Les cas
 * rendent un répertoire illisible : ignorés pour un compte root ou administrateur élevé.
 */
class RecoveryLoggingTest {

    private static void assertOnlyRelativeAndClass(List<String> warnings, Path home, String expected) {
        assertTrue(warnings.stream().anyMatch(message -> message.contains(expected)), warnings.toString());
        for (String message : warnings) {
            assertFalse(message.contains(home.toString()), "chemin absolu dans le journal : " + message);
            assertFalse(message.contains(System.getProperty("user.name")), "nom d'utilisateur dans le journal : " + message);
        }
    }

    @Test
    void anUnreadableRunDirectoryIsReportedByRelativeNameAndClass(@TempDir Path home) throws Exception {
        Path runsRoot = Files.createDirectories(home.resolve("runs"));
        Path run = Files.createDirectories(runsRoot.resolve("run-1"));
        Files.writeString(run.resolve("residue.txt"), "x");

        try (var _ = UnreadableDirectory.denyOrSkip(run);
             LogCapture logs = new LogCapture(ProviderResidueReclamation.class)) {
            ProviderResidueReclamation.reclaim(runsRoot, run, Set.of());

            assertOnlyRelativeAndClass(logs.warnings(), home, "enumerate provider residue in 'run-1'");
        }
    }

    @Test
    void anUnreadableResidueDirectoryIsReportedByRelativeNameAndClass(@TempDir Path home) throws Exception {
        Path runsRoot = Files.createDirectories(home.resolve("runs"));
        Path run = Files.createDirectories(runsRoot.resolve("run-1"));
        Path residue = Files.createDirectories(run.resolve("pgdata"));
        Files.writeString(residue.resolve("PG_VERSION"), "16");

        try (var _ = UnreadableDirectory.denyOrSkip(residue);
             LogCapture logs = new LogCapture(ProviderResidueReclamation.class)) {
            ProviderResidueReclamation.reclaim(runsRoot, run, Set.of());

            assertOnlyRelativeAndClass(logs.warnings(), home, "reclaim provider residue 'pgdata'");
        }
    }

    @Test
    void anUnreadableQuarantineIsReportedWithoutAnyPath(@TempDir Path home) throws Exception {
        Path runsRoot = Files.createDirectories(home.resolve("runs"));
        Path quarantine = Files.createDirectories(runsRoot.resolve(RunDirectoryRetention.QUARANTINE_DIRECTORY));

        try (var _ = UnreadableDirectory.denyOrSkip(quarantine);
             LogCapture logs = new LogCapture(RunDirectoryRetention.class)) {
            RunDirectoryRetention.prune(runsRoot, null);

            assertOnlyRelativeAndClass(logs.warnings(), home, "enumerate the run quarantine:");
        }
    }

    @Test
    void aQuarantinedRunThatCannotBeDeletedIsReportedByRelativeNameAndClass(@TempDir Path home) throws Exception {
        PosixFileAttributeView posix = Files.getFileAttributeView(home, PosixFileAttributeView.class);
        Assumptions.assumeTrue(posix != null, "needs POSIX permissions: a read-only directory refuses to lose a file");
        Path runsRoot = Files.createDirectories(home.resolve("runs"));
        Path quarantine = Files.createDirectories(runsRoot.resolve(RunDirectoryRetention.QUARANTINE_DIRECTORY));
        Path stuck = Files.createDirectories(quarantine.resolve("run-stuck"));
        Files.writeString(stuck.resolve("file"), "x");
        Files.getFileAttributeView(stuck, PosixFileAttributeView.class)
                .setPermissions(PosixFilePermissions.fromString("r-xr-xr-x"));
        try (LogCapture logs = new LogCapture(RunDirectoryRetention.class)) {
            Assumptions.assumeFalse(Files.isWritable(stuck), "a root account can still delete: nothing to observe");

            RunDirectoryRetention.prune(runsRoot, null);

            assertOnlyRelativeAndClass(logs.warnings(), home, "drain quarantined run '.quarantine/run-stuck'");
        } finally {
            Files.getFileAttributeView(stuck, PosixFileAttributeView.class)
                    .setPermissions(PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aRunThatCannotBeQuarantinedIsReportedByRelativeNameAndClass(@TempDir Path home) throws Exception {
        Path runsRoot = Files.createDirectories(home.resolve("runs"));
        // A regular file where the quarantine directory should be: quarantining a run must fail.
        Files.writeString(runsRoot.resolve(RunDirectoryRetention.QUARANTINE_DIRECTORY), "not a directory");
        Path run = Files.createDirectories(runsRoot.resolve("run-1"));
        Files.writeString(run.resolve("file"), "x");

        try (var _ = UnreadableDirectory.denyOrSkip(run);
             LogCapture logs = new LogCapture(RunDirectoryRetention.class)) {
            RunDirectoryRetention.prune(runsRoot, null);

            assertOnlyRelativeAndClass(logs.warnings(), home, "reclaim run directory 'run-1'");
        }
    }
}

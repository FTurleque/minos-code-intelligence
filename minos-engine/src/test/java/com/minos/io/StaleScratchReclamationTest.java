package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** MINOS-AUD-A02 : la primitive de balayage des résidus de run mort. L'horloge est injectée, jamais lue. */
class StaleScratchReclamationTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration LIFETIME = Duration.ofHours(24);

    @TempDir
    Path root;

    @Test
    void anOldChildIsReclaimedWithItsWholeContentAndARecentOneIsKept() throws Exception {
        Path old = residue("old", NOW.minus(Duration.ofHours(48)));
        Path recent = residue("recent", NOW.minus(Duration.ofMinutes(5)));

        StaleScratchReclamation.Outcome outcome = sweep(Set.of());

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent.resolve("nested/file.txt")));
        assertEquals(2, outcome.examined());
        assertEquals(1, outcome.reclaimed());
        assertEquals(0, outcome.failed());
        assertFalse(outcome.budgetExhausted());
    }

    @Test
    void theThresholdIsStrictAtTheLifetimeBoundary() throws Exception {
        Path exactly = residue("exactly", NOW.minus(LIFETIME));
        Path justOlder = residue("just-older", NOW.minus(LIFETIME).minusMillis(1));
        Path justYounger = residue("just-younger", NOW.minus(LIFETIME).plusMillis(1));

        sweep(Set.of());

        assertTrue(Files.exists(exactly), "a residue exactly at the lifetime is not strictly older");
        assertFalse(Files.exists(justOlder));
        assertTrue(Files.exists(justYounger));
    }

    @Test
    void aResidueDatedInTheFutureIsNeverReclaimed() throws Exception {
        Path future = residue("future", NOW.plus(Duration.ofHours(72)));

        StaleScratchReclamation.Outcome outcome = sweep(Set.of());

        assertTrue(Files.exists(future), "a clock jump must never make a residue look old");
        assertEquals(0, outcome.reclaimed());
    }

    @Test
    void aProtectedChildIsKeptWhateverItsAge() throws Exception {
        Path current = residue("current-run", NOW.minus(Duration.ofDays(30)));
        Path other = residue("other-run", NOW.minus(Duration.ofDays(30)));

        sweep(Set.of(current));

        assertTrue(Files.exists(current));
        assertFalse(Files.exists(other));
    }

    @Test
    void onlyChildrenMatchingTheNamePredicateAreCandidates() throws Exception {
        Path temporary = residue(".entry-1.tmp", NOW.minus(Duration.ofDays(2)));
        Path validEntry = residue("valid-cache-entry", NOW.minus(Duration.ofDays(2)));

        StaleScratchReclamation.reclaim(root, name -> name.startsWith(".entry-") && name.endsWith(".tmp"), Set.of(), new StaleScratchReclamation.Policy(LIFETIME, NOW, 100, 100), message -> { });

        assertFalse(Files.exists(temporary));
        assertTrue(Files.exists(validEntry), "an entry that is not a scratch residue is never touched");
    }

    @Test
    void theNumberOfCandidatesPerCallIsBoundedAndTheRestIsLeftForTheNextCall() throws Exception {
        for (int index = 0; index < 5; index++) residue("old-" + index, NOW.minus(Duration.ofDays(3)));

        StaleScratchReclamation.Outcome first = StaleScratchReclamation.reclaim(
                root, name -> true, Set.of(), new StaleScratchReclamation.Policy(LIFETIME, NOW, 100, 2), message -> { });
        assertEquals(2, first.reclaimed());
        assertTrue(first.budgetExhausted());
        assertEquals(3, count());

        StaleScratchReclamation.reclaim(root, name -> true, Set.of(), new StaleScratchReclamation.Policy(LIFETIME, NOW, 100, 100), message -> { });
        assertEquals(0, count());
    }

    @Test
    void theNumberOfExaminedEntriesPerCallIsBounded() throws Exception {
        for (int index = 0; index < 6; index++) residue("recent-" + index, NOW.minus(Duration.ofMinutes(1)));

        StaleScratchReclamation.Outcome outcome = StaleScratchReclamation.reclaim(
                root, name -> true, Set.of(), new StaleScratchReclamation.Policy(LIFETIME, NOW, 3, 100), message -> { });

        assertEquals(3, outcome.examined());
        assertTrue(outcome.budgetExhausted());
        assertEquals(6, count());
    }

    @Test
    void aMissingRootOrARootThatIsAFileDoesNothingAndNeverThrows() throws Exception {
        List<String> warnings = new ArrayList<>();
        StaleScratchReclamation.Outcome missing = StaleScratchReclamation.reclaim(
                root.resolve("absent"), name -> true, Set.of(), new StaleScratchReclamation.Policy(LIFETIME, NOW, 10, 10), warnings::add);
        Path file = Files.writeString(root.resolve("a-file"), "x");
        StaleScratchReclamation.Outcome notDirectory = StaleScratchReclamation.reclaim(
                file, name -> true, Set.of(), new StaleScratchReclamation.Policy(LIFETIME, NOW, 10, 10), warnings::add);

        assertEquals(0, missing.examined());
        assertEquals(0, notDirectory.examined());
        assertEquals(List.of(), warnings);
        assertTrue(Files.exists(file));
    }

    @Test
    void nonPositiveLifetimeAndBoundsAreRefused() {
        // La politique se valide a sa construction : c'est elle qui refuse, avant tout parcours du dossier.
        assertThrows(IllegalArgumentException.class, () -> new StaleScratchReclamation.Policy(Duration.ZERO, NOW, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new StaleScratchReclamation.Policy(LIFETIME, NOW, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new StaleScratchReclamation.Policy(LIFETIME, NOW, 10, 0));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void anOldSymbolicLinkIsRemovedWithoutWalkingOrTouchingItsTarget() throws Exception {
        Path outside = Files.createDirectories(root.resolveSibling(root.getFileName() + "-outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep");
        Path link = Files.createSymbolicLink(root.resolve("old-link"), outside);
        assumeTrue(ageLink(link, NOW.minus(Duration.ofDays(5))), "this file system cannot date a symbolic link");

        sweep(Set.of());

        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(precious), "the link target must never be walked or modified");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void anOldJunctionIsRemovedWithoutWalkingOrTouchingItsTarget() throws Exception {
        Path outside = Files.createDirectories(root.resolveSibling(root.getFileName() + "-outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep");
        Path junction = root.resolve("old-junction");
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assumeTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && Files.exists(junction),
                "a junction could not be created on this host");
        assumeTrue(ageLink(junction, NOW.minus(Duration.ofDays(5))), "this file system cannot date a junction");

        sweep(Set.of());

        assertFalse(Files.exists(junction, LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(precious), "the junction target must never be walked or modified");
    }

    @Test
    void aResidueThatCannotBeRemovedIsReportedWithoutAnAbsolutePathAndTheSweepContinues() throws Exception {
        Path stuck = residue("stuck", NOW.minus(Duration.ofDays(5)));
        Path other = residue("z-other", NOW.minus(Duration.ofDays(5)));
        List<String> warnings = new ArrayList<>();
        try (Blocker ignored = Blocker.on(stuck.resolve("nested/file.txt"))) {
            StaleScratchReclamation.Outcome outcome = StaleScratchReclamation.reclaim(
                    root, name -> true, Set.of(), new StaleScratchReclamation.Policy(LIFETIME, NOW, 100, 100), warnings::add);

            assertEquals(1, outcome.failed());
            assertEquals(1, outcome.reclaimed());
            assertFalse(Files.exists(other), "one stuck residue must not stop the others from being reclaimed");
            assertEquals(1, warnings.size(), warnings.toString());
            assertFalse(warnings.getFirst().contains(root.toString()), "the warning must carry no absolute path");
            assertTrue(warnings.getFirst().contains("stuck"));
        }
    }

    private StaleScratchReclamation.Outcome sweep(Set<Path> protectedChildren) {
        return StaleScratchReclamation.reclaim(
                root, name -> true, protectedChildren, new StaleScratchReclamation.Policy(LIFETIME, NOW, 100, 100), message -> { });
    }

    private int count() throws IOException {
        try (var stream = Files.list(root)) {
            return (int) stream.count();
        }
    }

    /** A directory with content, dated {@code modified}, as a killed run leaves it. */
    private Path residue(String name, Instant modified) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name).resolve("nested"));
        Files.writeString(directory.resolve("file.txt"), "residue");
        Files.setLastModifiedTime(directory.resolve("file.txt"), FileTime.from(modified));
        Files.setLastModifiedTime(directory, FileTime.from(modified));
        Files.setLastModifiedTime(directory.getParent(), FileTime.from(modified));
        return directory.getParent();
    }

    private static boolean ageLink(Path link, Instant modified) {
        try {
            FileTime time = FileTime.from(modified);
            Files.getFileAttributeView(link, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setTimes(time, time, time);
            return Files.readAttributes(link, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).lastModifiedTime().toInstant().isBefore(NOW.minus(LIFETIME));
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }

    /** Makes a file impossible to delete: a handle without delete sharing on Windows, a read-only directory on POSIX. */
    private static final class Blocker implements AutoCloseable {
        private final RandomAccessFile handle;
        private final Path readOnlyDirectory;

        private Blocker(RandomAccessFile handle, Path readOnlyDirectory) {
            this.handle = handle;
            this.readOnlyDirectory = readOnlyDirectory;
        }

        static Blocker on(Path file) throws IOException {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                return new Blocker(new RandomAccessFile(file.toFile(), "r"), null);
            }
            Path directory = file.getParent();
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
            assumeTrue(!Files.isWritable(directory), "directory permissions are not enforced for this account");
            return new Blocker(null, directory);
        }

        @Override
        public void close() throws IOException {
            if (handle != null) handle.close();
            if (readOnlyDirectory != null) {
                Files.setPosixFilePermissions(readOnlyDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }
}

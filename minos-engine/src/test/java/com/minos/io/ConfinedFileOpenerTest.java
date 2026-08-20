package com.minos.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial coverage for the confinement guarantee itself.
 *
 * <p>The replacement scenarios do not race a timer. {@link ConfinedFileOpener#beforeOpenForTests}
 * fires at the exact instant between the last validation and the open, so the substitution a real
 * attacker would have to win by luck happens here with certainty, on every run and every platform.
 * A test built on {@code Thread.sleep} would prove nothing about the instants it happened to
 * miss.</p>
 */
class ConfinedFileOpenerTest {

    @AfterEach
    void clearSeam() {
        ConfinedFileOpener.beforeOpenForTests = () -> { };
    }

    @Test
    void readsARegularFileInsideTheRoot(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);

        assertEquals("class Main {}", read(root, Path.of("src", "Main.java")));
    }

    @Test
    void refusesAFileReplacedByASymlinkBetweenValidationAndOpen(@TempDir Path root) throws Exception {
        Path outside = Files.writeString(
                Files.createTempFile("minos-confined-outside-", ".txt"), "SECRET", StandardCharsets.UTF_8);
        try {
            Path file = root.resolve("src/Main.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);
            assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");

            ConfinedFileOpener.beforeOpenForTests = () -> {
                try {
                    Files.delete(file);
                    Files.createSymbolicLink(file, outside);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            };

            assertThrows(ConfinedFileOpener.ConfinementException.class,
                    () -> read(root, Path.of("src", "Main.java")),
                    "a substituted link must be refused, not followed");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void refusesAFileReplacedByAnotherRegularFileWhenTheReplacementIsALink(@TempDir Path root)
            throws Exception {
        Path inside = root.resolve("src/Other.java");
        Path file = root.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);
        Files.writeString(inside, "class Other {}", StandardCharsets.UTF_8);
        assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");

        ConfinedFileOpener.beforeOpenForTests = () -> {
            try {
                Files.delete(file);
                Files.createSymbolicLink(file, inside);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };

        assertThrows(IOException.class, () -> read(root, Path.of("src", "Main.java")),
                "even a link that stays inside the root is refused: containment must not be re-decided "
                        + "from a resolved pathname");
    }

    @Test
    void survivesAFileReplacedByAnotherRegularFileWithoutEverLeavingTheRoot(@TempDir Path root)
            throws Exception {
        Path file = root.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "version one", StandardCharsets.UTF_8);
        Path replacement = root.resolve("src/replacement.tmp");
        Files.writeString(replacement, "version two", StandardCharsets.UTF_8);

        ConfinedFileOpener.beforeOpenForTests = () -> {
            try {
                Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };

        // A plain replacement inside the project is a legitimate edit, not an escape: the read must
        // succeed and must return bytes that came from inside the root.
        assertEquals("version two", read(root, Path.of("src", "Main.java")));
    }

    @Test
    void reportsADeletedFileAsAbsentRatherThanAsAConfinementBreach(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);

        ConfinedFileOpener.beforeOpenForTests = () -> {
            try {
                Files.delete(file);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };

        assertThrows(NoSuchFileException.class, () -> read(root, Path.of("src", "Main.java")));
    }

    @Test
    void refusesAPreexistingSymlinkThatEscapesTheRoot(@TempDir Path root) throws Exception {
        Path outside = Files.writeString(
                Files.createTempFile("minos-confined-escape-", ".txt"), "SECRET", StandardCharsets.UTF_8);
        try {
            Files.createDirectories(root.resolve("src"));
            assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");
            Files.createSymbolicLink(root.resolve("src/Escape.java"), outside);

            assertThrows(ConfinedFileOpener.ConfinementException.class,
                    () -> read(root, Path.of("src", "Escape.java")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void refusesAPathWhoseDirectoryComponentIsASymlink(@TempDir Path root) throws Exception {
        Path outsideDirectory = Files.createTempDirectory("minos-confined-outside-dir-");
        try {
            Files.writeString(outsideDirectory.resolve("Main.java"), "SECRET", StandardCharsets.UTF_8);
            assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");
            Files.createSymbolicLink(root.resolve("src"), outsideDirectory);

            assertThrows(ConfinedFileOpener.ConfinementException.class,
                    () -> read(root, Path.of("src", "Main.java")),
                    "a linked directory component is refused identically on both traversal strategies");
        } finally {
            Files.deleteIfExists(outsideDirectory.resolve("Main.java"));
            Files.deleteIfExists(outsideDirectory);
        }
    }

    /**
     * The boundary a caller hands in is not always canonical -- a Windows temp directory reached
     * through its short 8.3 alias is the case that caught this in CI. Comparing canonicalized
     * ancestors against a non-canonical boundary refused every legitimate read, so the boundary is
     * resolved inside the opener rather than trusted as given.
     */
    @Test
    void acceptsABoundaryThatIsNotAlreadyCanonical(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);
        Path detour = root.resolve("src").resolve("..");

        assertEquals("class Main {}", read(detour, Path.of("src", "Main.java")));
    }

    @Test
    void refusesTraversalAndAbsoluteRequests(@TempDir Path root) {
        assertThrows(ConfinedFileOpener.ConfinementException.class,
                () -> read(root, Path.of("..", "outside.java")));
        assertThrows(ConfinedFileOpener.ConfinementException.class,
                () -> read(root, root.resolve("absolute.java")));
    }

    @Test
    void refusesADirectory(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));

        assertThrows(IOException.class, () -> read(root, Path.of("src")));
    }

    /**
     * The replacement is driven from another thread and gated by latches, so the substitution is
     * both concurrent and deterministic: the opener is blocked at the seam until the replacement
     * has actually completed.
     */
    @Test
    void refusesAConcurrentSubstitutionSynchronizedWithLatchesRatherThanTiming(@TempDir Path root)
            throws Exception {
        Path outside = Files.writeString(
                Files.createTempFile("minos-confined-concurrent-", ".txt"), "SECRET", StandardCharsets.UTF_8);
        try {
            Path file = root.resolve("src/Main.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);
            assumeTrue(canCreateSymbolicLinks(root), "this platform/account cannot create symbolic links");

            CountDownLatch atSeam = new CountDownLatch(1);
            CountDownLatch replaced = new CountDownLatch(1);
            AtomicReference<Throwable> attackerFailure = new AtomicReference<>();
            Thread attacker = new Thread(() -> {
                try {
                    assertTrue(atSeam.await(30, TimeUnit.SECONDS), "opener never reached the seam");
                    Files.delete(file);
                    Files.createSymbolicLink(file, outside);
                } catch (Throwable failure) {
                    attackerFailure.set(failure);
                } finally {
                    replaced.countDown();
                }
            }, "confined-open-substitution");
            attacker.start();

            ConfinedFileOpener.beforeOpenForTests = () -> {
                atSeam.countDown();
                try {
                    assertTrue(replaced.await(30, TimeUnit.SECONDS), "substitution never completed");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            };

            assertThrows(IOException.class, () -> read(root, Path.of("src", "Main.java")));
            attacker.join();
            assertEquals(null, attackerFailure.get(), () -> String.valueOf(attackerFailure.get()));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private static String read(Path root, Path relative) throws IOException {
        try (SeekableByteChannel channel = ConfinedFileOpener.openConfinedRegularFile(root, relative)) {
            ByteBuffer buffer = ByteBuffer.allocate(4096);
            int read = channel.read(buffer);
            return read <= 0 ? "" : new String(buffer.array(), 0, read, StandardCharsets.UTF_8);
        }
    }

    /** Windows only allows symlink creation for privileged or developer-mode accounts. */
    private static boolean canCreateSymbolicLinks(Path root) {
        Path probe = root.resolve("minos-symlink-probe");
        try {
            Files.createSymbolicLink(probe, root);
            return true;
        } catch (IOException | UnsupportedOperationException unsupported) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // Probe cleanup failure must not mask the capability answer.
            }
        }
    }
}

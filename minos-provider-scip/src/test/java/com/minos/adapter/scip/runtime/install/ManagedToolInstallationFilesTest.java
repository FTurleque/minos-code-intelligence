package com.minos.adapter.scip.runtime.install;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The files and directories a tool installation writes (AUD-SEC-03): archive entries are created new, a resource copy
 * replaces its destination atomically, and a directory swap goes through one publication which fails closed.
 */
class ManagedToolInstallationFilesTest {

    private static final String RUNNER = "scip-java-windows-runner.ps1";

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < namesAndContents.length; index += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[index]));
                zip.write(namesAndContents[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Renames {@code from} to {@code to} (same length) in every header: an archive that names a path twice. */
    private static byte[] withDuplicateName(byte[] archive, String from, String to) {
        byte[] source = from.getBytes(StandardCharsets.UTF_8);
        byte[] target = to.getBytes(StandardCharsets.UTF_8);
        byte[] patched = archive.clone();
        for (int index = 0; index + source.length <= patched.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < source.length && match; offset++) {
                match = patched[index + offset] == source[offset];
            }
            if (match) System.arraycopy(target, 0, patched, index, target.length);
        }
        return patched;
    }

    private static Path symbolicLink(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.abort("symbolic links are unavailable: " + exception.getClass().getSimpleName());
            return link;
        }
    }

    // ------------------------------------------------------------------ archive extraction

    @Test
    void anArchiveIsExtractedWithItsDirectoriesAndItsFilesAreOwnerOnlyOnPosix(@TempDir Path temporary) throws Exception {
        Path archive = temporary.resolve("tool.zip");
        Files.write(archive, zip("top/a.txt", "alpha", "top/sub/b.txt", "beta"));
        Path root = temporary.resolve("root");

        ManagedScipProviderRuntimeManager.extractZipBounded(archive, root, 1024, 10);

        assertEquals("alpha", Files.readString(root.resolve("top/a.txt")));
        assertEquals("beta", Files.readString(root.resolve("top/sub/b.txt")));
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(root.resolve("top/a.txt")));
        }
    }

    @Test
    void aNameMetTwiceIsADefectOfTheArchiveAndTheFirstFileIsLeftIntact(@TempDir Path temporary) throws Exception {
        Path archive = temporary.resolve("tool.zip");
        Files.write(archive, withDuplicateName(zip("a.txt", "first", "b.txt", "second"), "b.txt", "a.txt"));
        Path root = temporary.resolve("root");

        IOException refusal = assertThrows(IOException.class,
                () -> ManagedScipProviderRuntimeManager.extractZipBounded(archive, root, 1024, 10));

        assertEquals("archive has a duplicate entry", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), "no absolute path in the message");
        assertEquals("first", Files.readString(root.resolve("a.txt")));
    }

    @Test
    void anEntryWhoseNameIsAlreadyALinkIsRefusedAndTheLinkTargetIsLeftIntact(@TempDir Path temporary) throws Exception {
        Path sentinel = temporary.resolve("sentinel.txt");
        Files.writeString(sentinel, "untouched");
        Path root = temporary.resolve("root");
        PrivateLocalStorage.ensurePrivateDirectory(root);
        symbolicLink(root.resolve("a.txt"), sentinel);
        Path archive = temporary.resolve("tool.zip");
        Files.write(archive, zip("a.txt", "overwritten"));

        assertThrows(IOException.class,
                () -> ManagedScipProviderRuntimeManager.extractZipBounded(archive, root, 1024, 10));

        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test
    void anExtractionRelaunchedAfterAnInterruptedOneSucceedsAndDropsWhatItLeftBehind(@TempDir Path temporary)
            throws Exception {
        Path archive = temporary.resolve("tool.zip");
        Files.write(archive, zip("top/a.txt", "new", "top/sub/b.txt", "beta"));
        Path root = temporary.resolve("root");
        PrivateLocalStorage.ensurePrivateDirectory(root.resolve("top"));
        Files.writeString(root.resolve("top/a.txt"), "interrupted");
        Files.writeString(root.resolve("top/leftover.txt"), "stale");

        ManagedScipProviderRuntimeManager.extractDistribution(archive, root, "top", 1024, 10);

        assertEquals("new", Files.readString(root.resolve("top/a.txt")));
        assertEquals("beta", Files.readString(root.resolve("top/sub/b.txt")));
        assertFalse(Files.exists(root.resolve("top/leftover.txt")));
    }

    // ------------------------------------------------------------------ packaged resource

    @Test
    void aPackagedResourceReplacesItsDestinationAndLeavesNoPartial(@TempDir Path temporary) throws Exception {
        Path destination = temporary.resolve("runtime").resolve(RUNNER);
        PrivateLocalStorage.ensurePrivateDirectory(destination.getParent());
        Files.writeString(destination, "old content");
        byte[] expected;
        try (InputStream resource = ManagedScipProviderRuntimeManager.class.getResourceAsStream(
                EmbeddedToolsCatalog.RESOURCE_ROOT + RUNNER)) {
            expected = resource.readAllBytes();
        }

        ManagedScipProviderRuntimeManager.copyPackagedResource(RUNNER, destination);
        ManagedScipProviderRuntimeManager.copyPackagedResource(RUNNER, destination);

        assertArrayEquals(expected, Files.readAllBytes(destination));
        try (Stream<Path> siblings = Files.list(destination.getParent())) {
            assertEquals(List.of(RUNNER), siblings.map(path -> path.getFileName().toString()).toList());
        }
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(destination.getParent()));
    }

    @Test
    void aMissingPackagedResourceIsRefused(@TempDir Path temporary) {
        assertThrows(IllegalStateException.class, () -> ManagedScipProviderRuntimeManager.copyPackagedResource(
                "no-such-resource.bin", temporary.resolve("runtime").resolve("no-such-resource.bin")));
    }

    // ------------------------------------------------------------------ directory swap

    private static Path tree(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("marker.txt"), content);
        return directory;
    }

    @Test
    void aSwapPutsTheNewTreeInPlaceAndDropsThePreviousOne(@TempDir Path temporary) throws Exception {
        Path destination = tree(temporary.resolve("tools/java"), "old");
        Path partial = tree(temporary.resolve("tools/java.partial"), "new");
        Path previous = temporary.resolve("tools/java.previous");

        ManagedScipProviderRuntimeManager.swapIn(partial, destination, previous,
                (source, target) -> com.minos.io.DurableAtomicFile.moveDirectory(source, target, "test swap"));

        assertEquals("new", Files.readString(destination.resolve("marker.txt")));
        assertFalse(Files.exists(previous));
        assertFalse(Files.exists(partial));
    }

    @Test
    void theRealSwapPublishesThroughTheAtomicDirectoryMove(@TempDir Path temporary) throws Exception {
        Path root = temporary.resolve("tools");
        Path destination = tree(root.resolve("java"), "old");
        Path partial = tree(root.resolve("java.partial"), "new");
        PrivateLocalStorage.ensurePrivateDirectory(root);

        ManagedScipProviderRuntimeManager.swapIn(partial, destination, root.resolve("java.previous"));

        assertEquals("new", Files.readString(destination.resolve("marker.txt")));
    }

    @Test
    void aSwapWhoseSecondMoveFailsRestoresThePreviousTree(@TempDir Path temporary) throws Exception {
        Path destination = tree(temporary.resolve("tools/java"), "old");
        Path partial = tree(temporary.resolve("tools/java.partial"), "new");
        Path previous = temporary.resolve("tools/java.previous");
        int[] calls = {0};

        IOException failure = assertThrows(IOException.class, () -> ManagedScipProviderRuntimeManager.swapIn(
                partial, destination, previous, (source, target) -> {
                    if (++calls[0] == 2) throw new IOException("the new tree cannot be moved");
                    com.minos.io.DurableAtomicFile.moveDirectory(source, target, "test swap");
                }));

        assertEquals("the new tree cannot be moved", failure.getMessage());
        assertEquals("old", Files.readString(destination.resolve("marker.txt")), "the previous tree is back");
        assertEquals("new", Files.readString(partial.resolve("marker.txt")), "the new tree is untouched");
        assertEquals(3, calls[0], "aside, refused, restored");
    }

    @Test
    void aMoveRefusedAsNotAtomicFailsTheInstallationWithoutCopyingAnything(@TempDir Path temporary) throws Exception {
        Path destination = tree(temporary.resolve("tools/java"), "old");
        Path partial = tree(temporary.resolve("tools/java.partial"), "new");
        Path previous = temporary.resolve("tools/java.previous");

        assertThrows(AtomicMoveNotSupportedException.class, () -> ManagedScipProviderRuntimeManager.swapIn(
                partial, destination, previous, (source, target) -> {
                    throw new AtomicMoveNotSupportedException(null, null, "not atomic");
                }));

        assertEquals("old", Files.readString(destination.resolve("marker.txt")), "the destination is unchanged");
        assertEquals("new", Files.readString(partial.resolve("marker.txt")), "nothing was copied or removed");
        assertFalse(Files.exists(previous));
    }
}

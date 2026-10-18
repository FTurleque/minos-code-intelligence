package com.minos.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three ways to write a file in a stream (AUD-SEC-03): hardened, created exclusively and hardened, and created
 * new in a tree whose confidentiality comes from its root.
 */
class PrivateLocalStorageStreamsTest {

    @AfterEach
    void resetCapabilityProbe() {
        PrivateLocalStorage.resetCapabilityProbeForTesting();
    }

    private static void write(OutputStream stream, String text) throws IOException {
        try (stream) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Path symbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException _) {
            assumeTrue(false, "this platform/account cannot create symbolic links");
            return link;
        }
    }

    // ------------------------------------------------------------------------ openPrivateOutputStream

    @Test
    void anAbsentFileIsCreatedPrivateAndWritten(@TempDir Path root) throws Exception {
        Path file = root.resolve("snapshots").resolve("current.bin");

        write(PrivateLocalStorage.openPrivateOutputStream(file), "snapshot");

        assertEquals("snapshot", Files.readString(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file.getParent()));
    }

    @Test
    void anExistingPermissiveFileIsHardenedThenTruncated(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("current.bin"), "a long previous content", StandardCharsets.UTF_8);

        write(PrivateLocalStorage.openPrivateOutputStream(file), "short");

        assertEquals("short", Files.readString(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    @Test
    void aLinkInPlaceOfTheFileIsRefusedAndItsTargetIsLeftIntact(@TempDir Path root) throws Exception {
        Path outside = Files.writeString(root.resolve("outside.txt"), "untouched", StandardCharsets.UTF_8);
        Path link = symbolicLinkOrSkip(root.resolve("current.bin"), outside);

        assertThrows(IOException.class, () -> PrivateLocalStorage.openPrivateOutputStream(link));

        assertEquals("untouched", Files.readString(outside));
    }

    @Test
    void aDirectoryInPlaceOfTheFileIsRefusedWithoutNamingThePath(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("current.bin"));

        IOException refusal = assertThrows(IOException.class, () -> PrivateLocalStorage.openPrivateOutputStream(directory));

        assertFalse(String.valueOf(refusal.getMessage()).contains(root.toString()),
                "the refusal must not carry the absolute path: " + refusal.getMessage());
    }

    @Test
    void writePrivateFileStillWritesTheWholeContent(@TempDir Path root) throws Exception {
        Path file = PrivateLocalStorage.writePrivateFile(root.resolve("pin"), "x=1".getBytes(StandardCharsets.UTF_8));

        assertEquals("x=1", Files.readString(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    // ---------------------------------------------------------------------- createPrivateOutputStream

    @Test
    void aFreeNameIsCreatedExclusivelyPrivateAndWritten(@TempDir Path root) throws Exception {
        Path file = root.resolve("runs").resolve("provider.stdout.log");

        write(PrivateLocalStorage.createPrivateOutputStream(file), "log line");

        assertEquals("log line", Files.readString(file));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    @Test
    void aNameAlreadyTakenIsRefusedWhateverTakesItAndNothingIsTouched(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("taken-by-file"), "intact", StandardCharsets.UTF_8);
        Path directory = Files.createDirectory(root.resolve("taken-by-directory"));
        Path outside = Files.writeString(root.resolve("outside.txt"), "untouched", StandardCharsets.UTF_8);
        Path link = symbolicLinkOrSkip(root.resolve("taken-by-link"), outside);

        for (Path taken : new Path[]{file, directory, link}) {
            FileAlreadyExistsException refusal = assertThrows(FileAlreadyExistsException.class,
                    () -> PrivateLocalStorage.createPrivateOutputStream(taken));
            assertFalse(String.valueOf(refusal.getMessage()).contains(root.toString()),
                    "the refusal must not carry the absolute path: " + refusal.getMessage());
        }

        assertEquals("intact", Files.readString(file));
        assertEquals("untouched", Files.readString(outside));
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void aParentWhichIsNotPrivateYetIsHardened(@TempDir Path root) throws Exception {
        Path parent = Files.createDirectory(root.resolve("shared-by-default"));

        write(PrivateLocalStorage.createPrivateOutputStream(parent.resolve("copy.bin")), "copy");

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(parent));
    }

    @Test
    void aFileWhichCannotBeHardenedIsDeletedAndTheFailureIsReported(@TempDir Path root) {
        Path file = root.resolve("victim");
        PrivateLocalStorage.CapabilityProbe real = PrivateLocalStorage.CapabilityProbe.real();
        PrivateLocalStorage.useForTesting(new PrivateLocalStorage.CapabilityProbe() {
            @Override
            public boolean supportsPosix(Path target) {
                return !target.getFileName().toString().equals("victim") && real.supportsPosix(target);
            }

            @Override
            public AclFileAttributeView aclView(Path target) {
                return target.getFileName().toString().equals("victim") ? null : real.aclView(target);
            }
        });

        assertThrows(IOException.class, () -> PrivateLocalStorage.createPrivateOutputStream(file));

        assertFalse(Files.exists(file), "a file which is not private must not linger");
    }

    // ----------------------------------------------------------------------------------- openNewTreeFile

    @Test
    void aNewFileIsCreatedAndWritten(@TempDir Path root) throws Exception {
        Path tree = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("tree"));
        Path file = tree.resolve("lib.jar");

        write(PrivateLocalStorage.openNewTreeFile(file), "jar bytes");

        assertEquals("jar bytes", Files.readString(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aNewFileIsRequestedOwnerOnlyAtCreationOnPosix(@TempDir Path root) throws Exception {
        Path tree = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("tree"));
        Path file = tree.resolve("lib.jar");

        write(PrivateLocalStorage.openNewTreeFile(file), "jar bytes");

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
    }

    @Test
    void anExistingFileIsRefusedAndItsContentIsIntact(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("lib.jar"), "intact", StandardCharsets.UTF_8);

        FileAlreadyExistsException refusal = assertThrows(FileAlreadyExistsException.class,
                () -> PrivateLocalStorage.openNewTreeFile(file));

        assertFalse(String.valueOf(refusal.getMessage()).contains(root.toString()));
        assertEquals("intact", Files.readString(file));
    }

    @Test
    void aLinkInPlaceOfTheTreeFileIsRefusedAndItsTargetIsLeftIntact(@TempDir Path root) throws Exception {
        Path outside = Files.writeString(root.resolve("outside.txt"), "untouched", StandardCharsets.UTF_8);
        Path link = symbolicLinkOrSkip(root.resolve("lib.jar"), outside);

        assertThrows(IOException.class, () -> PrivateLocalStorage.openNewTreeFile(link));

        assertEquals("untouched", Files.readString(outside));
    }

    @Test
    void anAbsentParentIsRefused(@TempDir Path root) {
        assertThrows(IOException.class, () -> PrivateLocalStorage.openNewTreeFile(root.resolve("absent").resolve("lib.jar")));
    }

    @Test
    void aParentWhichIsALinkIsRefusedAndNothingIsWrittenThroughIt(@TempDir Path root) throws Exception {
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = symbolicLinkOrSkip(root.resolve("link"), real);

        IOException refusal = assertThrows(IOException.class,
                () -> PrivateLocalStorage.openNewTreeFile(link.resolve("lib.jar")));

        assertFalse(String.valueOf(refusal.getMessage()).contains(root.toString()));
        try (var entries = Files.list(real)) {
            assertEquals(0L, entries.count(), "nothing was written through the link");
        }
    }

    // -------------------------------------------------------- the refusals around the creation and the opening

    @Test
    void aNameTakenIsRefusedWithoutAPathWhateverTheCreationThrows(@TempDir Path root) throws Exception {
        Path taken = Files.createDirectory(root.resolve("taken"));

        FileAlreadyExistsException exists = assertThrows(FileAlreadyExistsException.class, () ->
                PrivateLocalStorage.createNameOrRefuse(taken, () -> {
                    throw new FileAlreadyExistsException(taken.toString());
                }, "the name is taken"));
        // Windows answers "access denied" for a file created where a directory is.
        FileAlreadyExistsException denied = assertThrows(FileAlreadyExistsException.class, () ->
                PrivateLocalStorage.createNameOrRefuse(taken, () -> {
                    throw new java.nio.file.AccessDeniedException(taken.toString());
                }, "the name is taken"));

        assertEquals("the name is taken", exists.getMessage());
        assertEquals("the name is taken", denied.getMessage());
    }

    @Test
    void anAccessDeniedOnAFreeNameIsNotAnExistingName(@TempDir Path root) {
        Path free = root.resolve("free");

        assertThrows(java.nio.file.AccessDeniedException.class, () ->
                PrivateLocalStorage.createNameOrRefuse(free, () -> {
                    throw new java.nio.file.AccessDeniedException(free.toString());
                }, "the name is taken"));
    }

    @Test
    void aCreationWhichSucceedsIsLeftAlone(@TempDir Path root) throws Exception {
        Path free = root.resolve("free");

        PrivateLocalStorage.createNameOrRefuse(free, () -> Files.createFile(free), "the name is taken");

        assertTrue(Files.isRegularFile(free));
    }

    @Test
    void aFileWhichCannotBeOpenedIsDeletedAndTheFailureIsReported(@TempDir Path root) throws Exception {
        Path created = Files.writeString(root.resolve("created"), "x", StandardCharsets.UTF_8);

        IOException failure = assertThrows(IOException.class, () -> PrivateLocalStorage.openOrDelete(created, target -> {
            throw new IOException("cannot open");
        }));

        assertEquals("cannot open", failure.getMessage());
        assertFalse(Files.exists(created));
    }

    @Test
    void aDeletionWhichFailsTooIsKeptAsSuppressed(@TempDir Path root) throws Exception {
        Path notDeletable = Files.createDirectory(root.resolve("full"));
        Files.writeString(notDeletable.resolve("inside"), "x", StandardCharsets.UTF_8);

        IOException failure = assertThrows(IOException.class, () -> PrivateLocalStorage.openOrDelete(notDeletable, target -> {
            throw new IOException("cannot open");
        }));

        assertEquals("cannot open", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length, "the failed cleanup is not lost");
    }

    @Test
    void anOpenedStreamIsReturnedAsIs(@TempDir Path root) throws Exception {
        Path file = root.resolve("opened");

        try (OutputStream stream = PrivateLocalStorage.openOrDelete(file, Files::newOutputStream)) {
            stream.write(1);
        }

        assertTrue(Files.isRegularFile(file));
    }

    /** The point of the tree level: no DACL is rewritten per file, the file takes what its folder passes down. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aNewFileInheritsTheFolderAclAndItsDaclIsNotRewritten(@TempDir Path root) throws Exception {
        Path tree = Files.createDirectory(root.resolve("tree"));
        icacls(tree, "/grant", "*S-1-5-32-545:(OI)(CI)R");
        Path file = tree.resolve("lib.jar");

        write(PrivateLocalStorage.openNewTreeFile(file), "jar bytes");

        assertTrue(dacl(file).contains(";;;BU)"),
                "the inheritable entry added to the folder must reach the file, untouched: " + dacl(file));
    }

    private static void icacls(Path path, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString());
        command.add(path.toString());
        command.addAll(java.util.List.of(arguments));
        command.add("/q");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertEquals(0, process.waitFor(), "icacls failed: " + output);
    }

    private static String dacl(Path path) throws Exception {
        Path saved = Files.createTempFile(path.getParent().getParent(), "minos-dacl-test-", ".sddl");
        try {
            Process process = new ProcessBuilder(
                    Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString(),
                    path.toString(), "/save", saved.toString(), "/q").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor());
            return new String(Files.readAllBytes(saved), StandardCharsets.UTF_16LE);
        } finally {
            Files.deleteIfExists(saved);
        }
    }
}

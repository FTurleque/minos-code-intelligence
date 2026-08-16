package com.minos.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateLocalStorageTest {

    @AfterEach
    void resetCapabilityProbe() {
        PrivateLocalStorage.resetCapabilityProbeForTesting();
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    // ---------------------------------------------------------------- portable behaviour

    @Test
    void createsMissingDirectoriesAndReportsThemEnforced(@TempDir Path root) throws Exception {
        Path nested = root.resolve("remote-cache/repositories");
        Path created = PrivateLocalStorage.ensurePrivateDirectory(nested);

        assertTrue(Files.isDirectory(created, LinkOption.NOFOLLOW_LINKS));
        assertEquals(nested.toAbsolutePath().normalize(), created);
        assertPrivacyEnforced(created);
    }

    @Test
    void ensureIsIdempotent(@TempDir Path root) throws Exception {
        Path directory = root.resolve("scratch");
        PrivateLocalStorage.ensurePrivateDirectory(directory);
        PrivateLocalStorage.ensurePrivateDirectory(directory);
        PrivateLocalStorage.verifyPrivateDirectory(directory);
    }

    @Test
    void createsPrivateFilesAndRefusesToAdoptAnExistingOne(@TempDir Path root) throws Exception {
        Path file = root.resolve("store/snapshot.bin");
        Path created = PrivateLocalStorage.createPrivateFile(file);

        assertTrue(Files.isRegularFile(created, LinkOption.NOFOLLOW_LINKS));
        assertPrivacyEnforced(created);
        assertThrows(FileAlreadyExistsException.class, () -> PrivateLocalStorage.createPrivateFile(file));
    }

    @Test
    void temporaryFilesStayInsideTheGivenDirectory(@TempDir Path root) throws Exception {
        Path directory = root.resolve("postgresql-snapshot-scratch");
        Path temporary = PrivateLocalStorage.createPrivateTempFile(directory, ".snapshot-", ".tmp");

        assertEquals(directory.toAbsolutePath().normalize(), temporary.getParent());
        assertTrue(Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS));
        assertPrivacyEnforced(temporary);
    }

    @Test
    void rejectsADirectorySymlinkInsteadOfFollowingIt(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // symlink creation is not permitted here; nothing to assert
        }
        assertThrows(IOException.class, () -> PrivateLocalStorage.ensurePrivateDirectory(link));
        assertThrows(IOException.class, () -> PrivateLocalStorage.verifyPrivateDirectory(link));
    }

    @Test
    void rejectsAFileSymlinkInsteadOfFollowingIt(@TempDir Path root) throws Exception {
        Path real = Files.writeString(root.resolve("real.bin"), "payload");
        Path link = root.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }
        assertThrows(IOException.class, () -> PrivateLocalStorage.verifyPrivateFile(link));
        assertThrows(IOException.class, () -> PrivateLocalStorage.hardenExistingFile(link));
    }

    @Test
    void rejectsAPathWhoseTypeIsNotTheExpectedOne(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("plain.bin"), "payload");
        assertThrows(IOException.class, () -> PrivateLocalStorage.verifyPrivateDirectory(file));

        Path directory = Files.createDirectories(root.resolve("plain-directory"));
        assertThrows(IOException.class, () -> PrivateLocalStorage.verifyPrivateFile(directory));
    }

    @Test
    void privacyOfReportsAbsentForAMissingPath(@TempDir Path root) throws Exception {
        assertEquals(PrivateLocalStorage.Privacy.ABSENT,
                PrivateLocalStorage.privacyOf(root.resolve("nothing-here")));
    }

    // ---------------------------------------------------------------- POSIX

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixDirectoriesAreCreatedAt0700WithoutAWorldReadableWindow(@TempDir Path root) throws Exception {
        Path directory = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("a/b/c"));

        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)));
        // Every level MINOS created must be private, not just the leaf.
        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(root.resolve("a"), LinkOption.NOFOLLOW_LINKS)));
        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(root.resolve("a/b"), LinkOption.NOFOLLOW_LINKS)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixFilesAreCreatedAt0600(@TempDir Path root) throws Exception {
        Path file = PrivateLocalStorage.createPrivateFile(root.resolve("store/snapshot.bin"));
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)));

        Path temporary = PrivateLocalStorage.createPrivateTempFile(root.resolve("store"), ".t-", ".tmp");
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(temporary, LinkOption.NOFOLLOW_LINKS)));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixHardensAPreExistingWorldReadableInstallation(@TempDir Path root) throws Exception {
        // Reproduces a MINOS_HOME created before this policy: 0755 directories, 0644 files.
        Path directory = Files.createDirectories(root.resolve("remote-cache/repositories"));
        Files.setPosixFilePermissions(root.resolve("remote-cache"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path file = Files.writeString(directory.resolve("entry.properties"), "key=value");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        assertEquals(PrivateLocalStorage.Privacy.EXPOSED, PrivateLocalStorage.privacyOf(directory));
        assertEquals(PrivateLocalStorage.Privacy.EXPOSED, PrivateLocalStorage.privacyOf(file));

        PrivateLocalStorage.ensurePrivateDirectory(directory);
        PrivateLocalStorage.hardenExistingFile(file);

        assertEquals("rwx------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)));
        assertEquals("rw-------", PosixFilePermissions.toString(
                Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(directory));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixLeavesNoGroupOrOthersBitOnAnyPrivateLocation(@TempDir Path root) throws Exception {
        Path directory = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("semantic-vectors"));
        Path file = PrivateLocalStorage.createPrivateFile(directory.resolve("index-v2.bin"));

        for (Path target : new Path[]{directory, file}) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            for (PosixFilePermission permission : actual) {
                assertTrue(permission.name().startsWith("OWNER_"),
                        target + " must not grant " + permission);
            }
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixVerificationFailsClosedWhenPrivacyCannotBeAsserted(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("exposed"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

        IOException failure = assertThrows(IOException.class,
                () -> PrivateLocalStorage.verifyPrivateDirectory(directory));
        assertTrue(failure.getMessage().contains("readable beyond its owner"), failure.getMessage());
    }

    // ---------------------------------------------------------------- Windows / ACL

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsDirectoriesEndUpOwnerOnly(@TempDir Path root) throws Exception {
        Path directory = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("remote-cache/repositories"));
        assertOwnerOnlyAcl(directory);
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(directory));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsFilesEndUpOwnerOnly(@TempDir Path root) throws Exception {
        Path file = PrivateLocalStorage.createPrivateFile(root.resolve("store/snapshot.bin"));
        assertOwnerOnlyAcl(file);
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(file));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsHardensAPreExistingDirectoryWithABroaderAcl(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("legacy-cache"));
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        int before = acl.getAcl().size();

        PrivateLocalStorage.ensurePrivateDirectory(directory);

        assertOwnerOnlyAcl(directory);
        assertTrue(acl.getAcl().size() <= before, "hardening must not widen the ACL");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsTemporaryFilesAreOwnerOnly(@TempDir Path root) throws Exception {
        Path temporary = PrivateLocalStorage.createPrivateTempFile(
                root.resolve("postgresql-snapshot-scratch"), ".snapshot-", ".tmp");
        assertOwnerOnlyAcl(temporary);
    }

    private static void assertOwnerOnlyAcl(Path target) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(
                target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) return;
        for (AclEntry entry : acl.getAcl()) {
            assertEquals(acl.getOwner(), entry.principal(),
                    target + " grants access to " + entry.principal().getName());
        }
    }

    /**
     * Every enforcement entry point now fails closed, so reaching this assertion at all already
     * proves the location could not have been left {@code UNSUPPORTED} or {@code EXPOSED}: privacy
     * must be {@code ENFORCED} unconditionally, on every platform.
     */
    private static void assertPrivacyEnforced(Path target) throws IOException {
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(target));
    }

    // ---------------------------------------------------------------- UNSUPPORTED (fault injection)
    //
    // A filesystem that exposes neither POSIX permissions nor an ACL view cannot have its privacy
    // enforced or verified. Every enforcement entry point must fail closed rather than silently
    // treating the location as private -- this is simulated deterministically via a fake
    // CapabilityProbe so it does not depend on finding such a filesystem on the CI runner.

    @Test
    void unsupportedFilesystemFailsClosedCreatingADirectoryAndLeavesNoDirectoryBehind(@TempDir Path root) {
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());
        Path directory = root.resolve("scratch");

        IOException failure = assertThrows(IOException.class,
                () -> PrivateLocalStorage.ensurePrivateDirectory(directory));
        assertTrue(failure.getMessage().contains("neither POSIX"), failure.getMessage());
        assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS),
                "a directory this call created must not remain when it could not be made private");
    }

    @Test
    void unsupportedFilesystemFailureDuringANestedCreateOnlyRemovesWhatItCreated(@TempDir Path root) throws Exception {
        Path alreadyExisting = Files.createDirectories(root.resolve("already-existing"));
        Path nested = alreadyExisting.resolve("newly-created");
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());

        assertThrows(IOException.class, () -> PrivateLocalStorage.ensurePrivateDirectory(nested));

        assertFalse(Files.exists(nested, LinkOption.NOFOLLOW_LINKS),
                "the directory this call created must not remain");
        assertTrue(Files.isDirectory(alreadyExisting),
                "a directory that pre-existed this call must never be deleted, even on failure");
    }

    @Test
    void unsupportedFilesystemFailsClosedCreatingAFileAndLeavesNoFileBehind(@TempDir Path root) {
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());
        Path file = root.resolve("store/snapshot.bin");

        assertThrows(IOException.class, () -> PrivateLocalStorage.createPrivateFile(file));
        assertFalse(Files.exists(file, LinkOption.NOFOLLOW_LINKS),
                "a file this call created must not remain when it could not be made private");
    }

    @Test
    void unsupportedFilesystemFailsClosedCreatingATempFileAndLeavesNoFileBehind(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("scratch"));
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());

        assertThrows(IOException.class,
                () -> PrivateLocalStorage.createPrivateTempFile(directory, ".t-", ".tmp"));
        try (var entries = Files.list(directory)) {
            assertTrue(entries.findAny().isEmpty(),
                    "a temp file that could not be made private must not remain in the directory");
        }
    }

    @Test
    void unsupportedFilesystemFailsClosedVerifyingAPreExistingDirectory(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("legacy"));
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());

        assertThrows(IOException.class, () -> PrivateLocalStorage.verifyPrivateDirectory(directory));
    }

    @Test
    void unsupportedFilesystemFailsClosedVerifyingAPreExistingFile(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("legacy.bin"), "payload");
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());

        assertThrows(IOException.class, () -> PrivateLocalStorage.verifyPrivateFile(file));
    }

    @Test
    void unsupportedFilesystemFailsClosedHardeningAPreExistingFileButPreservesIt(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("legacy.bin"), "payload");
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());

        assertThrows(IOException.class, () -> PrivateLocalStorage.hardenExistingFile(file));
        // Unlike a file this call created itself, a pre-existing legacy file is the caller's data:
        // failing to harden it must not destroy it.
        assertTrue(Files.exists(file, LinkOption.NOFOLLOW_LINKS));
        assertEquals("payload", Files.readString(file));
    }

    @Test
    void privacyOfReportsUnsupportedAsADiagnosticWithoutThrowing(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("legacy"));
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());

        assertEquals(PrivateLocalStorage.Privacy.UNSUPPORTED, PrivateLocalStorage.privacyOf(directory));
    }

    // ---------------------------------------------------------------- ACL branch (fault injection)
    //
    // These exercise PrivateLocalStorage's ACL code path deterministically on any OS, complementing
    // the @EnabledOnOs(WINDOWS) tests below that confirm the real platform behaves the same way.

    @Test
    void aclOwnerOnlyGrantIsEnforced(@TempDir Path root) throws Exception {
        UserPrincipal owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        PrivateLocalStorage.useForTesting(
                FakeCapabilityProbe.aclOnly(target -> FakeAclFileAttributeView.ownerOnly(owner)));

        Path directory = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("remote-cache"));

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(directory));
    }

    @Test
    void aclForeignGrantIsDetectedAndRejectedByVerify(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("legacy-cache"));
        UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        PrivateLocalStorage.useForTesting(
                FakeCapabilityProbe.aclOnly(target -> FakeAclFileAttributeView.withForeignGrant(owner)));

        IOException failure = assertThrows(IOException.class,
                () -> PrivateLocalStorage.verifyPrivateDirectory(directory));
        assertTrue(failure.getMessage().contains("another principal"), failure.getMessage());
    }

    @Test
    void aclHardeningNarrowsAnInheritedGrantToOwnerOnly(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("legacy-cache"));
        UserPrincipal owner = Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS);
        FakeCapabilityProbe probe = FakeCapabilityProbe.aclOnly(
                target -> FakeAclFileAttributeView.withForeignGrant(owner));
        PrivateLocalStorage.useForTesting(probe);

        PrivateLocalStorage.ensurePrivateDirectory(directory);

        AclFileAttributeView view = probe.viewOf(directory.toAbsolutePath().normalize());
        assertEquals(1, view.getAcl().size(), "hardening must narrow the ACL down to the owner only");
        assertEquals(owner, view.getAcl().get(0).principal());
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(directory));
    }

    @Test
    void aclHardeningAppliesToEveryIntermediateDirectoryNotJustTheLeaf(@TempDir Path root) throws Exception {
        UserPrincipal owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        FakeCapabilityProbe probe = FakeCapabilityProbe.aclOnly(
                target -> FakeAclFileAttributeView.withForeignGrant(owner));
        PrivateLocalStorage.useForTesting(probe);

        Path leaf = PrivateLocalStorage.ensurePrivateDirectory(root.resolve("a/b/c"));

        for (Path level : new Path[]{root.resolve("a"), root.resolve("a/b"), leaf}) {
            AclFileAttributeView view = probe.viewOf(level.toAbsolutePath().normalize());
            assertEquals(1, view.getAcl().size(), level + " must be hardened to owner-only, not just the leaf");
        }
    }

    @Test
    void aclHardeningFailureLeavesNoFileBehind(@TempDir Path root) throws Exception {
        Path directory = root.resolve("store");
        Path file = directory.resolve("snapshot.bin").toAbsolutePath().normalize();
        UserPrincipal owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        // The directory hardens correctly; only the file's ACL implementation is broken (setAcl is a
        // permanent no-op), so verify() -- not harden() -- is what must catch the surviving grant.
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.aclOnly(target ->
                target.equals(file)
                        ? FakeAclFileAttributeView.stuckWithForeignGrant(owner)
                        : FakeAclFileAttributeView.ownerOnly(owner)));

        assertThrows(IOException.class, () -> PrivateLocalStorage.createPrivateFile(file));
        assertFalse(Files.exists(file, LinkOption.NOFOLLOW_LINKS),
                "a file whose ACL could not actually be narrowed must not remain");
    }
}

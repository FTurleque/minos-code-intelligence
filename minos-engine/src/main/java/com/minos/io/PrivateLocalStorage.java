package com.minos.io;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Single owner-only policy for every local location that can hold user code or its derivatives.
 *
 * <p>MINOS_HOME accumulates material that is as confidential as the repositories it indexes: full
 * clones of private repositories, symbol snapshots, SCIP artifacts, semantic vectors, runtime
 * observations and the scratch files used to build them. Java's defaults do not protect any of it —
 * {@link Files#createDirectories} yields {@code 0777 & ~umask} (typically {@code 0755}) and a plain
 * write yields {@code 0644}, both world-readable. This type is the one place that decides what
 * "private" means so the policy cannot drift between storage backends.</p>
 *
 * <h2>Policy</h2>
 * <ul>
 *   <li>POSIX: directories {@code 0700}, files {@code 0600} — no GROUP and no OTHERS bit.</li>
 *   <li>ACL platforms (Windows): a single explicit ALLOW entry for the owner.</li>
 * </ul>
 *
 * <h2>Guarantees</h2>
 * <p>Permissions are requested <em>at creation</em> via {@link FileAttribute}, so there is no window
 * between a world-readable create and a later {@code chmod}. Locations that already exist — an
 * installation created before this policy, or by an older MINOS — are hardened in place and then
 * re-read: enforcement is verified, never assumed. A path that is a symbolic link, or whose type is
 * not the expected one, is rejected rather than followed.</p>
 *
 * <p>Where the filesystem exposes neither a POSIX nor an ACL view there is nothing to enforce and
 * nothing is silently claimed: {@link #privacyOf(Path)} reports {@link Privacy#UNSUPPORTED} so the
 * condition surfaces in diagnostics instead of being mistaken for a protected store.</p>
 */
public final class PrivateLocalStorage {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private static final Set<PosixFilePermission> FORBIDDEN_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private PrivateLocalStorage() {
    }

    /** Enforcement actually achieved for a location, so callers and diagnostics never over-claim. */
    public enum Privacy {
        /** Owner-only access is enforced and was verified by re-reading the location. */
        ENFORCED,
        /** The location exists but grants access beyond its owner. */
        EXPOSED,
        /** The filesystem exposes no permission model, so privacy cannot be enforced or denied. */
        UNSUPPORTED,
        /** The location does not exist. */
        ABSENT
    }

    /**
     * Creates {@code directory} and any missing parent as owner-only, hardening it first if it
     * already exists, then verifies the result.
     *
     * @return the normalised absolute directory
     * @throws IOException if the path is a symlink, is not a directory, or cannot be made private
     */
    public static Path ensurePrivateDirectory(Path directory) throws IOException {
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        createPrivateDirectories(target);
        hardenDirectory(target);
        verifyPrivateDirectory(target);
        return target;
    }

    /**
     * Creates {@code file} with owner-only permissions. Fails if it already exists, so a caller
     * cannot be handed a file another user pre-created.
     */
    public static Path createPrivateFile(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) ensurePrivateDirectory(parent);
        Files.createFile(target, privateFileAttributes());
        hardenFile(target);
        verifyPrivateFile(target);
        return target;
    }

    /**
     * Creates a uniquely named owner-only temporary file inside {@code directory}, which is itself
     * ensured private first. The file never lands in the shared system temp directory.
     */
    public static Path createPrivateTempFile(Path directory, String prefix, String suffix) throws IOException {
        Path parent = ensurePrivateDirectory(directory);
        Path temporary = Files.createTempFile(parent, prefix, suffix, privateFileAttributes());
        hardenFile(temporary);
        verifyPrivateFile(temporary);
        return temporary;
    }

    /** Hardens an already-written file in place, e.g. one produced before this policy existed. */
    public static Path hardenExistingFile(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        hardenFile(target);
        verifyPrivateFile(target);
        return target;
    }

    public static void verifyPrivateDirectory(Path directory) throws IOException {
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        requireType(target, true);
        verifyPrivacy(target);
    }

    public static void verifyPrivateFile(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        requireType(target, false);
        verifyPrivacy(target);
    }

    /**
     * Reports the enforcement actually in place, without changing anything. Intended for
     * diagnostics; it never throws for an exposed location, it describes it.
     */
    public static Privacy privacyOf(Path path) throws IOException {
        Path target = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Privacy.ABSENT;
        if (Files.isSymbolicLink(target)) return Privacy.EXPOSED;
        if (supportsPosix()) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            return actual.stream().anyMatch(FORBIDDEN_PERMISSIONS::contains) ? Privacy.EXPOSED : Privacy.ENFORCED;
        }
        AclFileAttributeView acl = aclView(target);
        if (acl == null) return Privacy.UNSUPPORTED;
        return foreignAclEntry(acl) == null ? Privacy.ENFORCED : Privacy.EXPOSED;
    }

    private static void createPrivateDirectories(Path target) throws IOException {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) return;
        Path parent = target.getParent();
        if (parent != null) createPrivateDirectories(parent);
        try {
            Files.createDirectory(target, privateDirectoryAttributes());
        } catch (FileAlreadyExistsException concurrentlyCreated) {
            // Another writer won the race; hardening and verification below still apply to it.
        }
    }

    private static void hardenDirectory(Path target) throws IOException {
        harden(target, DIRECTORY_PERMISSIONS);
    }

    private static void hardenFile(Path target) throws IOException {
        harden(target, FILE_PERMISSIONS);
    }

    private static void harden(Path target, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("private storage path must not be a symbolic link: " + target);
        }
        if (supportsPosix()) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            if (!actual.equals(permissions)) Files.setPosixFilePermissions(target, permissions);
            return;
        }
        AclFileAttributeView acl = aclView(target);
        if (acl == null) return;
        UserPrincipal owner = Files.getOwner(target, LinkOption.NOFOLLOW_LINKS);
        acl.setAcl(List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build()));
    }

    private static void verifyPrivacy(Path target) throws IOException {
        if (supportsPosix()) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> leaked = EnumSet.noneOf(PosixFilePermission.class);
            for (PosixFilePermission permission : actual) {
                if (FORBIDDEN_PERMISSIONS.contains(permission)) leaked.add(permission);
            }
            if (!leaked.isEmpty()) {
                throw new IOException("private storage is readable beyond its owner: " + target + " grants " + leaked);
            }
            return;
        }
        AclFileAttributeView acl = aclView(target);
        if (acl == null) return;
        AclEntry foreign = foreignAclEntry(acl);
        if (foreign != null) {
            throw new IOException("private storage grants access to another principal: "
                    + target + " grants " + foreign.principal().getName());
        }
    }

    /** The first entry granting access to a principal other than the owner, or {@code null}. */
    private static AclEntry foreignAclEntry(AclFileAttributeView acl) throws IOException {
        UserPrincipal owner = acl.getOwner();
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() != AclEntryType.ALLOW) continue;
            if (!entry.principal().equals(owner)) return entry;
        }
        return null;
    }

    private static void requireType(Path target, boolean directory) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("private storage path must not be a symbolic link: " + target);
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unreadable) {
            throw new IOException("private storage path is not readable: " + target, unreadable);
        }
        if (directory && !attributes.isDirectory()) {
            throw new IOException("private storage path is not a directory: " + target);
        }
        if (!directory && !attributes.isRegularFile()) {
            throw new IOException("private storage path is not a regular file: " + target);
        }
    }

    private static FileAttribute<?>[] privateDirectoryAttributes() {
        return posixAttributes(DIRECTORY_PERMISSIONS);
    }

    private static FileAttribute<?>[] privateFileAttributes() {
        return posixAttributes(FILE_PERMISSIONS);
    }

    private static FileAttribute<?>[] posixAttributes(Set<PosixFilePermission> permissions) {
        if (!supportsPosix()) return new FileAttribute<?>[0];
        return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(permissions)};
    }

    private static AclFileAttributeView aclView(Path target) {
        return Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean supportsPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}

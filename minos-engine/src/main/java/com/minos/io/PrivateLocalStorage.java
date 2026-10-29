package com.minos.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li>ACL platforms (Windows): a single explicit ALLOW entry for the owner, and a DACL that does
 *       not inherit from its parent, so that a grant added later to a parent cannot flow back in. On a
 *       directory that entry is inheritable (file and directory inherit), so whatever a process of the
 *       owner creates in it later -- a sandboxed provider writing its artifact, a tool unpacking an
 *       archive -- is owner-only too instead of taking the default ACL of its creator. Still no other
 *       principal.</li>
 * </ul>
 *
 * <h2>What hardening never removes</h2>
 * <p>An ACL platform keeps every DENY entry the location already carries, explicit or inherited
 * (an inherited one becomes explicit when the DACL stops inheriting), ahead of the owner's ALLOW
 * entry: a restriction an administrator placed is theirs to lift, never MINOS's. To lift one that
 * became explicit this way, an administrator removes it by name:
 * {@code icacls <MINOS_HOME> /remove:d <principal> /T}. When such a restriction stops MINOS from writing
 * where it must, the operation fails and says so, without naming the path. ALLOW entries granted to any
 * other principal are removed: a grant is a right, not a restriction, and this storage is owner-only by
 * definition.</p>
 *
 * <h2>What hardening writes, and how often</h2>
 * <p>The first time a JVM touches an object, its DACL is rewritten (the owner entry and the DENY entries
 * kept) and then protected against inheritance by the {@link PlatformAccessControl} port (on Windows, the local
 * runtime runs {@code icacls /inheritance:d}: one short process per object and per JVM). This class launches no
 * process itself. Java cannot read the protected bit, and its {@code getAcl()} does not show conditional
 * (XA) entries, so neither can be assumed already right. After that, in the same JVM, a call rewrites the
 * entries only if they are no longer the expected ones. Both writes need WRITE_DAC, which an owner always
 * holds. The memory of what was protected is forgotten when this class creates an object again at the same
 * path, and emptied when it reaches 8192 entries; an object deleted and recreated by anything else is
 * touched afresh only after one of those two events.</p>
 *
 * <p>Conditional entries. Java sees no conditional entry and cannot write one back. A conditional ALLOW (XA)
 * is a right granted to someone else: it is dropped. A conditional DENY (XD) is a restriction an
 * administrator put there: it is never dropped. Before its first rewrite of an object's DACL, this class
 * reads the whole DACL through the same port ({@code icacls /save} on Windows, one more short process per
 * object and per JVM) and, if an XD
 * entry is present or the DACL cannot be read, <b>refuses</b> without writing: the error says that a
 * conditional restriction on the data location prevents MINOS from making it private, without naming the
 * path, and the administrator removes the restriction or moves {@code MINOS_HOME}. Residual limit: a
 * conditional DENY added between that read and the rewrite is still dropped.</p>
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
        /**
         * The filesystem exposes no permission model, so privacy cannot be enforced or denied. This
         * is a read-only diagnostic value from {@link #privacyOf(Path)}: every enforcement entry
         * point fails closed with an {@link IOException} instead of ever returning this as success.
         */
        UNSUPPORTED,
        /** The location does not exist. */
        ABSENT
    }

    /**
     * Creates {@code directory} and any missing parent as owner-only, hardening it first if it
     * already exists, then verifies the result.
     *
     * @return the normalised absolute directory
     * @throws IOException if the path is a symlink, is not a directory, or cannot be made private —
     *         including when the filesystem cannot enforce or verify ownership at all
     */
    public static Path ensurePrivateDirectory(Path directory) throws IOException {
        return ensurePrivateDirectory(directory, null, null, true);
    }

    /**
     * MINOS-AUD-H23: on an ACL filesystem, hardening rewrites the DACL for the owner of the directory. A directory that
     * already existed is therefore hardened only if it is dedicated to MINOS (not the user profile, one of its parents
     * or a volume root) and belongs to {@code currentUser} (the current user when null); otherwise nothing is changed.
     * POSIX needs no such guard: a non-owner cannot change the mode of a directory, and the read-only data root of the
     * container must not be written to.
     */
    static Path ensurePrivateDirectory(Path directory, UserPrincipal currentUser, Path userHome) throws IOException {
        return ensurePrivateDirectory(directory, currentUser, userHome, false);
    }

    private static Path ensurePrivateDirectory(Path directory, UserPrincipal currentUser, Path userHome,
                                               boolean readUserHome) throws IOException {
        Path target = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !supportsPosix(target)) {
            refuseSharedLocation(target, readUserHome ? userHome(System.getProperty("user.home")) : userHome);
            UserPrincipal expected = currentUser != null ? currentUser : ProcessOwner.current(target);
            if (!Files.getOwner(target, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
                throw new IOException("MINOS refuses to make private an existing directory that belongs to another "
                        + "account; its permissions are unchanged. Take ownership of it, or choose another MINOS_HOME");
            }
        }
        createPrivateDirectories(target);
        hardenDirectory(target);
        verifyPrivateDirectory(target);
        return target;
    }

    static void refuseSharedLocation(Path target, Path userHome) throws IOException {
        Path home = userHome == null ? null : userHome.toAbsolutePath().normalize();
        if (target.getParent() == null || (home != null && home.startsWith(target))) {
            throw new IOException("MINOS refuses to make private a directory that is not dedicated to MINOS (the user "
                    + "profile, one of its parents or a volume root); its permissions are unchanged. Choose another "
                    + "MINOS_HOME");
        }
    }

    /** {@code user.home} when it names an existing absolute directory; an unknown home ("?") guards nothing. */
    static Path userHome(String property) {
        String value = property == null ? "" : property.trim();
        if (value.isEmpty()) return null;
        try {
            Path home = Path.of(value);
            return home.isAbsolute() && Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS) ? home : null;
        } catch (java.nio.file.InvalidPathException _) {
            return null;
        }
    }

    /**
     * Creates {@code file} with owner-only permissions. Fails if it already exists, so a caller
     * cannot be handed a file another user pre-created.
     */
    public static Path createPrivateFile(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) ensurePrivateDirectory(parent);
        createFileExclusively(target, parent);
        forgetProtected(target);
        hardenOrDeleteAndThrow(target);
        return target;
    }

    /** The one creation of a new file by this class: owner-only at creation where the platform allows it. */
    private static void createFileExclusively(Path target, Path parent) throws IOException {
        try {
            Files.createFile(target, privateFileAttributes(target));
        } catch (AccessDeniedException denied) {
            throw explainDenied(denied, parent);
        }
    }

    /** The one opening of a stream by this class: never through a link, whatever the name became meanwhile. */
    private static OutputStream openStreamWithoutFollowingLinks(Path target) throws IOException {
        try {
            return Files.newOutputStream(target,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        } catch (AccessDeniedException denied) {
            throw explainDenied(denied, target, target.getParent());
        }
    }

    /**
     * Creates a uniquely named owner-only temporary file inside {@code directory}, which is itself
     * ensured private first. The file never lands in the shared system temp directory.
     */
    public static Path createPrivateTempFile(Path directory, String prefix, String suffix) throws IOException {
        Path parent = ensurePrivateDirectory(directory);
        Path temporary;
        try {
            temporary = Files.createTempFile(parent, prefix, suffix, privateFileAttributes(parent));
        } catch (AccessDeniedException denied) {
            throw explainDenied(denied, parent);
        }
        forgetProtected(temporary);
        hardenOrDeleteAndThrow(temporary);
        return temporary;
    }

    /**
     * Creates a uniquely named owner-only temporary directory inside {@code parent}, which is itself
     * ensured private first. On ACL platforms the new directory is hardened and verified before it
     * is returned, exactly as {@link #ensurePrivateDirectory} does for a named one.
     */
    public static Path createPrivateTempDirectory(Path parent, String prefix) throws IOException {
        Path root = ensurePrivateDirectory(parent);
        Path temporary;
        try {
            temporary = Files.createTempDirectory(root, prefix, privateDirectoryAttributes(root));
        } catch (AccessDeniedException denied) {
            throw explainDenied(denied, root);
        }
        forgetProtected(temporary);
        try {
            hardenDirectory(temporary);
            verifyPrivateDirectory(temporary);
        } catch (IOException failure) {
            deleteEmptyDirectoryBestEffort(temporary);
            throw failure;
        }
        return temporary;
    }

    /**
     * Writes {@code content} as the whole content of the owner-only file {@code file}: created
     * private when absent, hardened in place when it already exists, never through a link (a
     * symbolic link or special object is refused, and the write itself is opened with
     * {@link LinkOption#NOFOLLOW_LINKS}).
     *
     * @return the normalised absolute file
     */
    public static Path writePrivateFile(Path file, byte[] content) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Objects.requireNonNull(content, "content");
        try (OutputStream output = openPrivateOutputStream(target)) {
            output.write(content);
        }
        return target;
    }

    /**
     * Opens {@code file} for writing, as the whole content of an owner-only file: created private when absent,
     * hardened in place when it already exists, then truncated; never through a link (a symbolic link, a directory or
     * a special object in its place is refused, and the stream itself is opened with {@link
     * LinkOption#NOFOLLOW_LINKS}). This is the stream form of {@link #writePrivateFile}, for content too large to hold
     * in memory (a snapshot, a vector index). The caller closes the stream; a failure while writing leaves the file as
     * far as it got.
     */
    public static OutputStream openPrivateOutputStream(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            hardenExistingFile(target);
        } else {
            try {
                createPrivateFile(target);
            } catch (FileAlreadyExistsException concurrentCreate) {
                try {
                    hardenExistingFile(target);
                } catch (IOException unsafeWinner) {
                    unsafeWinner.addSuppressed(concurrentCreate);
                    throw unsafeWinner;
                }
            }
        }
        return openStreamWithoutFollowingLinks(target);
    }

    /**
     * Creates {@code file} exclusively and opens it for writing: the name must be free, whatever could take it (a file,
     * a directory, a link), and the file is owner-only and verified before the stream is handed out, as {@link
     * #createPrivateFile} guarantees. For the files MINOS writes and reads back in a folder the provider can also write
     * into (process logs, a kept copy of an artifact): the file can neither pre-exist nor be adopted, and a name that
     * reappeared between a deletion and this creation fails the call. A file whose opening fails is deleted. The
     * refusal for a taken name carries no path.
     *
     * @throws FileAlreadyExistsException if the name is taken; the message names no path
     */
    public static OutputStream createPrivateOutputStream(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        createNameOrRefuse(target, () -> createPrivateFile(target), "private storage file already exists");
        return openOrDelete(target, PrivateLocalStorage::openStreamWithoutFollowingLinks);
    }

    /** Something that creates a file; the refusal for a name that is taken is decided around it. */
    @FunctionalInterface
    interface Creation {
        void run() throws IOException;
    }

    /** Opens the stream of a file this call just created, and deletes that file if it cannot be opened. */
    @FunctionalInterface
    interface Opener {
        OutputStream open(Path target) throws IOException;
    }

    /**
     * Runs {@code creation}; a name that is taken is refused with {@code message}, which carries no path. Windows
     * answers "access denied", not "exists", for a file created where a directory is: when the name is there, that
     * is the same refusal.
     */
    static void createNameOrRefuse(Path target, Creation creation, String message) throws IOException {
        try {
            creation.run();
        } catch (FileAlreadyExistsException _) {
            throw new FileAlreadyExistsException(null, null, message);
        } catch (AccessDeniedException denied) {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(null, null, message);
            }
            throw denied;
        }
    }

    static OutputStream openOrDelete(Path target, Opener opener) throws IOException {
        try {
            return opener.open(target);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Creates a new file in a tree that is private because of its root, and opens it for writing. The file is requested
     * owner-only at creation where the platform allows it (POSIX {@code 0600}); on Windows it takes the ACL its folder
     * passes down. <b>No DACL is rewritten and the privacy of the file is not checked again</b>: it is the
     * confidentiality of the root, made private by {@link #ensurePrivateDirectory} or, for a tree a provider reads,
     * acquired by inheritance. This is the partial level on purpose, for the extraction of an archive or the copy of
     * thousands of files, where hardening each file would rewrite thousands of DACLs and, on Windows, take away the
     * access the sandbox identity gets by inheritance. The name must be free and is never followed; the immediate parent
     * must be a real directory (neither a link nor a special object), its ancestors are not checked. An existing name
     * is refused with a message that carries no path.
     *
     * @throws FileAlreadyExistsException if the name is taken
     * @throws IOException if the parent is absent, is a link or is not a directory
     */
    public static OutputStream openNewTreeFile(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("private storage tree file has no parent directory");
        BasicFileAttributes parentAttributes = readAttributesNoFollow(parent);
        if (parentAttributes.isSymbolicLink() || parentAttributes.isOther() || !parentAttributes.isDirectory()) {
            throw new IOException("private storage tree parent must be a real directory");
        }
        createNameOrRefuse(target, () -> createFileExclusively(target, parent),
                "private storage tree file already exists");
        return openOrDelete(target, PrivateLocalStorage::openStreamWithoutFollowingLinks);
    }

    /**
     * Hardens and verifies a just-created file, deleting it before propagating the failure so a
     * file this call created never lingers in a state that is not actually private.
     */
    private static void hardenOrDeleteAndThrow(Path target) throws IOException {
        try {
            hardenFile(target);
            verifyPrivateFile(target);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
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

    /**
     * Fails unless {@code path} is owned by the principal this process creates new objects as. The
     * private-storage checks compare an ACL with the <em>actual</em> owner of the object, which is the
     * right question for a location MINOS made, and the wrong one for a location it merely found: an
     * object owned by another principal always passes "owner only", because the owner is that principal,
     * and keeps the right to rewrite its own ACL whatever MINOS then writes into it. Call this on
     * anything that pre-exists, before hardening or trusting it.
     *
     * <p>The reference identity is not read from a name or a property (both can be set from outside): it
     * is the owner the operating system gives a file this process creates, once per JVM.</p>
     */
    public static void verifyOwnedByCurrentUser(Path path) throws IOException {
        Path target = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        UserPrincipal owner = Files.getOwner(target, LinkOption.NOFOLLOW_LINKS);
        if (!owner.equals(ProcessOwner.current(target))) {
            throw new IOException("private storage location is owned by another principal");
        }
    }

    public static void verifyPrivateFile(Path file) throws IOException {
        Path target = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        requireType(target, false);
        verifyPrivacy(target);
    }

    /**
     * Reports the enforcement actually in place, without changing anything. Intended for
     * diagnostics; it never throws for an exposed or unsupported location, it describes it. Callers
     * that need a safety guarantee must use one of the enforcement entry points above instead, which
     * fail closed rather than returning {@link Privacy#UNSUPPORTED} or {@link Privacy#EXPOSED}.
     */
    public static Privacy privacyOf(Path path) throws IOException {
        Path target = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Privacy.ABSENT;
        BasicFileAttributes attributes = readAttributesNoFollow(target);
        if (attributes.isSymbolicLink() || attributes.isOther()) return Privacy.EXPOSED;
        if (supportsPosix(target)) {
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
        boolean created = true;
        try {
            Files.createDirectory(target, privateDirectoryAttributes(target));
            // A new object at a path this process knew is a new object: what was said of the old one is void.
            forgetProtected(target);
        } catch (AccessDeniedException denied) {
            throw explainDenied(denied, parent);
        } catch (FileAlreadyExistsException concurrentlyCreated) {
            // A regular file occupying the name is not a lost race: it is refused in the same family
            // Files.createDirectories uses, without naming the path. A link or a special object falls
            // through to hardenDirectory, which refuses it with its own, more precise, diagnostic.
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(null, null, "private storage path exists and is not a directory");
            }
            // Another writer won the race; hardening and verification below still apply to it, but
            // it is theirs, not ours -- it must not be deleted if hardening fails below.
            created = false;
        }
        try {
            // POSIX gets owner-only permissions atomically at creation above; ACL platforms cannot
            // express an owner-only ACL as a creation-time FileAttribute, so every directory this
            // call creates -- not just the leaf the caller asked for -- must be hardened explicitly
            // here. Otherwise an intermediate directory would be left with whatever ACL it inherited.
            hardenDirectory(target);
            verifyPrivateDirectory(target);
        } catch (IOException failure) {
            if (created) deleteEmptyDirectoryBestEffort(target);
            throw failure;
        }
    }

    /** Best-effort cleanup of a directory this call itself just created and knows to be empty. */
    private static void deleteEmptyDirectoryBestEffort(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException cleanupFailure) {
            // Best-effort only: the real failure already propagates to the caller.
        }
    }

    private static void hardenDirectory(Path target) throws IOException {
        harden(target, DIRECTORY_PERMISSIONS, true);
    }

    private static void hardenFile(Path target) throws IOException {
        harden(target, FILE_PERMISSIONS, false);
    }

    private static void harden(Path target, Set<PosixFilePermission> permissions, boolean directory)
            throws IOException {
        BasicFileAttributes attributes = readAttributesNoFollow(target);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("private storage path must not be a symbolic link or reparse/special object");
        }
        if (supportsPosix(target)) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            if (!actual.equals(permissions)) Files.setPosixFilePermissions(target, permissions);
            return;
        }
        AclFileAttributeView acl = aclView(target);
        if (acl == null) throw unsupportedFilesystem(target);
        UserPrincipal owner = Files.getOwner(target, LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> current = acl.getAcl();
        List<AclEntry> desired = ownerOnlyAcl(current, owner, directory);
        String key = target.toString();
        // The first time this process touches an object, its DACL is rewritten even if the entries Java can
        // see already look right: Java's getAcl() does not show conditional (XA) entries, so an object that
        // inherits one would otherwise keep it (made explicit, and permanent, by the protection below).
        boolean firstTouch = !PROTECTED_LOCATIONS.contains(key);
        if (firstTouch || !current.equals(desired)) {
            CAPABILITY_PROBE.get().requireNoConditionalDeny(target);
            try {
                acl.setAcl(desired);
            } catch (AccessDeniedException denied) {
                throw WriteDenyDiagnosis.explainDenied(denied, current, owner);
            }
            PROTECTED_LOCATIONS.remove(key);
        }
        if (!PROTECTED_LOCATIONS.contains(key)) {
            CAPABILITY_PROBE.get().protectFromInheritance(target);
            rememberProtected(key);
        }
    }

    /**
     * The DACL a private location must have: every DENY entry it already carries (kept ahead of the
     * ALLOW entry, in their original order, inherited ones included), then the owner's entry.
     *
     * <p>The owner entry of a directory is inheritable: whatever any process of the owner creates in it
     * (a sandboxed provider writing its artifact, a tool unpacking an archive) is owner-only too,
     * instead of falling back to the default ACL of the creating process. Still no other principal.</p>
     */
    private static List<AclEntry> ownerOnlyAcl(List<AclEntry> current, UserPrincipal owner, boolean directory) {
        List<AclEntry> desired = new ArrayList<>();
        for (AclEntry entry : current) {
            if (entry.type() == AclEntryType.DENY) desired.add(entry);
        }
        AclEntry.Builder entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        desired.add(entry.build());
        return List.copyOf(desired);
    }

    /** Words {@code denied} as the write-protected failure when one of {@code locations} carries a write DENY. */
    private static IOException explainDenied(AccessDeniedException denied, Path... locations) {
        return WriteDenyDiagnosis.explainDenied(denied, PrivateLocalStorage::aclView, locations);
    }

    // ------------------------------------------------------------ protected DACLs (Windows)

    /**
     * Locations this process already made inheritance-proof. A pure optimisation: it spares a process
     * boundary per call on a location hardened earlier in this JVM, and a DACL that no longer equals the
     * expected one is rewritten and protected again whatever this set says.
     */
    private static final Set<String> PROTECTED_LOCATIONS = ConcurrentHashMap.newKeySet();
    private static final int MAX_REMEMBERED_LOCATIONS = 8_192;

    private static void forgetProtected(Path target) {
        PROTECTED_LOCATIONS.remove(target.toString());
    }

    private static void rememberProtected(String key) {
        if (PROTECTED_LOCATIONS.size() >= MAX_REMEMBERED_LOCATIONS) PROTECTED_LOCATIONS.clear();
        PROTECTED_LOCATIONS.add(key);
    }

    private static void verifyPrivacy(Path target) throws IOException {
        if (supportsPosix(target)) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> leaked = EnumSet.noneOf(PosixFilePermission.class);
            for (PosixFilePermission permission : actual) {
                if (FORBIDDEN_PERMISSIONS.contains(permission)) leaked.add(permission);
            }
            if (!leaked.isEmpty()) {
                throw new IOException("private storage is readable beyond its owner: grants " + leaked);
            }
            return;
        }
        AclFileAttributeView acl = aclView(target);
        if (acl == null) throw unsupportedFilesystem(target);
        AclEntry foreign = foreignAclEntry(acl);
        if (foreign != null) {
            throw new IOException("private storage grants access to another principal: "
                    + foreign.principal().getName());
        }
    }

    /**
     * Fails closed: the filesystem exposes neither a POSIX nor an ACL view, so ownership and
     * confidentiality cannot be enforced or verified for {@code target}. Enforcement entry points
     * must never treat this as success -- only the read-only {@link #privacyOf(Path)} diagnostic is
     * allowed to report {@link Privacy#UNSUPPORTED} without throwing.
     */
    private static IOException unsupportedFilesystem(Path target) {
        return new IOException("cannot enforce private storage: filesystem supports neither POSIX "
                + "permissions nor ACLs");
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
        BasicFileAttributes attributes = readAttributesNoFollow(target);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("private storage path must not be a symbolic link or reparse/special object");
        }
        if (directory && !attributes.isDirectory()) {
            throw new IOException("private storage path is not a directory");
        }
        if (!directory && !attributes.isRegularFile()) {
            throw new IOException("private storage path is not a regular file");
        }
    }

    private static BasicFileAttributes readAttributesNoFollow(Path target) throws IOException {
        try {
            return Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unreadable) {
            throw new IOException("private storage path is not readable");
        }
    }

    private static FileAttribute<?>[] privateDirectoryAttributes(Path target) {
        return posixAttributes(target, DIRECTORY_PERMISSIONS);
    }

    private static FileAttribute<?>[] privateFileAttributes(Path target) {
        return posixAttributes(target, FILE_PERMISSIONS);
    }

    private static FileAttribute<?>[] posixAttributes(Path target, Set<PosixFilePermission> permissions) {
        if (!supportsPosix(target)) return new FileAttribute<?>[0];
        return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(permissions)};
    }

    private static AclFileAttributeView aclView(Path target) {
        return CAPABILITY_PROBE.get().aclView(target);
    }

    private static boolean supportsPosix(Path target) {
        return CAPABILITY_PROBE.get().supportsPosix(target);
    }

    // ---------------------------------------------------------------- fault-injection seam (tests)

    private static final ThreadLocal<CapabilityProbe> CAPABILITY_PROBE =
            ThreadLocal.withInitial(CapabilityProbe::real);

    /**
     * Test-only: overrides filesystem capability probing for the calling thread.
     *
     * <p>This is {@code public} so tests in other modules can use it, which means any code sharing
     * this JVM process could technically call it too -- that alone is not a security boundary
     * against code already running in-process (nothing expressible with Java visibility modifiers
     * is: such code could just as easily call the real enforcement methods' own private internals
     * via reflection). MINOS's actual containment boundary for untrusted code is process isolation
     * (the AppContainer/Job Object and bubblewrap/cgroup sandboxes), not this method's visibility.
     * What this check <em>does</em> defend against is the realistic accident: a shipped MINOS
     * artifact does not bundle a JUnit dependency, so in an actual deployed process this throws
     * unconditionally, regardless of who calls it.</p>
     *
     * @throws IllegalStateException if called outside a test runtime (no JUnit Jupiter on the
     *         classpath)
     */
    public static void useForTesting(CapabilityProbe probe) {
        requireTestRuntime();
        CAPABILITY_PROBE.set(Objects.requireNonNull(probe, "probe"));
    }

    /** Test-only: restores real filesystem capability probing for the calling thread. */
    public static void resetCapabilityProbeForTesting() {
        CAPABILITY_PROBE.remove();
    }

    /** Test-only: forgets which locations this process already protected, as a fresh process would. */
    static void forgetProtectedLocationsForTesting() {
        PROTECTED_LOCATIONS.clear();
    }

    private static void requireTestRuntime() {
        try {
            Class.forName("org.junit.jupiter.api.Test", false, PrivateLocalStorage.class.getClassLoader());
        } catch (ClassNotFoundException notATestRuntime) {
            throw new IllegalStateException(
                    "PrivateLocalStorage.useForTesting is a test-only fault-injection hook; "
                            + "it refuses to run outside a JUnit test runtime");
        }
    }
}

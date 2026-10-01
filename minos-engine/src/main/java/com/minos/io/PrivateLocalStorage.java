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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
 * <p>The list of entries is rewritten only when it is not already the expected one, so a location that is
 * already right is not re-ACL'd. The protection against inheritance is a different matter: Java cannot read
 * that bit, so it is asserted once per object and per JVM with {@code icacls /inheritance:d}, which writes
 * the DACL (it needs WRITE_DAC, which an owner always holds) even when the bit was already set. That is one
 * short process per object the first time this JVM touches it, and none after, until the object is created
 * again.</p>
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
        try {
            Files.createFile(target, privateFileAttributes(target));
        } catch (AccessDeniedException denied) {
            throw explainDenied(denied, parent);
        }
        forgetProtected(target);
        hardenOrDeleteAndThrow(target);
        return target;
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
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            output.write(content);
        } catch (AccessDeniedException denied) {
            throw explainDenied(denied, target, target.getParent());
        }
        return target;
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
        if (!owner.equals(currentOwner())) {
            throw new IOException("private storage location is owned by another principal");
        }
    }

    private static volatile UserPrincipal currentOwner;

    private static UserPrincipal currentOwner() throws IOException {
        UserPrincipal known = currentOwner;
        if (known != null) return known;
        Path probe = Files.createTempFile("minos-owner-", ".probe");
        try {
            currentOwner = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            return currentOwner;
        } finally {
            Files.deleteIfExists(probe);
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
        if (!current.equals(desired)) {
            try {
                acl.setAcl(desired);
            } catch (AccessDeniedException denied) {
                throw explainDenied(denied, current, owner);
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

    // ------------------------------------------------------------ explicit deny entries (Windows)

    private static final String WRITE_PROTECTED_MESSAGE =
            "private storage is write-protected by an explicit deny entry; MINOS does not remove it";

    private static final Set<AclEntryPermission> WRITE_RIGHTS = EnumSet.of(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.WRITE_ACL);

    /**
     * The failure for an access-denied error on a location whose DACL carries a write DENY that applies to
     * the owner: MINOS says why it cannot write, and the message does not name the path. The original
     * error names it, so what is chained is an access-denied cause without its path.
     */
    private static IOException explainDenied(AccessDeniedException denied, List<AclEntry> aclOfTheLocation,
                                             UserPrincipal owner) {
        return carriesWriteDenyForOwner(aclOfTheLocation, owner) ? writeProtected() : denied;
    }

    private static IOException writeProtected() {
        return new IOException(WRITE_PROTECTED_MESSAGE, new AccessDeniedException(null));
    }

    /**
     * Whether a DENY entry that applies to the object itself takes a write right from the owner. An entry
     * counts when it is not inherit-only and names the owner, or a group: Java cannot say which groups the
     * process belongs to, so a DENY on a group is assumed to apply. That is wrong only when the owner is
     * not a member, and then the refusal had another cause that the message would mislabel.
     */
    static boolean carriesWriteDenyForOwner(List<AclEntry> acl, UserPrincipal owner) {
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.DENY) continue;
            if (entry.flags().contains(AclEntryFlag.INHERIT_ONLY)) continue;
            boolean applies = entry.principal().equals(owner)
                    || entry.principal() instanceof java.nio.file.attribute.GroupPrincipal;
            if (!applies) continue;
            for (AclEntryPermission right : entry.permissions()) {
                if (WRITE_RIGHTS.contains(right)) return true;
            }
        }
        return false;
    }

    /** Rethrows {@code denied} as the write-protected failure when one of {@code locations} carries a write DENY. */
    private static IOException explainDenied(AccessDeniedException denied, Path... locations) {
        for (Path location : locations) {
            if (location == null) continue;
            AclFileAttributeView view = aclView(location);
            if (view == null) continue;
            try {
                if (carriesWriteDenyForOwner(view.getAcl(), view.getOwner())) return writeProtected();
            } catch (IOException unreadable) {
                // Nothing more precise than the original refusal can be said.
            }
        }
        return denied;
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

    /**
     * Switches inheritance off on a Windows DACL. Java writes a DACL ({@link AclFileAttributeView#setAcl})
     * but cannot mark it protected, so an inheritable grant added later to a parent would flow back
     * into the location (measured: {@code icacls <parent> /grant Everyone:(OI)(CI)R} reached every
     * hardened child). {@code icacls /inheritance:d} is the smallest reliable way to set that bit; it runs
     * without a shell, by absolute path, on a path checked beforehand. It is {@code :d}, not {@code :r}:
     * a file just created in a private directory holds nothing but an inherited owner entry, which Java
     * reads back as equal to the expected one; {@code :r} would drop it and leave an empty DACL (measured),
     * {@code :d} turns it into an explicit entry.
     */
    private static final class WindowsDacl {
        private static final long TIMEOUT_SECONDS = 10L;

        private WindowsDacl() {
        }

        static void protect(Path target) throws IOException {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
            String path = target.toString();
            if (!target.isAbsolute() || path.chars().anyMatch(c -> c < 0x20 || "*?\"<>|".indexOf(c) >= 0)) {
                throw new IOException("cannot protect private storage from inheritance: unsupported path");
            }
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                throw new IOException("cannot protect private storage from inheritance: Windows directory unknown");
            }
            Process process = new ProcessBuilder(
                    Path.of(systemRoot, "System32", "icacls.exe").toString(), path, "/inheritance:d", "/L", "/q")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("cannot protect private storage from inheritance: timed out");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("cannot protect private storage from inheritance: interrupted");
            }
            if (process.exitValue() != 0) {
                throw new IOException("cannot protect private storage from inheritance (exit "
                        + process.exitValue() + ")");
            }
        }
    }

    // ---------------------------------------------------------------- fault-injection seam (tests)

    /**
     * What a given path's filesystem actually exposes for privacy enforcement. Production code
     * always uses {@link #real()}; tests may substitute a fake via
     * {@link #useForTesting(CapabilityProbe)} to deterministically exercise the POSIX, ACL and
     * unsupported-filesystem branches without depending on the real OS/filesystem under CI.
     *
     * <p>Public so that tests in other modules that build on {@code PrivateLocalStorage} (e.g.
     * {@code MinosApplication.open}) can inject the same deterministic fault without depending on
     * minos-engine's test sources or duplicating this seam. It is a fault-injection hook, not part
     * of the storage policy itself -- production code never references it, and {@link
     * #useForTesting} refuses to install one outside a test runtime (see there for why that is, and
     * is not, a security boundary).</p>
     */
    public interface CapabilityProbe {
        boolean supportsPosix(Path target);

        AclFileAttributeView aclView(Path target);

        /**
         * Makes the DACL of {@code target}, which already holds its final explicit entries, stop
         * inheriting from its parent. Only a platform whose inheritance Java cannot switch off through
         * {@link AclFileAttributeView} has anything to do here: the default does nothing, which is what a
         * test double wants.
         */
        default void protectFromInheritance(Path target) throws IOException {
        }

        static CapabilityProbe real() {
            return new CapabilityProbe() {
                @Override
                public boolean supportsPosix(Path target) {
                    return target.getFileSystem().supportedFileAttributeViews().contains("posix");
                }

                @Override
                public AclFileAttributeView aclView(Path target) {
                    return Files.getFileAttributeView(target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                }

                @Override
                public void protectFromInheritance(Path target) throws IOException {
                    WindowsDacl.protect(target);
                }
            };
        }
    }

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

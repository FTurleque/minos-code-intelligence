package com.minos.runtime.local;

import com.minos.io.ConfinedFileOpener;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.Sha256;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.InvalidPathException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A Windows sandbox launcher, as an artifact of the product rather than a file of the user's data.
 *
 * <p>The launchers are PowerShell scripts MINOS assembles from its own jar ({@link
 * WindowsContainmentScript}). A script that is executed has no place in MINOS_HOME, which holds data and
 * which an administrator may lock or share. It is materialised <em>outside</em> MINOS_HOME, under
 * {@code %LOCALAPPDATA%\minos-launchers\<sha256>\}, read-only, at a location named after its own SHA-256
 * (a different script is a different file, nothing is rewritten in place).</p>
 *
 * <h2>What is checked, and when</h2>
 * <p>A hash proves what the file holds, not who else can replace it. So, at materialisation and again
 * before every launch ({@link #verify()}): no ancestor of the root is a link or a reparse point; the
 * root, the {@code <sha256>} directory and the file are owned by the principal this process runs as
 * (a pre-existing one is checked <em>before</em> MINOS hardens it, because an owner keeps the right to
 * rewrite the ACL of what it owns whatever MINOS writes there) and are owner-only; the directory that
 * contains the root grants no principal outside the user, SYSTEM and Administrators the right to
 * delete, rewrite or take over what is under it (read once from the SDDL, then compared with the live ACL
 * before each launch); and finally the content equals the script the jar produces. Any failure refuses
 * the launch: there is no fallback to a weaker backend.</p>
 *
 * <h2>Limits, stated</h2>
 * <p>Between the last check and the moment PowerShell reads the file, a process of <em>the same
 * account</em> (or an administrator) can still substitute it: {@code -File} offers no way to close that
 * window without native code, and such a process is outside the confinement this class provides. A
 * principal that is neither can no longer do it. The ancestors above the directory that contains the
 * root are checked for links only, not for their ACL: the default ACL of the user's profile is relied on
 * there. A process that is elevated while the root was made by a non-elevated one (or the opposite) sees
 * another owner and refuses.</p>
 */
record SandboxLauncherScript(Path file, String sha256, List<AclEntry> parentAcl, UserPrincipal parentOwner) {

    private static final String ROOT_NAME = "minos-launchers";
    private static final int MAX_SCRIPT_BYTES = 1024 * 1024;
    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_DELAY_MILLIS = 50L;
    private static final String INTEGRITY_FAILURE = "sandbox launcher integrity check failed";
    private static final String UNREADABLE_RIGHTS =
            "sandbox launcher root: the access rights of its directory are unreadable";

    SandboxLauncherScript {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sha256, "sha256");
        parentAcl = List.copyOf(Objects.requireNonNull(parentAcl, "parentAcl"));
        Objects.requireNonNull(parentOwner, "parentOwner");
    }

    /** Materialises {@code launcherName} under {@code %LOCALAPPDATA%\minos-launchers}. */
    static SandboxLauncherScript materialize(String launcherName) throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base;
        try {
            base = localAppData == null || localAppData.isBlank() ? null : Path.of(localAppData);
        } catch (InvalidPathException invalid) {
            base = null;
        }
        if (base == null || !base.isAbsolute()) {
            throw new IOException("sandbox launcher root is unavailable: no private user directory");
        }
        return materialize(base.resolve(ROOT_NAME), launcherName);
    }

    static SandboxLauncherScript materialize(Path root, String launcherName) throws IOException {
        try {
            return materializeChecked(root, launcherName);
        } catch (IllegalStateException identityUnknown) {
            throw new IOException("sandbox launcher is unavailable: the identity of this process is unknown");
        } catch (FileSystemException failure) {
            // A file-system error names the path in its message; the launcher is reported without it.
            throw new IOException("sandbox launcher is unavailable (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private static SandboxLauncherScript materializeChecked(Path root, String launcherName) throws IOException {
        byte[] content = WindowsContainmentScript.assemble(launcherName).getBytes(StandardCharsets.UTF_8);
        String digest = Sha256.hex(content);
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Path directory = normalizedRoot.resolve(digest);
        requireNoLinkAmongAncestors(normalizedRoot);
        // Anything that was already there must be ours before MINOS hardens it or trusts it.
        for (Path existing : List.of(normalizedRoot, directory)) {
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                PrivateLocalStorage.verifyOwnedByCurrentUser(existing);
            }
        }
        PrivateLocalStorage.ensurePrivateDirectory(directory);
        requireOurPrivateDirectories(normalizedRoot, directory);
        Path target = directory.resolve(launcherName);
        ParentState parent = requireParentNotReplaceableByOthers(normalizedRoot, directory);
        SandboxLauncherScript script = new SandboxLauncherScript(target, digest, parent.acl(), parent.owner());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            PrivateLocalStorage.verifyOwnedByCurrentUser(target);
            if (script.contentMatches()) {
                script.verify();
                return script;
            }
            // Not the script the jar produces (truncated, tampered): MINOS restores its own artifact.
            Files.setAttribute(target, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
            Files.delete(target);
        }
        publish(directory, target, content);
        script.verify();
        return script;
    }

    /**
     * Fails closed unless the file is still exactly what {@link #materialize} checked: same ancestors,
     * same owners, same ACLs, same content.
     */
    void verify() throws IOException {
        try {
            Path directory = file.getParent();
            Path root = directory.getParent();
            requireNoLinkAmongAncestors(root);
            requireOurPrivateDirectories(root, directory);
            PrivateLocalStorage.verifyOwnedByCurrentUser(file);
            PrivateLocalStorage.verifyPrivateFile(file);
            AclFileAttributeView parent = Files.getFileAttributeView(
                    root.getParent(), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (parent != null
                    && (!parent.getAcl().equals(parentAcl) || !parent.getOwner().equals(parentOwner))) {
                throw new IOException(INTEGRITY_FAILURE);
            }
        } catch (IOException failure) {
            // The causes name paths and principals; a launcher that cannot be trusted is all that is said.
            throw new IOException(INTEGRITY_FAILURE);
        }
        if (!contentMatches()) throw new IOException(INTEGRITY_FAILURE);
    }

    private boolean contentMatches() throws IOException {
        byte[] content;
        try (InputStream raw = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            content = raw.readNBytes(MAX_SCRIPT_BYTES + 1);
        } catch (IOException unreadable) {
            // The cause names the path; the check only needs to say that the launcher cannot be trusted.
            throw new IOException(INTEGRITY_FAILURE);
        }
        return content.length <= MAX_SCRIPT_BYTES && Arrays.equals(
                Sha256.hex(content).getBytes(StandardCharsets.US_ASCII),
                sha256.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireOurPrivateDirectories(Path root, Path directory) throws IOException {
        for (Path dir : List.of(root, directory)) {
            PrivateLocalStorage.verifyOwnedByCurrentUser(dir);
            PrivateLocalStorage.verifyPrivateDirectory(dir);
        }
    }

    /** A link or a reparse point among the ancestors lets somebody else decide where the root really is. */
    private static void requireNoLinkAmongAncestors(Path root) throws IOException {
        for (Path current = root; current != null; current = current.getParent()) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("sandbox launcher root is reached through a link or a reparse point");
            }
        }
    }

    /** What was validated about the directory that contains the root, kept to be compared with before each launch. */
    private record ParentState(List<AclEntry> acl, UserPrincipal owner) { }

    /**
     * The directory that contains the root decides who can rename the root away and put another one in
     * its place, and its owner can always rewrite its DACL (the SDDL that {@code icacls /save} writes does
     * not carry the owner). Its DACL is read as SDDL (SIDs, so the answer does not depend on the language of
     * the machine) and must be the same as the live ACL read just before and just after, else it changed
     * while it was being read and is refused; its owner must be this process's principal, SYSTEM or
     * Administrators. The live ACL and owner are kept to be compared with before each launch.
     */
    private static ParentState requireParentNotReplaceableByOthers(Path root, Path scratch) throws IOException {
        Path parent = root.getParent();
        if (parent == null) throw new IOException("sandbox launcher root has no containing directory");
        AclFileAttributeView view = Files.getFileAttributeView(
                parent, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException(UNREADABLE_RIGHTS);
        // Written inside the directory MINOS just made private, never in a place others can write.
        Path saved = PrivateLocalStorage.createPrivateTempFile(scratch, ".acl-", ".txt");
        try {
            List<AclEntry> before = view.getAcl();
            UserPrincipal owner = view.getOwner();
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) throw new IOException("Windows directory unknown");
            Process process = new ProcessBuilder(
                    Path.of(systemRoot, "System32", "icacls.exe").toString(),
                    parent.toString(), "/save", saved.toString(), "/q")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException(UNREADABLE_RIGHTS);
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException(UNREADABLE_RIGHTS);
            }
            if (process.exitValue() != 0) throw new IOException(UNREADABLE_RIGHTS);
            String sddl = new String(Files.readAllBytes(saved), StandardCharsets.UTF_16LE);
            if (!view.getAcl().equals(before) || !view.getOwner().equals(owner)) {
                throw new IOException("sandbox launcher root: its directory changed while it was being checked");
            }
            Set<String> trusted = Set.of(ProcessIdentity.sid(), "S-1-5-18", "S-1-5-32-544");
            if (SddlReplaceRights.firstForeignReplaceGrant(sddl, trusted) != null) {
                throw new IOException(
                        "sandbox launcher root: another principal can replace what is under its directory");
            }
            boolean ownedByUs;
            try {
                PrivateLocalStorage.verifyOwnedByCurrentUser(parent);
                ownedByUs = true;
            } catch (IOException notOurs) {
                ownedByUs = false;
            }
            if (!SddlReplaceRights.ownerTrusted(owner, ownedByUs, before, sddl, trusted)) {
                throw new IOException("sandbox launcher root: its directory is owned by another principal");
            }
            return new ParentState(before, owner);
        } finally {
            Files.deleteIfExists(saved);
        }
    }

    private static void publish(Path directory, Path target, byte[] content) throws IOException {
        Path partial = PrivateLocalStorage.createPrivateTempFile(directory, ".launcher-", ".ps1");
        try {
            PrivateLocalStorage.writePrivateFile(partial, content);
            moveWithRetry(partial, target);
            Files.setAttribute(target, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * Bounded tolerance for a transient failure while publishing the launcher (a real-time antivirus
     * scan briefly holding the just-written file). The content is MINOS' own, not attacker-controlled,
     * so retrying a plain move carries none of the implications a provider-controlled path would. A
     * target another process published meanwhile is accepted only if {@link #verify()} then passes.
     */
    private static void moveWithRetry(Path source, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileSystemException failure) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
                if (attempt == MOVE_ATTEMPTS) throw new IOException("sandbox launcher could not be published");
                try {
                    Thread.sleep(MOVE_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("sandbox launcher could not be published");
                }
            }
        }
    }

    /**
     * Removes the copy an earlier MINOS left among the data, best effort: it is the same script, but
     * an executable among data is exactly what this class exists to avoid.
     */
    static void removeLegacyCopy(Path minosHome, String launcherName) {
        Path legacy = minosHome.toAbsolutePath().normalize().resolve("sandbox").resolve(launcherName);
        try {
            if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) {
                Files.setAttribute(legacy, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                Files.deleteIfExists(legacy);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // A copy that cannot be removed is no longer used by anything; it is not a failure.
        }
    }

    /** The launcher must not sit among the data: refused when {@code file} is under {@code minosHome}. */
    static void requireOutsideMinosHome(Path file, Path minosHome) throws IOException {
        if (file.toAbsolutePath().normalize().startsWith(minosHome.toAbsolutePath().normalize())) {
            throw new IOException("sandbox launcher must not be materialised inside MINOS_HOME");
        }
    }
}

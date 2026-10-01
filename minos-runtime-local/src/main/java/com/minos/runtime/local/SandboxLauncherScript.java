package com.minos.runtime.local;

import com.minos.io.ConfinedFileOpener;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.Sha256;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * A Windows sandbox launcher, as an artifact of the product rather than a file of the user's data.
 *
 * <p>The launchers are PowerShell scripts MINOS assembles from its own jar ({@link
 * WindowsContainmentScript}). A script that is executed has no place in MINOS_HOME, which holds data
 * and which an administrator may lock or share. The script is therefore materialised <em>outside</em>
 * MINOS_HOME, in a private directory of the user under the temporary directory of the JVM, at a
 * location named after its own SHA-256 (so a different script is a different file and nothing is ever
 * rewritten in place), read-only, and its content is checked against that SHA-256 before every launch:
 * a script that is no longer the one the jar produced is refused, never run and never replaced by a
 * weaker fallback.</p>
 *
 * <p>Limit, stated: between the check and the moment PowerShell reads the file, another process of the
 * same user could still substitute it. {@code -File} offers no way to close that window without native
 * code, and a process of the same user is outside the confinement this class provides; what it removes
 * is the script sitting among the data, where it was a persistent, writable launch point.</p>
 */
record SandboxLauncherScript(Path file, String sha256) {

    private static final String ROOT_NAME = "minos-launchers";
    private static final int MAX_SCRIPT_BYTES = 1024 * 1024;
    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_DELAY_MILLIS = 50L;

    SandboxLauncherScript {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sha256, "sha256");
    }

    /** Materialises {@code launcherName} under the default root, outside MINOS_HOME. */
    static SandboxLauncherScript materialize(String launcherName) throws IOException {
        return materialize(Path.of(System.getProperty("java.io.tmpdir")).resolve(ROOT_NAME), launcherName);
    }

    static SandboxLauncherScript materialize(Path root, String launcherName) throws IOException {
        byte[] content = WindowsContainmentScript.assemble(launcherName).getBytes(StandardCharsets.UTF_8);
        String digest = Sha256.hex(content);
        Path directory = PrivateLocalStorage.ensurePrivateDirectory(
                Objects.requireNonNull(root, "root").resolve(digest));
        Path target = directory.resolve(launcherName);
        SandboxLauncherScript script = new SandboxLauncherScript(target, digest);
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (script.matches()) return script;
            // Not the script the jar produces (truncated, tampered): MINOS restores its own artifact.
            Files.setAttribute(target, "dos:readonly", false, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Files.delete(target);
        }
        publish(directory, target, content);
        if (!script.matches()) {
            throw new IOException("sandbox launcher integrity check failed");
        }
        return script;
    }

    /** Fails closed unless the file still holds exactly the script this value was built for. */
    void verify() throws IOException {
        if (!matches()) throw new IOException("sandbox launcher integrity check failed");
    }

    private boolean matches() throws IOException {
        byte[] content;
        try (InputStream raw = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            content = raw.readNBytes(MAX_SCRIPT_BYTES + 1);
        } catch (IOException unreadable) {
            // The cause names the path; the check only needs to say that the launcher cannot be trusted.
            throw new IOException("sandbox launcher integrity check failed");
        }
        return content.length <= MAX_SCRIPT_BYTES && Arrays.equals(
                Sha256.hex(content).getBytes(StandardCharsets.US_ASCII),
                sha256.getBytes(StandardCharsets.US_ASCII));
    }

    private static void publish(Path directory, Path target, byte[] content) throws IOException {
        Path partial = PrivateLocalStorage.createPrivateTempFile(directory, ".launcher-", ".ps1");
        try {
            PrivateLocalStorage.writePrivateFile(partial, content);
            moveWithRetry(partial, target);
            Files.setAttribute(target, "dos:readonly", true, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /**
     * Bounded tolerance for a transient failure while publishing the launcher (a real-time antivirus
     * scan briefly holding the just-written file). The content is MINOS' own, not attacker-controlled,
     * so retrying a plain move carries none of the implications a provider-controlled path would. A
     * target another process published meanwhile is accepted only if it matches (see {@link #matches}).
     */
    private static void moveWithRetry(Path source, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (FileAlreadyExistsException winnerPublishedFirst) {
                return;
            } catch (FileSystemException failure) {
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
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
            if (Files.exists(legacy, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.setAttribute(legacy, "dos:readonly", false, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                Files.deleteIfExists(legacy);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // A copy that cannot be removed is no longer used by anything; it is not a failure.
        }
    }
}

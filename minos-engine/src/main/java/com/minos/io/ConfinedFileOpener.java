package com.minos.io;

import java.io.IOException;
import java.io.Serial;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Set;

/**
 * Opens a project-relative file so that the bytes actually read come from an object that is proven
 * to be a regular, non-symlink file inside a validated root.
 *
 * <p>The usual "validate then open" shape -- {@code Files.isRegularFile} / {@code toRealPath}
 * followed by {@code Files.newInputStream(path)} -- validates a <em>pathname</em> and then re-walks
 * that pathname a second time when opening. Anything able to write into the workspace concurrently
 * can replace the validated object with a symlink in between, and the second walk follows it. The
 * fix is not to shorten that window: re-running {@code toRealPath()} immediately before the open
 * narrows it without removing it. The window has to stop existing.</p>
 *
 * <p>Two strategies deliver that, chosen from what the platform actually provides:</p>
 * <ul>
 *   <li><b>{@link SecureDirectoryStream} (POSIX).</b> The root is opened once, then each directory
 *       segment is descended through the <em>open directory handle</em> with {@link
 *       LinkOption#NOFOLLOW_LINKS} -- the {@code openat} model. No pathname is ever re-resolved, so
 *       there is nothing for a concurrent rename to redirect: whatever is opened is by construction
 *       an entry of a directory chain that was descended from the real root without traversing a
 *       single link.</li>
 *   <li><b>Fail-closed fallback (Windows and any provider without secure streams).</b> The final
 *       component is opened with {@code NOFOLLOW_LINKS}, which is atomic: the open either yields a
 *       non-symlink object or fails, so the classic "swap the file for a symlink" race cannot be
 *       won on the leaf at all. The ancestor chain is then verified <em>while the handle is
 *       already open</em>, and the channel is closed and the read refused unless every ancestor is
 *       a real directory and the file still resolves inside the root.</li>
 * </ul>
 *
 * <p>Both strategies refuse a symlink outright rather than accepting one that happens to land back
 * inside the root: a link is not needed to read project sources, and accepting it would mean
 * re-deciding containment from a resolved pathname, which is the very thing being removed.</p>
 */
public final class ConfinedFileOpener {

    /**
     * TEST-ONLY seam. Runs after the last validation step and immediately before the file is
     * opened, which is exactly the instant a real attacker would need to hit. A test uses it to
     * perform the replacement deterministically -- with a latch or a direct call -- instead of
     * racing a timer and calling the result reproducible.
     */
    static volatile Runnable beforeOpenForTests = () -> { };

    private ConfinedFileOpener() {
    }

    /** Refusal to establish the confinement guarantee. Never carries the offending path. */
    public static final class ConfinementException extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;

        public ConfinementException(String message) {
            super(message);
        }

        public ConfinementException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Opens {@code root/relative} for reading under the guarantees described above.
     *
     * @param root     an already resolved real directory (see {@link Path#toRealPath}); it is the
     *                 confinement boundary and is never re-derived from the returned channel
     * @param relative a normalized relative path inside {@code root}
     * @throws ConfinementException           if the guarantee cannot be established
     * @throws java.nio.file.NoSuchFileException if the file (or one of its directories) is absent
     */
    public static SeekableByteChannel openConfinedRegularFile(Path root, Path relative) throws IOException {
        Path boundary = Objects.requireNonNull(root, "root");
        Path target = requireConfinedRelativePath(Objects.requireNonNull(relative, "relative"));
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(boundary)) {
            if (rootStream instanceof SecureDirectoryStream<Path> secureRoot) {
                return openThroughDirectoryHandles(secureRoot, target);
            }
        }
        return openAndVerifyWhileHeld(boundary, target);
    }

    /** Whether this platform can offer the {@code openat}-style guarantee at all. */
    public static boolean supportsDirectoryHandleTraversal(Path root) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Objects.requireNonNull(root, "root"))) {
            return stream instanceof SecureDirectoryStream;
        }
    }

    private static Path requireConfinedRelativePath(Path relative) throws ConfinementException {
        Path normalized = relative.normalize();
        if (relative.isAbsolute() || normalized.getNameCount() == 0 || !normalized.equals(relative)) {
            throw new ConfinementException("path must be a normalized project-relative path");
        }
        for (int index = 0; index < normalized.getNameCount(); index++) {
            String segment = normalized.getName(index).toString();
            if (segment.isEmpty() || "..".equals(segment) || ".".equals(segment)) {
                throw new ConfinementException("path must not contain traversal segments");
            }
        }
        return normalized;
    }

    private static SeekableByteChannel openThroughDirectoryHandles(
            SecureDirectoryStream<Path> root,
            Path relative
    ) throws IOException {
        Deque<SecureDirectoryStream<Path>> descended = new ArrayDeque<>();
        try {
            SecureDirectoryStream<Path> parent = root;
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                SecureDirectoryStream<Path> next =
                        parent.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS);
                descended.push(next);
                parent = next;
            }
            final SecureDirectoryStream<Path> current = parent;
            Path fileName = relative.getFileName();
            BasicFileAttributes attributes = current
                    .getFileAttributeView(fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new ConfinementException("source is not a regular non-symlink file");
            }
            beforeOpenForTests.run();
            // NOFOLLOW_LINKS here is what makes the replacement race unwinnable rather than merely
            // unlikely: if the name was swapped for a link after the attribute read, this fails.
            try {
                return current.newByteChannel(
                        fileName, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            } catch (NoSuchFileException | AccessDeniedException propagated) {
                throw propagated;
            } catch (IOException failure) {
                throw classifyOpenFailure(
                        () -> current
                                .getFileAttributeView(fileName, BasicFileAttributeView.class,
                                        LinkOption.NOFOLLOW_LINKS)
                                .readAttributes(),
                        failure);
            }
        } finally {
            closeAll(descended);
        }
    }

    private static SeekableByteChannel openAndVerifyWhileHeld(Path root, Path relative) throws IOException {
        Path candidate = root.resolve(relative);
        verifyAncestorChain(root, relative);
        beforeOpenForTests.run();
        SeekableByteChannel channel;
        try {
            channel = Files.newByteChannel(
                    candidate, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException | AccessDeniedException propagated) {
            throw propagated;
        } catch (IOException failure) {
            throw classifyOpenFailure(
                    () -> Files.readAttributes(
                            candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS),
                    failure);
        }
        try {
            // The handle is open at this point, so the object it refers to can no longer be turned
            // into something else behind our back; re-checking the chain now therefore proves what
            // was opened, instead of predicting what a later open would find.
            verifyAncestorChain(root, relative);
            BasicFileAttributes attributes =
                    Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                throw new ConfinementException("source is not a regular non-symlink file");
            }
            if (!candidate.toRealPath().startsWith(root)) {
                throw new ConfinementException("source resolves outside the project root");
            }
            return channel;
        } catch (IOException | RuntimeException failure) {
            closeQuietly(channel, failure);
            throw failure;
        }
    }

    private static void verifyAncestorChain(Path root, Path relative) throws IOException {
        Path current = root;
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            current = current.resolve(relative.getName(index));
            BasicFileAttributes attributes =
                    Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new ConfinementException("source path crosses a non-directory or a link");
            }
            if (!current.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root)) {
                throw new ConfinementException("source path escapes the project root");
            }
        }
    }

    /**
     * Names the reason a {@code NOFOLLOW_LINKS} open was rejected.
     *
     * <p>Refusing to follow a link surfaces as a platform-specific failure -- {@code ELOOP} on
     * POSIX, {@code "File is symbolic link"} on Windows -- and callers must not have to pattern
     * match on either. When the object under the requested name is genuinely not a regular file,
     * the refusal is republished as a {@link ConfinementException}; anything else keeps its original
     * failure, because turning an unrelated I/O error into a confinement verdict would be a lie in
     * the other direction.</p>
     */
    private static IOException classifyOpenFailure(AttributeReader reader, IOException failure) {
        try {
            BasicFileAttributes attributes = reader.read();
            if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                return new ConfinementException("source is not a regular non-symlink file", failure);
            }
        } catch (IOException unreadable) {
            failure.addSuppressed(unreadable);
        }
        return failure;
    }

    @FunctionalInterface
    private interface AttributeReader {
        BasicFileAttributes read() throws IOException;
    }

    private static void closeAll(Deque<SecureDirectoryStream<Path>> streams) throws IOException {
        IOException failure = null;
        while (!streams.isEmpty()) {
            try {
                streams.pop().close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeQuietly(SeekableByteChannel channel, Throwable primary) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }
}

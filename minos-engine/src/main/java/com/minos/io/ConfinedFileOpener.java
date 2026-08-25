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
 * Opens a project-relative file using the strongest confinement primitive exposed by the platform,
 * while refusing links, filesystem-specific/special objects and non-regular leaves.
 *
 * <p>The usual "validate then open" shape -- {@code Files.isRegularFile} / {@code toRealPath}
 * followed by {@code Files.newInputStream(path)} -- validates a <em>pathname</em> and then re-walks
 * that pathname a second time when opening. Anything able to write into the workspace concurrently
 * can replace the validated object with a symlink in between, and the second walk follows it. The
 * fix for platforms exposing directory handles is not to shorten that window: the traversal itself
 * must stay relative to already-open directories.</p>
 *
 * <p>Two strategies are used, and their guarantees are deliberately not described as identical:</p>
 * <ul>
 *   <li><b>{@link SecureDirectoryStream} (POSIX).</b> The root is opened once, then each directory
 *       segment is descended through the <em>open directory handle</em> with {@link
 *       LinkOption#NOFOLLOW_LINKS} -- the {@code openat} model. No pathname is ever re-resolved, so
 *       there is nothing for a concurrent ancestor rename to redirect: whatever is opened is by
 *       construction an entry of a directory chain descended from the real root without traversing
 *       a link or special filesystem object.</li>
 *   <li><b>Path-revalidated fallback (Windows and providers without secure streams).</b> The final
 *       component is opened with {@code NOFOLLOW_LINKS}, so a leaf swapped to a symbolic link is
 *       refused atomically. The ancestor chain and leaf type are then revalidated while the channel
 *       remains open, including rejection of {@link BasicFileAttributes#isOther()} reparse/special
 *       objects. Standard Java NIO does not expose a Windows directory-handle-relative traversal or
 *       a portable way to query the opened channel's file identity, so this fallback intentionally
 *       does <em>not</em> claim the same ancestor-identity proof as {@code SecureDirectoryStream}.
 *       Callers that require that stronger proof can test {@link #supportsDirectoryHandleTraversal(Path)}
 *       and fail closed.</li>
 * </ul>
 *
 * <p>Both strategies refuse a link outright rather than accepting one that happens to land back
 * inside the root. Windows junction/reparse points and other objects reported through
 * {@link BasicFileAttributes#isOther()} are refused for the same reason.</p>
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
     * @param root     the directory that is the confinement boundary; it is canonicalized here, once,
     *                 and is never re-derived from the returned channel
     * @param relative a normalized relative path inside {@code root}
     * @throws ConfinementException           if the guarantee cannot be established
     * @throws java.nio.file.NoSuchFileException if the file (or one of its directories) is absent
     */
    public static SeekableByteChannel openConfinedRegularFile(Path root, Path relative) throws IOException {
        // The boundary is resolved here rather than trusted from the caller. Establishing where the
        // project *is* happens once, before anything is opened, and is not the race this class
        // exists to close -- but comparing a canonicalized ancestor against a boundary that is not
        // canonical is a real trap: on Windows a short 8.3 temp path (RUNNER~1) resolves to its long
        // form, and every read would be refused as an escape. Fail-closed, but wrongly so.
        Path boundary = Objects.requireNonNull(root, "root").toRealPath();
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
                Path segment = relative.getName(index);
                final SecureDirectoryStream<Path> from = parent;
                SecureDirectoryStream<Path> next;
                try {
                    next = from.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException | AccessDeniedException propagated) {
                    throw propagated;
                } catch (IOException failure) {
                    // Refusing to descend a linked directory surfaces as ELOOP here and as an
                    // explicit chain check in the fallback. Classifying both the same way keeps one
                    // hostile situation looking like one failure to a caller, whatever the platform.
                    throw classifyDescentFailure(
                            () -> from
                                    .getFileAttributeView(segment, BasicFileAttributeView.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .readAttributes(),
                            failure);
                }
                descended.push(next);
                parent = next;
            }
            final SecureDirectoryStream<Path> current = parent;
            Path fileName = relative.getFileName();
            BasicFileAttributes attributes = current
                    .getFileAttributeView(fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                throw new ConfinementException("source is not a regular physical file");
            }
            beforeOpenForTests.run();
            // NOFOLLOW_LINKS here is what makes a leaf replacement by a symbolic link fail instead
            // of following the attacker's new target.
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
            // The channel stays open while the pathname is revalidated. This closes the classic
            // leaf-symlink race, but these pathname checks are intentionally not described as a
            // handle-relative ancestor identity proof on platforms without SecureDirectoryStream.
            verifyAncestorChain(root, relative);
            BasicFileAttributes attributes =
                    Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                throw new ConfinementException("source is not a regular physical file");
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
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
                throw new ConfinementException("source path crosses a non-directory, link or special object");
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
     * match on either. When the object under the requested name is genuinely not what the traversal
     * required, the refusal is republished as a {@link ConfinementException}; anything else keeps
     * its original failure, because turning an unrelated I/O error into a confinement verdict would
     * be a lie in the other direction.</p>
     */
    private static IOException classifyOpenFailure(AttributeReader reader, IOException failure) {
        return classify(reader, failure, false);
    }

    private static IOException classifyDescentFailure(AttributeReader reader, IOException failure) {
        return classify(reader, failure, true);
    }

    private static IOException classify(AttributeReader reader, IOException failure, boolean directory) {
        try {
            BasicFileAttributes attributes = reader.read();
            boolean wrongKind = directory ? !attributes.isDirectory() : !attributes.isRegularFile();
            if (attributes.isSymbolicLink() || attributes.isOther() || wrongKind) {
                return new ConfinementException(directory
                        ? "source path crosses a non-directory, link or special object"
                        : "source is not a regular physical file", failure);
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

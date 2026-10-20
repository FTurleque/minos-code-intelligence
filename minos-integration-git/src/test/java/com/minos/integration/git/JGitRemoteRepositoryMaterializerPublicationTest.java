package com.minos.integration.git;

import com.minos.io.DurableAtomicFile;
import com.minos.remote.RemoteRepositoryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publication of a cache entry is the exclusive, atomic rename of the directory that was cloned in a temporary
 * (AUD-SEC-03): without an atomic rename the materialization fails with the message it always had and leaves no
 * entry; an entry that appeared meanwhile is never replaced, an empty directory included.
 */
class JGitRemoteRepositoryMaterializerPublicationTest {

    private static final String ATOMIC_MESSAGE = "remote repository cache requires atomic directory publication";

    private static RemoteRepositoryRequest request(Path source) throws Exception {
        return JGitRemoteRepositoryMaterializerTest.request(JGitRemoteRepositoryMaterializerTest.head(source), null);
    }

    private static JGitRemoteRepositoryMaterializer materializer(
            Path home, Path source, JGitRemoteRepositoryMaterializer.DirectoryPublisher publisher,
            AtomicInteger clones) throws Exception {
        return JGitRemoteRepositoryMaterializerTest.materializer(
                home, new RemoteRepositoryCachePolicy(2, 1024L * 1024L), source, clones, name -> Optional.empty(),
                publisher);
    }

    private static long entries(Path home) throws IOException {
        try (Stream<Path> children = Files.list(home.resolve("remote-cache").resolve("repositories"))) {
            return children.count();
        }
    }

    @Test
    void aFilesystemWithoutAtomicRenameFailsWithTheUsualMessageAndLeavesNoEntry(@TempDir Path temp) throws Exception {
        Path source = JGitRemoteRepositoryMaterializerTest.createRepository(temp.resolve("source"));
        RemoteRepositoryRequest request = request(source);
        Path home = temp.resolve("home");
        AtomicInteger publications = new AtomicInteger();
        JGitRemoteRepositoryMaterializer materializer = materializer(home, source, (from, target) -> {
            publications.incrementAndGet();
            throw new IOException("filesystem does not support required atomic directory",
                    new AtomicMoveNotSupportedException("a", "b", "no atomic rename"));
        }, new AtomicInteger());

        IOException failure = assertThrows(IOException.class, () -> materializer.materialize(request));

        assertEquals(ATOMIC_MESSAGE, failure.getMessage());
        assertInstanceOf(AtomicMoveNotSupportedException.class, failure.getCause().getCause());
        assertEquals(1, publications.get(), "no second attempt and no copy");
        assertEquals(0L, entries(home), "neither the entry nor the temporary of the clone is left");
    }

    @Test
    void anEntryWhichAppearedBeforeTheRenameIsNeverReplacedEvenWhenItIsAnEmptyDirectory(@TempDir Path temp)
            throws Exception {
        Path source = JGitRemoteRepositoryMaterializerTest.createRepository(temp.resolve("source"));
        RemoteRepositoryRequest request = request(source);
        Path home = temp.resolve("home");
        Path squatter = temp.resolve("squatter");
        JGitRemoteRepositoryMaterializer materializer = materializer(home, source, (from, target) -> {
            // rename(2) would replace an empty directory: the publication must refuse it instead.
            Files.createDirectory(target);
            Files.writeString(squatter, target.getFileName().toString());
            DurableAtomicFile.moveDirectory(from, target, "remote repository cache entry publication");
        }, new AtomicInteger());

        IOException failure = assertThrows(IOException.class, () -> materializer.materialize(request));

        assertInstanceOf(FileAlreadyExistsException.class, failure);
        Path entry = home.resolve("remote-cache").resolve("repositories").resolve(Files.readString(squatter));
        assertTrue(Files.isDirectory(entry), "the directory which took the name is still there");
        try (Stream<Path> children = Files.list(entry)) {
            assertEquals(0L, children.count(), "and it is still empty: nothing was moved into it");
        }
        assertFalse(Files.exists(entry.resolve("repository")));
    }

    @Test
    void theNominalPublicationAndTheCachedEntryStayGreen(@TempDir Path temp) throws Exception {
        Path source = JGitRemoteRepositoryMaterializerTest.createRepository(temp.resolve("source"));
        RemoteRepositoryRequest request = request(source);
        Path home = temp.resolve("home");
        AtomicInteger clones = new AtomicInteger();
        JGitRemoteRepositoryMaterializer materializer = materializer(home, source,
                JGitRemoteRepositoryMaterializer.DirectoryPublisher.ATOMIC, clones);

        var first = materializer.materialize(request);
        var second = materializer.materialize(request);

        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        assertEquals(1, clones.get(), "the second call is served from the cache entry");
        assertEquals(1L, entries(home));
    }
}

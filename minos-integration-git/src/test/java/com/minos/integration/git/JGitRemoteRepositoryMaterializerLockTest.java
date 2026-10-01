package com.minos.integration.git;

import com.minos.io.PrivateLocalStorage;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The materialization lock: bounded, fail closed, owner-only, never through a link. */
class JGitRemoteRepositoryMaterializerLockTest {

    @TempDir
    Path root;

    @Test
    void aMaterializationLockHeldElsewhereFailsClosedAfterTheDeadlineWithoutLeakingAPath() throws Exception {
        Path source = createRepository(root.resolve("source"));
        String commit = head(source);
        JGitRemoteRepositoryMaterializer materializer =
                materializer(root.resolve("home"), source, Duration.ofMillis(300));
        materializer.release(materializer.materialize(request(commit)));
        Path lockFile = onlyLockFile(root.resolve("home"));

        try (FileChannel holder = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = holder.lock()) {
            IOException failure = assertThrows(IOException.class, () -> materializer.materialize(request(commit)));
            assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
            assertFalse(failure.getMessage().contains(root.toString()), "no absolute path in the message");
        }
    }

    @Test
    void theMaterializationLockCanBeAcquiredAfterThePreviousHolderReleases() throws Exception {
        Path source = createRepository(root.resolve("source"));
        String commit = head(source);
        JGitRemoteRepositoryMaterializer materializer =
                materializer(root.resolve("home"), source, Duration.ofSeconds(10));
        materializer.release(materializer.materialize(request(commit)));
        Path lockFile = onlyLockFile(root.resolve("home"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (FileChannel holder = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = holder.lock();
            Future<RemoteMaterialization> waiting = pool.submit(() -> materializer.materialize(request(commit)));
            Thread.sleep(300);
            assertFalse(waiting.isDone(), "the materialization waits for the holder");
            held.release();
            materializer.release(waiting.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theMaterializationLockFileIsOwnerOnly() throws Exception {
        Path source = createRepository(root.resolve("source"));
        JGitRemoteRepositoryMaterializer materializer =
                materializer(root.resolve("home"), source, Duration.ofSeconds(10));
        materializer.release(materializer.materialize(request(head(source))));

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED,
                PrivateLocalStorage.privacyOf(onlyLockFile(root.resolve("home"))));
    }

    @Test
    void aMaterializationLockFileReplacedByASymbolicLinkIsRefusedNotFollowed() throws Exception {
        Path source = createRepository(root.resolve("source"));
        String commit = head(source);
        JGitRemoteRepositoryMaterializer materializer =
                materializer(root.resolve("home"), source, Duration.ofSeconds(10));
        materializer.release(materializer.materialize(request(commit)));
        Path lockFile = onlyLockFile(root.resolve("home"));
        Path outside = Files.writeString(root.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
        Files.delete(lockFile);
        Files.createSymbolicLink(lockFile, outside);

        assertThrows(IOException.class, () -> materializer.materialize(request(commit)));
        assertEquals("outside", Files.readString(outside, StandardCharsets.UTF_8));
    }

    @Test
    void thePinFileAndTheEntryMetadataAreOwnerOnlyAndThePinIsNeverWrittenThroughALink() throws Exception {
        Path source = createRepository(root.resolve("source"));
        String commit = head(source);
        JGitRemoteRepositoryMaterializer materializer =
                materializer(root.resolve("home"), source, Duration.ofSeconds(10));
        RemoteMaterialization materialization = materializer.materialize(request(commit));
        try {
            Path entry = materialization.repositoryRoot().getParent();
            assertEquals(PrivateLocalStorage.Privacy.ENFORCED,
                    PrivateLocalStorage.privacyOf(entry.resolve("entry.properties")));

            materializer.pin(materialization);
            assertEquals(PrivateLocalStorage.Privacy.ENFORCED,
                    PrivateLocalStorage.privacyOf(entry.resolve("registered.pin")));

            Path outside = Files.writeString(root.resolve("outside.txt"), "outside", StandardCharsets.UTF_8);
            Files.delete(entry.resolve("registered.pin"));
            Files.createSymbolicLink(entry.resolve("registered.pin"), outside);
            assertThrows(IOException.class, () -> materializer.pin(materialization));
            assertEquals("outside", Files.readString(outside, StandardCharsets.UTF_8));
        } finally {
            materializer.release(materialization);
        }
    }

    private static Path onlyLockFile(Path home) throws IOException {
        try (DirectoryStream<Path> locks = Files.newDirectoryStream(home.resolve("remote-cache/locks"), "*.lock")) {
            Path found = null;
            for (Path lock : locks) {
                assertNull(found, "exactly one lock file expected");
                found = lock;
            }
            return found;
        }
    }

    private static JGitRemoteRepositoryMaterializer materializer(Path home, Path source, Duration lockTimeout)
            throws IOException {
        return new JGitRemoteRepositoryMaterializer(
                home,
                RemoteRepositoryCachePolicy.DEFAULT,
                (request, destination, secret, budget) -> {
                    try (Git cloned = Git.cloneRepository()
                            .setURI(source.toUri().toString())
                            .setDirectory(destination.toFile())
                            .setBranch(request.reference())
                            .call()) {
                        cloned.getRepository().getConfig().setString(
                                "remote", "origin", "url", request.canonicalRepositoryUri());
                        cloned.getRepository().getConfig().save();
                    }
                    budget.checkpoint();
                },
                name -> Optional.empty(),
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
                lockTimeout);
    }

    private static RemoteRepositoryRequest request(String commit) {
        return RemoteRepositoryRequest.of("https://github.com/acme/demo", "main", commit, "fixtures/java", null);
    }

    private static Path createRepository(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("fixtures/java"));
        Files.writeString(directory.resolve("fixtures/java/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            PersonIdent identity = new PersonIdent("MINOS Test", "minos@example.invalid",
                    Date.from(Instant.parse("2026-07-29T00:00:00Z")), TimeZone.getTimeZone("UTC"));
            git.commit().setMessage("initial").setAuthor(identity).setCommitter(identity).call();
        }
        return directory;
    }

    private static String head(Path repository) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }
}

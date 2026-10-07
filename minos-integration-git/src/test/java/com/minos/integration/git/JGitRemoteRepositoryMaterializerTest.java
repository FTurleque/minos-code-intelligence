package com.minos.integration.git;

import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JGitRemoteRepositoryMaterializerTest {

    @Test
    void clonesOnceReusesAValidatedCacheAndNeverPersistsCredentialMaterial(@TempDir Path temp) throws Exception {
        Path source = createRepository(temp.resolve("source"));
        String commit = head(source);
        RemoteRepositoryRequest request = request(commit, "MINOS_REMOTE_TOKEN");
        AtomicInteger clones = new AtomicInteger();
        AtomicReference<char[]> resolvedSecret = new AtomicReference<>();
        JGitRemoteRepositoryMaterializer materializer = materializer(
                temp.resolve("home"), new RemoteRepositoryCachePolicy(2, 1024L * 1024L), source, clones,
                name -> {
                    assertEquals("MINOS_REMOTE_TOKEN", name);
                    char[] value = "super-secret-token".toCharArray();
                    resolvedSecret.set(value);
                    return Optional.of(value);
                });

        RemoteMaterialization first = materializer.materialize(request);
        RemoteMaterialization second = materializer.materialize(request);
        try {
            assertFalse(first.cacheHit());
            assertTrue(second.cacheHit());
            assertEquals(1, clones.get());
            assertEquals(first.cacheKey(), second.cacheKey());
            assertEquals(commit, head(second.repositoryRoot()));
            assertTrue(Files.isRegularFile(second.projectRoot().resolve("pom.xml")));
            assertTrue(new String(resolvedSecret.get()).chars().allMatch(value -> value == 0));
            String metadata = Files.readString(first.repositoryRoot().getParent().resolve("entry.properties"));
            String gitConfig = Files.readString(first.repositoryRoot().resolve(".git/config"));
            assertFalse(metadata.contains("super-secret-token"));
            assertFalse(metadata.contains("MINOS_REMOTE_TOKEN"));
            assertFalse(gitConfig.contains("super-secret-token"));
            assertEquals("https://github.com/acme/demo.git", request.canonicalRepositoryUri());
        } finally {
            materializer.release(second);
            materializer.release(first);
        }
    }

    @Test
    void corruptOrDirtyCacheEntryIsDiscardedAndRebuilt(@TempDir Path temp) throws Exception {
        Path source = createRepository(temp.resolve("source"));
        String commit = head(source);
        AtomicInteger clones = new AtomicInteger();
        JGitRemoteRepositoryMaterializer materializer = materializer(
                temp.resolve("home"), new RemoteRepositoryCachePolicy(2, 1024L * 1024L), source, clones,
                name -> Optional.empty());

        RemoteMaterialization first = materializer.materialize(request(commit, null));
        materializer.release(first);
        Files.writeString(first.repositoryRoot().resolve("untracked.txt"), "dirty");
        RemoteMaterialization rebuilt = materializer.materialize(request(commit, null));
        try {
            assertFalse(rebuilt.cacheHit());
            assertEquals(2, clones.get());
            assertFalse(Files.exists(rebuilt.repositoryRoot().resolve("untracked.txt")));
        } finally {
            materializer.release(rebuilt);
        }
    }

    @Test
    void evictsLeastRecentEntryAndRejectsUnexpectedCommitOrMissingSecret(@TempDir Path temp) throws Exception {
        Path source = createRepository(temp.resolve("source"));
        AtomicInteger clones = new AtomicInteger();
        JGitRemoteRepositoryMaterializer materializer = materializer(
                temp.resolve("home"), new RemoteRepositoryCachePolicy(1, 1024L * 1024L), source, clones,
                name -> Optional.empty());
        String firstCommit = head(source);
        RemoteMaterialization first = materializer.materialize(request(firstCommit, null));
        materializer.release(first);

        Files.writeString(source.resolve("fixtures/java/pom.xml"), "<project>second</project>");
        try (Git git = Git.open(source.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second").setAuthor(identity()).setCommitter(identity()).call();
        }
        String secondCommit = head(source);
        RemoteMaterialization second = materializer.materialize(request(secondCommit, null));
        try {
            assertFalse(Files.exists(first.repositoryRoot()));
            assertTrue(Files.isDirectory(second.repositoryRoot()));
        } finally {
            materializer.release(second);
        }
        assertThrows(Exception.class, () -> materializer.materialize(request("c".repeat(40), null)));
        RemoteRepositoryRequest privateRequest = RemoteRepositoryRequest.of(
                "https://github.com/acme/private", "main", secondCommit, "fixtures/java", "MINOS_REMOTE_TOKEN_MISSING");
        IllegalStateException missingSecret = assertThrows(
                IllegalStateException.class, () -> materializer.materialize(privateRequest));
        assertFalse(missingSecret.getMessage().contains("MINOS_REMOTE_TOKEN_MISSING"));
    }

    @Test
    void activeLeasePreventsEvictionUntilReleased(@TempDir Path temp) throws Exception {
        Path source = createRepository(temp.resolve("source"));
        AtomicInteger clones = new AtomicInteger();
        JGitRemoteRepositoryMaterializer materializer = materializer(
                temp.resolve("home"), new RemoteRepositoryCachePolicy(1, 1024L * 1024L), source, clones,
                name -> Optional.empty());
        RemoteMaterialization first = materializer.materialize(request(head(source), null));

        Files.writeString(source.resolve("fixtures/java/pom.xml"), "<project>next</project>");
        try (Git git = Git.open(source.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("next").setAuthor(identity()).setCommitter(identity()).call();
        }
        RemoteRepositoryRequest next = request(head(source), null);
        assertThrows(IOException.class, () -> materializer.materialize(next));
        assertTrue(Files.isDirectory(first.repositoryRoot()));

        materializer.release(first);
        RemoteMaterialization second = materializer.materialize(next);
        try {
            assertFalse(Files.exists(first.repositoryRoot()));
            assertTrue(Files.isDirectory(second.repositoryRoot()));
        } finally {
            materializer.release(second);
        }
    }

    @Test
    void registeredPinSurvivesLeaseReleaseAndMakesCachePressureFailClosed(@TempDir Path temp) throws Exception {
        Path source = createRepository(temp.resolve("source"));
        AtomicInteger clones = new AtomicInteger();
        JGitRemoteRepositoryMaterializer materializer = materializer(
                temp.resolve("home"), new RemoteRepositoryCachePolicy(1, 1024L * 1024L), source, clones,
                name -> Optional.empty());
        RemoteMaterialization registered = materializer.materialize(request(head(source), null));
        materializer.pin(registered);
        materializer.release(registered);
        assertTrue(Files.isRegularFile(registered.repositoryRoot().getParent().resolve("registered.pin")));

        Files.writeString(source.resolve("fixtures/java/pom.xml"), "<project>newer</project>");
        try (Git git = Git.open(source.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("newer").setAuthor(identity()).setCommitter(identity()).call();
        }

        assertThrows(IOException.class, () -> materializer.materialize(request(head(source), null)));
        assertTrue(Files.isDirectory(registered.repositoryRoot()));
        assertTrue(Files.isRegularFile(registered.projectRoot().resolve("pom.xml")));
    }

    @Test
    void cloneByteBudgetFailsDuringMaterializationAndCleansPartialEntry(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RemoteRepositoryCachePolicy policy = new RemoteRepositoryCachePolicy(2, 1024L * 1024L);
        JGitRemoteRepositoryMaterializer materializer = new JGitRemoteRepositoryMaterializer(
                home,
                policy,
                (request, destination, secret, budget) -> {
                    Files.createDirectories(destination);
                    byte[] block = new byte[300 * 1024];
                    for (int index = 0; index < 5; index++) {
                        Files.write(destination.resolve("block-" + index + ".bin"), block);
                        budget.checkpoint();
                    }
                },
                name -> Optional.empty(),
                Clock.systemUTC());

        assertThrows(IOException.class, () -> materializer.materialize(request("a".repeat(40), null)));
        assertNoCacheDirectories(home);
    }

    @Test
    void cloneFileBudgetFailsAndCleansPartialEntry(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RemoteRepositoryCachePolicy policy = new RemoteRepositoryCachePolicy(
                2, 1024L * 1024L, 3, 10, 20, Duration.ofMinutes(1));
        JGitRemoteRepositoryMaterializer materializer = new JGitRemoteRepositoryMaterializer(
                home,
                policy,
                (request, destination, secret, budget) -> {
                    Files.createDirectories(destination);
                    for (int index = 0; index < 4; index++) {
                        Files.writeString(destination.resolve("file-" + index + ".txt"), "x");
                    }
                    budget.checkpoint();
                },
                name -> Optional.empty(),
                Clock.systemUTC());

        IOException failure = assertThrows(
                IOException.class,
                () -> materializer.materialize(request("a".repeat(40), null)));

        assertTrue(failure.getMessage().contains("file limit"));
        assertNoCacheDirectories(home);
    }

    @Test
    void cloneTraversalBudgetFailsBeforeADeepEntryTreeCanBeAccepted(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        RemoteRepositoryCachePolicy policy = new RemoteRepositoryCachePolicy(
                2, 1024L * 1024L, 1, 1, 1, Duration.ofMinutes(1));
        JGitRemoteRepositoryMaterializer materializer = new JGitRemoteRepositoryMaterializer(
                home,
                policy,
                (request, destination, secret, budget) -> {
                    Files.createDirectories(destination);
                    Files.writeString(destination.resolve("one.txt"), "x");
                    budget.checkpoint();
                },
                name -> Optional.empty(),
                Clock.systemUTC());

        IOException failure = assertThrows(
                IOException.class,
                () -> materializer.materialize(request("a".repeat(40), null)));

        assertTrue(failure.getMessage().contains("traversal entry limit"));
        assertNoCacheDirectories(home);
    }

    /** MINOS-AUD-A02 : un temporaire de clonage d'un processus mort est borné dans le temps ; une entrée valide jamais touchée. */
    @Test
    void anExpiredCloneTemporaryIsReclaimedWhereAValidEntryAndARecentTemporaryAreKept(@TempDir Path temp) throws Exception {
        Path source = createRepository(temp.resolve("source"));
        AtomicInteger clones = new AtomicInteger();
        Path home = temp.resolve("home");
        JGitRemoteRepositoryMaterializer materializer = materializer(
                home, new RemoteRepositoryCachePolicy(4, 1024L * 1024L), source, clones, name -> Optional.empty());
        RemoteMaterialization validEntry = materializer.materialize(request(head(source), null));
        materializer.release(validEntry);

        Path repositories = home.resolve("remote-cache/repositories");
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        Path expired = plant(repositories, ".entry-" + UUID.randomUUID() + ".tmp", now.minus(Duration.ofHours(48)));
        Path recent = plant(repositories, ".entry-" + UUID.randomUUID() + ".tmp", now.minus(Duration.ofMinutes(5)));
        Files.writeString(source.resolve("fixtures/java/pom.xml"), "<project>second</project>");
        try (Git git = Git.open(source.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second").setAuthor(identity()).setCommitter(identity()).call();
        }

        RemoteMaterialization second = materializer.materialize(request(head(source), null));
        try {
            assertFalse(Files.exists(expired), "a clone temporary older than the residue lifetime must be reclaimed");
            assertTrue(Files.exists(recent), "a recent clone temporary may belong to a live clone");
            assertTrue(Files.isDirectory(validEntry.repositoryRoot()), "a valid cache entry is never a scratch residue");
            assertTrue(Files.isDirectory(second.repositoryRoot()));
            assertEquals(2, clones.get());
        } finally {
            materializer.release(second);
        }
    }

    private static Path plant(Path root, String name, Instant modified) throws IOException {
        Path directory = Files.createDirectories(root.resolve(name).resolve("repository"));
        Files.writeString(directory.resolve("f.txt"), "residue");
        java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime.from(modified);
        Files.setLastModifiedTime(directory.resolve("f.txt"), time);
        Files.setLastModifiedTime(directory, time);
        Files.setLastModifiedTime(directory.getParent(), time);
        return directory.getParent();
    }

    private static void assertNoCacheDirectories(Path home) throws IOException {
        Path repositories = home.resolve("remote-cache/repositories");
        try (var entries = Files.list(repositories)) {
            assertTrue(entries.noneMatch(Files::isDirectory));
        }
    }

    private static JGitRemoteRepositoryMaterializer materializer(
            Path home,
            RemoteRepositoryCachePolicy policy,
            Path source,
            AtomicInteger clones,
            JGitRemoteRepositoryMaterializer.SecretResolver secrets
    ) throws Exception {
        return new JGitRemoteRepositoryMaterializer(
                home,
                policy,
                (request, destination, secret, budget) -> {
                    clones.incrementAndGet();
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
                secrets,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));
    }

    private static Path createRepository(Path root) throws Exception {
        Files.createDirectories(root.resolve("fixtures/java"));
        Files.writeString(root.resolve("fixtures/java/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor(identity()).setCommitter(identity()).call();
        }
        return root;
    }

    private static PersonIdent identity() {
        return new PersonIdent(
                "MINOS Test", "minos@example.invalid",
                java.util.Date.from(Instant.parse("2026-07-29T00:00:00Z")),
                java.util.TimeZone.getTimeZone("UTC"));
    }

    private static String head(Path repository) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    private static RemoteRepositoryRequest request(String commit, String credential) {
        return RemoteRepositoryRequest.of(
                "https://github.com/acme/demo", "main", commit, "fixtures/java", credential);
    }
}

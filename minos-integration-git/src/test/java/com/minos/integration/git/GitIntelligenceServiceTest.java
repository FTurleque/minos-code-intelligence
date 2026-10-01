package com.minos.integration.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitIntelligenceServiceTest {

    @Test
    void reportsBoundedRepositoryFileAndZoneActivity(@TempDir Path temp) throws Exception {
        Path repositoryRoot = temp.resolve("repo");
        Files.createDirectories(repositoryRoot.resolve("src"));

        try (Git git = Git.init().setDirectory(repositoryRoot.toFile()).call()) {
            Files.writeString(repositoryRoot.resolve("src/App.java"), "class App {}\n");
            Files.writeString(repositoryRoot.resolve("README.md"), "initial\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Alice", "alice@example.com")
                    .setCommitter("Alice", "alice@example.com")
                    .call();

            Files.writeString(repositoryRoot.resolve("src/App.java"), "class App { int version = 2; }\n");
            git.add().addFilepattern("src/App.java").call();
            git.commit()
                    .setMessage("update app")
                    .setAuthor("Bob", "bob@example.com")
                    .setCommitter("Bob", "bob@example.com")
                    .call();
        }

        GitIntelligenceService service = new GitIntelligenceService();
        GitIntelligenceService.RepositoryView repository = service.inspect(repositoryRoot);
        assertNotNull(repository.repositoryId());
        assertNotNull(repository.headCommit());
        assertTrue(repository.clean());
        assertFalse(repository.shallow());
        assertTrue(repository.limitations().contains("NO_ORIGIN_REMOTE"));

        GitIntelligenceService.ActivityReport report = service.analyze(
                repositoryRoot,
                new GitIntelligenceService.ActivityQuery(Instant.EPOCH, 100, 100, 1)
        );

        assertEquals(2, report.scannedCommitCount());
        assertFalse(report.historyTruncated());
        assertFalse(report.filesTruncated());
        assertEquals(2, report.recentCommits().size());

        GitIntelligenceService.FileActivity app = report.files().stream()
                .filter(file -> file.path().equals("src/App.java"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, app.commitCount());
        assertEquals(2, app.uniqueAuthorCount());

        GitIntelligenceService.FileActivity readme = report.files().stream()
                .filter(file -> file.path().equals("README.md"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, readme.commitCount());

        GitIntelligenceService.ZoneActivity src = report.zones().stream()
                .filter(zone -> zone.zone().equals("src"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, src.commitTouches());
        assertEquals(1, src.distinctFileCount());

        try (Git git = Git.open(repositoryRoot.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString(
                    "remote",
                    "origin",
                    "url",
                    "https://user:secret@example.com/org/repo.git?token=should-not-leak"
            );
            config.save();
        }
        GitIntelligenceService.RepositoryView sanitized = service.inspect(repositoryRoot);
        assertEquals("https://example.com/org/repo", sanitized.originRemote());
        assertFalse(sanitized.originRemote().contains("secret"));
        assertFalse(sanitized.originRemote().contains("token"));
    }

    @Test
    void rejectsNonGitDirectories(@TempDir Path temp) {
        GitIntelligenceService service = new GitIntelligenceService();
        var exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.inspect(temp)
        );
        assertTrue(exception.getMessage().contains("not inside a Git repository"));
    }

    @Test
    void anOldCommitterDateDoesNotHideMoreRecentAncestors(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("repo");
        Files.createDirectories(root);
        Instant recent = Instant.parse("2026-06-01T00:00:00Z");
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            commit(git, root, "first.txt", "recent-oldest", recent);
            commit(git, root, "second.txt", "skewed-clock", Instant.parse("2001-01-01T00:00:00Z"));
            commit(git, root, "third.txt", "recent-newest", recent.plusSeconds(60));
        }

        GitIntelligenceService.ActivityReport report = new GitIntelligenceService().analyze(
                root, new GitIntelligenceService.ActivityQuery(Instant.parse("2026-01-01T00:00:00Z"), 100, 100, 1));

        // Git lists newest-first: third, second (dated 2001), first. Stopping at the old date lost "first".
        assertEquals(2, report.scannedCommitCount());
        assertTrue(report.files().stream().anyMatch(file -> file.path().equals("first.txt")));
        assertTrue(report.files().stream().noneMatch(file -> file.path().equals("second.txt")));
    }

    @Test
    void theGitEnvironmentCannotRedirectTheAnalysedRepository(@TempDir Path temp) throws Exception {
        Path requested = temp.resolve("requested");
        Path other = temp.resolve("other");
        Files.createDirectories(requested);
        Files.createDirectories(other);
        try (Git git = Git.init().setDirectory(requested.toFile()).call()) {
            commit(git, requested, "requested.txt", "requested", Instant.parse("2026-06-01T00:00:00Z"));
        }
        try (Git git = Git.init().setDirectory(other.toFile()).call()) {
            commit(git, other, "other.txt", "other", Instant.parse("2026-06-02T00:00:00Z"));
        }

        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                GitEnvironmentProbe.class.getName(), requested.toString());
        builder.environment().put("GIT_DIR", other.resolve(".git").toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains("requested.txt") && !output.contains("other.txt"), output);
    }

    /** Runs in a child JVM so that GIT_DIR is really present in the process environment. */
    public static final class GitEnvironmentProbe {
        public static void main(String[] arguments) throws Exception {
            var report = new GitIntelligenceService().analyze(
                    Path.of(arguments[0]),
                    new GitIntelligenceService.ActivityQuery(Instant.EPOCH, 100, 100, 1));
            report.files().forEach(file -> System.out.println(file.path()));
        }
    }

    private static void commit(Git git, Path root, String file, String message, Instant when) throws Exception {
        Files.writeString(root.resolve(file), message + "\n");
        git.add().addFilepattern(file).call();
        var ident = new org.eclipse.jgit.lib.PersonIdent("Dev", "dev@example.com", when, java.time.ZoneOffset.UTC);
        git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call();
    }
}

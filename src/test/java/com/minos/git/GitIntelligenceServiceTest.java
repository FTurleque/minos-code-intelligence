package com.minos.git;

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
}

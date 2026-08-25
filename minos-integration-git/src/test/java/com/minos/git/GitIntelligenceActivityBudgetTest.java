package com.minos.git;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analysis frontier must bound the <em>work</em>, not only the answer.
 *
 * <p>{@code maxFiles} used to be the only limit, and it was applied after every changed path of
 * every scanned commit had already been turned into diff entries, path strings, file entries, zone
 * entries and a retained {@code changedPaths} list. A single commit touching a vendored tree could
 * therefore cost orders of magnitude more memory than the caller asked for. Each test below drives
 * a commit that is far larger than the budget and asserts on what was actually accumulated.</p>
 */
class GitIntelligenceActivityBudgetTest {

    private static final int HUGE_COMMIT_FILES = 60;

    @Test
    void oneCommitLargerThanTheDiffBudgetIsBoundedDuringTheScanNotAfterIt(@TempDir Path temp)
            throws Exception {
        Path root = repositoryWithOneHugeCommit(temp, HUGE_COMMIT_FILES);
        GitIntelligenceService service = new GitIntelligenceService(
                new GitIntelligenceService.ActivityBudget(5, 10_000, 10_000, 10_000));

        GitIntelligenceService.ActivityReport report = service.analyze(root, query(10_000));

        GitIntelligenceService.CommitActivity commit = report.recentCommits().getFirst();
        assertEquals(5, commit.changedPaths().size(),
                "the per-commit diff budget must bound what the commit costs, whatever maxFiles says");
        assertTrue(report.limitations().contains("DIFF_SCAN_TRUNCATED"), report.limitations().toString());
        assertTrue(report.files().size() <= 5,
                "file activity can only be derived from paths that were actually analysed");
    }

    @Test
    void aTruncatedScanStaysDeterministicAcrossRuns(@TempDir Path temp) throws Exception {
        Path root = repositoryWithOneHugeCommit(temp, HUGE_COMMIT_FILES);
        GitIntelligenceService service = new GitIntelligenceService(
                new GitIntelligenceService.ActivityBudget(7, 10_000, 10_000, 10_000));

        List<String> first = service.analyze(root, query(10_000)).recentCommits().getFirst().changedPaths();
        List<String> second = service.analyze(root, query(10_000)).recentCommits().getFirst().changedPaths();

        assertEquals(first, second, "a bounded scan must not depend on iteration order or timing");
        assertEquals(first.stream().sorted().toList(), first, "paths stay sorted after truncation");
    }

    @Test
    void trackedFilesAndZonesStopGrowingOnceTheirBudgetIsReached(@TempDir Path temp) throws Exception {
        Path root = repositoryWithOneHugeCommit(temp, HUGE_COMMIT_FILES);
        GitIntelligenceService service = new GitIntelligenceService(
                new GitIntelligenceService.ActivityBudget(10_000, 4, 2, 10_000));

        GitIntelligenceService.ActivityReport report = service.analyze(root, query(10_000));

        assertTrue(report.files().size() <= 4, "tracked file map must be bounded: " + report.files().size());
        assertTrue(report.zones().size() <= 2, "tracked zone map must be bounded: " + report.zones().size());
        assertTrue(report.limitations().contains("FILE_TRACKING_TRUNCATED"), report.limitations().toString());
        assertTrue(report.limitations().contains("ZONE_TRACKING_TRUNCATED"), report.limitations().toString());
    }

    @Test
    void retainedChangedPathsAreBoundedIndependentlyOfTheRequestedMaxFiles(@TempDir Path temp)
            throws Exception {
        Path root = repositoryWithOneHugeCommit(temp, HUGE_COMMIT_FILES);
        GitIntelligenceService service = new GitIntelligenceService(
                new GitIntelligenceService.ActivityBudget(10_000, 10_000, 10_000, 9));

        GitIntelligenceService.ActivityReport report = service.analyze(root, query(10_000));

        assertEquals(9, report.recentCommits().getFirst().changedPaths().size());
        assertTrue(report.limitations().contains("PATHS_TRUNCATED"), report.limitations().toString());
        assertEquals(HUGE_COMMIT_FILES, report.files().size(),
                "retaining fewer paths must not silently drop the activity facts already computed");
    }

    @Test
    void aNormalRepositoryIsUnaffectedByTheDefaultBudget(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("small");
        Files.createDirectories(root.resolve("src"));
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Files.writeString(root.resolve("src/App.java"), "class App {}\n");
            Files.writeString(root.resolve("README.md"), "hello\n");
            commit(git, "initial");
            Files.writeString(root.resolve("src/App.java"), "class App { int v = 2; }\n");
            commit(git, "update");
        }

        GitIntelligenceService service = new GitIntelligenceService();
        GitIntelligenceService.ActivityReport report = service.analyze(root, query(100));

        assertEquals(2, report.scannedCommitCount());
        assertEquals(List.of("README.md", "src/App.java"), report.recentCommits().getLast().changedPaths());
        assertEquals(List.of("src/App.java"), report.recentCommits().getFirst().changedPaths());
        assertFalse(report.limitations().contains("DIFF_SCAN_TRUNCATED"), report.limitations().toString());
        assertFalse(report.limitations().contains("PATHS_TRUNCATED"), report.limitations().toString());
        assertFalse(report.limitations().contains("FILE_TRACKING_TRUNCATED"), report.limitations().toString());
        assertFalse(report.limitations().contains("ZONE_TRACKING_TRUNCATED"), report.limitations().toString());
    }

    @Test
    void theDefaultServiceUsesTheCentralizedBudget() {
        assertEquals(GitIntelligenceService.ActivityBudget.DEFAULT, new GitIntelligenceService().budget());
        assertThrows(IllegalArgumentException.class,
                () -> new GitIntelligenceService.ActivityBudget(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new GitIntelligenceService.ActivityBudget(1, 1, 1, 0));
    }

    private static GitIntelligenceService.ActivityQuery query(int maxFiles) {
        return new GitIntelligenceService.ActivityQuery(Instant.EPOCH, 100, maxFiles, 2);
    }

    /** One commit whose diff is far wider than any budget under test, spread over several zones. */
    private static Path repositoryWithOneHugeCommit(Path temp, int files) throws Exception {
        Path root = temp.resolve("huge");
        Files.createDirectories(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            for (int index = 0; index < files; index++) {
                Path file = root.resolve("zone-" + (index % 6)).resolve("file-" + index + ".txt");
                Files.createDirectories(file.getParent());
                Files.writeString(file, "content " + index + "\n");
            }
            commit(git, "vendored import");
        }
        return root;
    }

    private static void commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit()
                .setMessage(message)
                .setAuthor("Alice", "alice@example.com")
                .setCommitter("Alice", "alice@example.com")
                .call();
    }
}

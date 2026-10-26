package com.minos.application;

import com.minos.git.GitIntelligence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-01, lot 4: the surfaces read the Git facts of a project through the application query, which renders the
 * port's answers unchanged, refuses what the port refuses and propagates its failures as they are.
 */
class LocalProjectGitQueryTest {

    private static final Instant SINCE = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant CHANGED = Instant.parse("2026-09-02T08:00:00Z");
    private static final Path ROOT = Path.of("work", "demo");

    private static final GitIntelligence.RepositoryView REPOSITORY = new GitIntelligence.RepositoryView(
            "repo-1", "work/demo", "https://example.invalid/demo.git", "main", "abc123", true, true, false,
            List.of("SHALLOW_CLONE"));

    @Test
    void theInspectionIsRenderedFieldByField() throws Exception {
        FakePort port = new FakePort();
        port.repository = REPOSITORY;

        ProjectGitQuery.RepositoryView view = new LocalProjectGitQuery(port).inspect(ROOT);

        assertEquals(List.of(ROOT), port.roots);
        assertEquals(new ProjectGitQuery.RepositoryView("repo-1", "work/demo", "https://example.invalid/demo.git",
                "main", "abc123", true, true, false, List.of("SHALLOW_CLONE")), view);
    }

    @Test
    void theActivityIsRenderedFieldByFieldWithTheRequestThePortReceived() throws Exception {
        FakePort port = new FakePort();
        port.report = new GitIntelligence.ActivityReport(
                REPOSITORY,
                new GitIntelligence.ActivityQuery(SINCE, 10, 20, 3, true),
                7, true, false,
                List.of(new GitIntelligence.CommitActivity("abc123", CHANGED, "Ada", "ada@example.invalid",
                        "change greeting", List.of("src/A.java"))),
                List.of(new GitIntelligence.FileActivity("src/A.java", 2, 1, CHANGED, "abc123")),
                List.of(new GitIntelligence.ZoneActivity("src", 2, 1, CHANGED)),
                List.of("HISTORY_TRUNCATED"));

        ProjectGitQuery.ActivityReport report = new LocalProjectGitQuery(port)
                .analyze(ROOT, new ProjectGitQuery.ActivityRequest(SINCE, 10, 20, 3, true));

        assertEquals(List.of(new GitIntelligence.ActivityQuery(SINCE, 10, 20, 3, true)), port.queries);
        assertEquals(new ProjectGitQuery.ActivityReport(
                new ProjectGitQuery.RepositoryView("repo-1", "work/demo", "https://example.invalid/demo.git", "main",
                        "abc123", true, true, false, List.of("SHALLOW_CLONE")),
                new ProjectGitQuery.ActivityRequest(SINCE, 10, 20, 3, true),
                7, true, false,
                List.of(new ProjectGitQuery.CommitActivity("abc123", CHANGED, "Ada", "ada@example.invalid",
                        "change greeting", List.of("src/A.java"))),
                List.of(new ProjectGitQuery.FileActivity("src/A.java", 2, 1, CHANGED, "abc123")),
                List.of(new ProjectGitQuery.ZoneActivity("src", 2, 1, CHANGED)),
                List.of("HISTORY_TRUNCATED")), report);
    }

    @Test
    void absentListsAreRenderedEmpty() {
        assertEquals(List.of(), new ProjectGitQuery.RepositoryView(null, null, null, null, null, false, false, true,
                null).limitations());
        assertEquals(List.of(), new ProjectGitQuery.CommitActivity("c", CHANGED, "a", "e", "m", null).changedPaths());
        ProjectGitQuery.ActivityReport report = new ProjectGitQuery.ActivityReport(null,
                new ProjectGitQuery.ActivityRequest(SINCE, 1, 1, 1, false), 0, false, false, null, null, null, null);
        assertEquals(List.of(), report.recentCommits());
        assertEquals(List.of(), report.files());
        assertEquals(List.of(), report.zones());
        assertEquals(List.of(), report.limitations());
    }

    @Test
    void theRequestRefusesWhatThePortRefusesWithTheSameMessage() {
        assertEquals(refusal(() -> new GitIntelligence.ActivityQuery(SINCE, 0, 1, 1)),
                refusal(() -> new ProjectGitQuery.ActivityRequest(SINCE, 0, 1, 1, false)));
        assertEquals(refusal(() -> new GitIntelligence.ActivityQuery(SINCE, 1, 10_001, 1)),
                refusal(() -> new ProjectGitQuery.ActivityRequest(SINCE, 1, 10_001, 1, false)));
        assertEquals(refusal(() -> new GitIntelligence.ActivityQuery(SINCE, 1, 1, 9)),
                refusal(() -> new ProjectGitQuery.ActivityRequest(SINCE, 1, 1, 9, false)));
        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new ProjectGitQuery.ActivityRequest(null, 1, 1, 1, false));
        assertEquals("since", missing.getMessage());
        assertEquals(new ProjectGitQuery.ActivityRequest(SINCE, 10_000, 10_000, 8, true),
                new ProjectGitQuery.ActivityRequest(SINCE, 10_000, 10_000, 8, true));
    }

    @Test
    void aFailureOfThePortIsPropagatedWithoutWrapper() {
        IOException unreadable = new IOException("repository unreadable");
        IllegalStateException wrapped = new IllegalStateException(unreadable);
        FakePort port = new FakePort();
        port.failure = unreadable;
        LocalProjectGitQuery query = new LocalProjectGitQuery(port);

        assertSame(unreadable, assertThrows(IOException.class, () -> query.inspect(ROOT)));
        assertSame(unreadable, assertThrows(IOException.class,
                () -> query.analyze(ROOT, new ProjectGitQuery.ActivityRequest(SINCE, 1, 1, 1, false))));
        port.failure = wrapped;
        assertSame(wrapped, assertThrows(IllegalStateException.class, () -> query.inspect(ROOT)));
    }

    @Test
    void aMissingCollaboratorIsRefused() {
        assertEquals("git", assertThrows(NullPointerException.class,
                () -> new LocalProjectGitQuery((GitIntelligence) null)).getMessage());
        assertEquals("application", assertThrows(NullPointerException.class,
                () -> new LocalProjectGitQuery((MinosApplication) null)).getMessage());
        LocalProjectGitQuery query = new LocalProjectGitQuery(new FakePort());
        assertThrows(NullPointerException.class, () -> query.analyze(ROOT, null));
    }

    @Test
    void theApplicationRecordsCarryEveryComponentOfThePortRecords() {
        // A component added to a port record must be carried by the application query, or this test fails.
        assertSameComponents(GitIntelligence.RepositoryView.class, ProjectGitQuery.RepositoryView.class);
        assertSameComponents(GitIntelligence.CommitActivity.class, ProjectGitQuery.CommitActivity.class);
        assertSameComponents(GitIntelligence.FileActivity.class, ProjectGitQuery.FileActivity.class);
        assertSameComponents(GitIntelligence.ZoneActivity.class, ProjectGitQuery.ZoneActivity.class);
        assertSameComponents(GitIntelligence.ActivityQuery.class, ProjectGitQuery.ActivityRequest.class);
        List<String> report = names(ProjectGitQuery.ActivityReport.class);
        List<String> port = new ArrayList<>(names(GitIntelligence.ActivityReport.class));
        port.set(port.indexOf("query"), "request");
        assertEquals(port, report);
    }

    private static void assertSameComponents(Class<?> port, Class<?> application) {
        assertTrue(port.isRecord() && application.isRecord());
        assertEquals(names(port), names(application), application.getSimpleName());
    }

    private static List<String> names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static String refusal(Runnable construction) {
        return assertThrows(IllegalArgumentException.class, construction::run).getMessage();
    }

    private static final class FakePort implements GitIntelligence {
        private final List<Path> roots = new ArrayList<>();
        private final List<ActivityQuery> queries = new ArrayList<>();
        private RepositoryView repository;
        private ActivityReport report;
        private Exception failure;

        @Override
        public RepositoryView inspect(Path projectRoot) throws Exception {
            roots.add(projectRoot);
            if (failure != null) throw failure;
            return repository;
        }

        @Override
        public ActivityReport analyze(Path projectRoot, ActivityQuery query) throws Exception {
            roots.add(projectRoot);
            queries.add(query);
            if (failure != null) throw failure;
            return report;
        }
    }
}

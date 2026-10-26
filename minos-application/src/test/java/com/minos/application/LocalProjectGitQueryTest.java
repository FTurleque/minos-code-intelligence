package com.minos.application;

import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.RepositoryView;
import com.minos.git.GitIntelligence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AUD-ARC-01, lot 4: the surfaces read the Git facts of a project through the application query, which hands over the
 * port's answers and failures as they are.
 */
class LocalProjectGitQueryTest {

    private static final Path ROOT = Path.of("work", "demo");
    private static final ActivityQuery QUERY = new ActivityQuery(Instant.parse("2026-09-01T00:00:00Z"), 10, 20, 3, true);
    private static final RepositoryView REPOSITORY = new RepositoryView(
            "repo-1", "work/demo", null, "main", "abc123", false, false, true, List.of("SHALLOW_CLONE"));

    @Test
    void theInspectionAndTheActivityAreThoseOfThePort() throws Exception {
        FakePort port = new FakePort();
        port.report = new ActivityReport(REPOSITORY, QUERY, 7, true, false, List.of(), List.of(), List.of(), List.of());
        LocalProjectGitQuery query = new LocalProjectGitQuery(port);

        assertSame(REPOSITORY, query.inspect(ROOT));
        assertSame(port.report, query.analyze(ROOT, QUERY));
        assertEquals(List.of(ROOT, ROOT), port.roots);
        assertEquals(List.of(QUERY), port.queries);
    }

    @Test
    void aFailureOfThePortIsPropagatedWithoutWrapper() {
        IOException unreadable = new IOException("repository unreadable");
        IllegalStateException wrapped = new IllegalStateException(unreadable);
        FakePort port = new FakePort();
        port.failure = unreadable;
        LocalProjectGitQuery query = new LocalProjectGitQuery(port);

        assertSame(unreadable, assertThrows(IOException.class, () -> query.inspect(ROOT)));
        assertSame(unreadable, assertThrows(IOException.class, () -> query.analyze(ROOT, QUERY)));
        port.failure = wrapped;
        assertSame(wrapped, assertThrows(IllegalStateException.class, () -> query.inspect(ROOT)));
    }

    @Test
    void aMissingCollaboratorOrQueryIsRefused() {
        assertEquals("git", assertThrows(NullPointerException.class,
                () -> new LocalProjectGitQuery((GitIntelligence) null)).getMessage());
        assertEquals("application", assertThrows(NullPointerException.class,
                () -> new LocalProjectGitQuery((MinosApplication) null)).getMessage());
        FakePort port = new FakePort();
        assertEquals("query", assertThrows(NullPointerException.class,
                () -> new LocalProjectGitQuery(port).analyze(ROOT, null)).getMessage());
        assertEquals(List.of(), port.queries, "a missing query never reaches the port");
    }

    private static final class FakePort implements GitIntelligence {
        private final List<Path> roots = new ArrayList<>();
        private final List<ActivityQuery> queries = new ArrayList<>();
        private ActivityReport report;
        private Exception failure;

        @Override
        public RepositoryView inspect(Path projectRoot) throws Exception {
            roots.add(projectRoot);
            if (failure != null) throw failure;
            return REPOSITORY;
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

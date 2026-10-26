package com.minos.domain.git;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The factual Git records shared by the engine port and the application query: bounds and immutable lists. */
class GitActivityFactsTest {

    private static final Instant SINCE = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void theQueryAcceptsItsBoundsAndMasksAuthorEmailsByDefault() {
        ActivityQuery lowest = new ActivityQuery(SINCE, 1, 1, 1);
        ActivityQuery highest = new ActivityQuery(SINCE, 10_000, 10_000, 8, true);

        assertFalse(lowest.includeAuthorEmails());
        assertTrue(highest.includeAuthorEmails());
        assertEquals(10_000, highest.maxCommits());
        assertEquals(8, highest.zoneDepth());
    }

    @Test
    void theQueryRefusesEachBoundWithANamedMessage() {
        assertEquals("maxCommits must be between 1 and 10000", refusal(() -> new ActivityQuery(SINCE, 0, 1, 1)));
        assertEquals("maxCommits must be between 1 and 10000", refusal(() -> new ActivityQuery(SINCE, 10_001, 1, 1)));
        assertEquals("maxFiles must be between 1 and 10000", refusal(() -> new ActivityQuery(SINCE, 1, 0, 1)));
        assertEquals("maxFiles must be between 1 and 10000", refusal(() -> new ActivityQuery(SINCE, 1, 10_001, 1)));
        assertEquals("zoneDepth must be between 1 and 8", refusal(() -> new ActivityQuery(SINCE, 1, 1, 0)));
        assertEquals("zoneDepth must be between 1 and 8", refusal(() -> new ActivityQuery(SINCE, 1, 1, 9)));
        assertEquals("since", assertThrows(NullPointerException.class,
                () -> new ActivityQuery(null, 1, 1, 1)).getMessage());
    }

    @Test
    void absentListsAreEmptyAndGivenListsAreCopied() {
        List<String> limitations = new ArrayList<>(List.of("SHALLOW_CLONE"));
        RepositoryView repository = new RepositoryView("r", "w", null, "main", "abc", false, true, true, limitations);
        limitations.add("LATER");
        assertEquals(List.of("SHALLOW_CLONE"), repository.limitations());
        assertEquals(List.of(), new RepositoryView(null, null, null, null, null, false, false, true, null).limitations());

        Instant changed = SINCE.plusSeconds(60);
        assertEquals(List.of(), new CommitActivity("c", changed, "a", "e", "m", null).changedPaths());
        assertEquals(List.of("src/A.java"),
                new CommitActivity("c", changed, "a", "e", "m", List.of("src/A.java")).changedPaths());

        ActivityReport empty = new ActivityReport(repository, new ActivityQuery(SINCE, 1, 1, 1), 0, false, false,
                null, null, null, null);
        assertEquals(List.of(), empty.recentCommits());
        assertEquals(List.of(), empty.files());
        assertEquals(List.of(), empty.zones());
        assertEquals(List.of(), empty.limitations());

        ActivityReport full = new ActivityReport(repository, new ActivityQuery(SINCE, 1, 1, 1), 1, true, true,
                List.of(new CommitActivity("c", changed, "a", "e", "m", List.of())),
                List.of(new FileActivity("src/A.java", 1, 1, changed, "c")),
                List.of(new ZoneActivity("src", 1, 1, changed)),
                List.of("HISTORY_TRUNCATED"));
        assertEquals("src/A.java", full.files().get(0).path());
        assertEquals("src", full.zones().get(0).zone());
        assertThrows(UnsupportedOperationException.class, () -> full.recentCommits().clear());
    }

    private static String refusal(Runnable construction) {
        return assertThrows(IllegalArgumentException.class, construction::run).getMessage();
    }
}

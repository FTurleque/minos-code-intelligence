package com.minos.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.integration.git.GitIntelligenceService;
import com.minos.output.SymbolOutputFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitActivityCommandTest {

    @Test
    void jsonKeepsActivityFactualAndDoesNotInferImportance() {
        Instant now = Instant.parse("2026-07-27T08:00:00Z");
        GitIntelligenceService.RepositoryView repository = new GitIntelligenceService.RepositoryView(
                "repo-1", "C:/work/project", "https://github.com/example/project", "main", "abc123",
                false, false, true, List.of());
        GitIntelligenceService.ActivityReport report = new GitIntelligenceService.ActivityReport(
                repository,
                new GitIntelligenceService.ActivityQuery(now.minusSeconds(86400), 100, 100, 2),
                1,
                false,
                false,
                List.of(new GitIntelligenceService.CommitActivity(
                        "abc123", now, "Ada", "ada@example.test", "change", List.of("src/A.java"))),
                List.of(new GitIntelligenceService.FileActivity("src/A.java", 1, 1, now, "abc123")),
                List.of(new GitIntelligenceService.ZoneActivity("src", 1, 1, now)),
                List.of()
        );

        String json = GitActivityCommand.render(report, SymbolOutputFormat.JSON);

        assertTrue(json.contains("\"nature\":\"FACTUAL_ACTIVITY\""));
        assertTrue(json.contains("\"importanceInference\":false"));
        assertTrue(json.contains("\"zone\":\"src\""));
        assertTrue(json.contains("\"commitCount\":1"));
    }

    /**
     * Q6: the query, file and zone objects were built with {@code Map.of}, whose iteration order is
     * drawn at random when the JVM starts; their keys must come out in the order written by the command.
     */
    @Test
    void jsonKeysComeOutInTheirDeclaredOrderOnEveryLaunch() throws Exception {
        Instant now = Instant.parse("2026-07-27T08:00:00Z");
        GitIntelligenceService.ActivityReport report = new GitIntelligenceService.ActivityReport(
                new GitIntelligenceService.RepositoryView(
                        "repo-1", "C:/work/project", "https://github.com/example/project", "main", "abc123",
                        false, false, true, List.of()),
                new GitIntelligenceService.ActivityQuery(now.minusSeconds(86400), 100, 100, 2),
                1, false, false,
                List.of(new GitIntelligenceService.CommitActivity(
                        "abc123", now, "Ada", "ada@example.test", "change", List.of("src/A.java"))),
                List.of(new GitIntelligenceService.FileActivity("src/A.java", 1, 1, now, "abc123")),
                List.of(new GitIntelligenceService.ZoneActivity("src", 1, 1, now)),
                List.of());

        JsonNode json = new ObjectMapper().readTree(GitActivityCommand.render(report, SymbolOutputFormat.JSON));

        assertEquals(List.of("since", "maxCommits", "maxFiles", "zoneDepth"), keys(json.get("query")));
        assertEquals(List.of("path", "commitCount", "uniqueAuthorCount", "lastChangedAt", "lastCommitId"),
                keys(json.get("files").get(0)));
        assertEquals(List.of("zone", "commitTouches", "distinctFileCount", "lastChangedAt"),
                keys(json.get("zones").get(0)));
        assertEquals(List.of("nature", "importanceInference", "repository", "query", "scannedCommitCount",
                "historyTruncated", "filesTruncated", "recentCommits", "files", "zones", "limitations"), keys(json));
        assertEquals("src/A.java", json.get("files").get(0).get("path").textValue());
        assertEquals(2, json.get("query").get("zoneDepth").intValue());
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

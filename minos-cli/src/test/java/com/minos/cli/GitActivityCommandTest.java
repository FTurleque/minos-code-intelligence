package com.minos.cli;

import com.minos.git.GitIntelligenceService;
import com.minos.output.SymbolOutputFormat;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
}

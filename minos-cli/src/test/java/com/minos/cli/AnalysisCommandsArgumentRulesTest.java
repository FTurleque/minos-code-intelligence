package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.minos.cli.CliArgumentRules.command;

/** Q11 : architecture, impact et git-activity obéissent aux règles uniformes de {@link CliOptions}. */
class AnalysisCommandsArgumentRulesTest {

    @TempDir Path home;

    @Test
    void architecture() throws Exception {
        new CliArgumentRules(home).check(command("architecture", "architecture p")
                .text("--module", "core").choice("--format", "json"));
    }

    @Test
    void impact() throws Exception {
        new CliArgumentRules(home).check(command("impact", "impact p S")
                .integer("--depth", 1, 32, 2).integer("--limit", 1, 10_000, 5).choice("--format", "json"));
    }

    @Test
    void gitActivity() throws Exception {
        new CliArgumentRules(home).check(command("git-activity", "git-activity p")
                .integer("--days", 1, 3650, 5).integer("--max-commits", 1, 10_000, 5)
                .integer("--max-files", 1, 10_000, 5).integer("--zone-depth", 1, 8, 2)
                .choice("--format", "json"));
    }
}

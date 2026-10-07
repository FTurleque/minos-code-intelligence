package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.minos.cli.CliArgumentRules.command;

/**
 * Q11 : index, tools, doctor, remote et runtime obéissent aux règles uniformes de {@link CliOptions}.
 * Les formes acceptées de {@code remote} et de {@code tools install} ne sont pas rejouées ici (réseau,
 * installation) : {@link CliValidInvocationsTest} les couvre avec des services enregistreurs.
 */
class ExecutionCommandsArgumentRulesTest {

    private static final String COMMIT = "0123456789012345678901234567890123456789";

    @TempDir Path home;

    @Test
    void index() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("index", "index p").text("--provider", "scip-java").choice("--format", "json")
                .text("--scip", "f.scip")
                .flag("--force-full").flag("--dry-run").flag("--no-resume").flag("--resume-only")
                .refusalsOnly());
        rules.check(command("index (import)", "index p --scip f.scip --provider x")
                .text("--provider-version", "1").text("--module", "m").text("--snapshot", "s"));
    }

    @Test
    void tools() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("tools list", "tools list").choice("--format", "json"));
        rules.check(command("tools verify", "tools verify").choice("--format", "json").flag("--all"));
        rules.check(command("tools install", "tools install scip-java").choice("--format", "json").refusalsOnly());
    }

    @Test
    void doctor() throws Exception {
        new CliArgumentRules(home).check(command("doctor", "doctor").choice("--format", "json"));
    }

    @Test
    void remote() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("remote materialize",
                "remote materialize https://github.com/a/b --ref main --commit " + COMMIT)
                .text("--subdir", "s").text("--credential-env", "MINOS_REMOTE_TOKEN").choice("--format", "json").refusalsOnly());
        rules.check(command("remote index",
                "remote index https://github.com/a/b --ref main --commit " + COMMIT + " --name n")
                .text("--subdir", "s").text("--credential-env", "MINOS_REMOTE_TOKEN").choice("--format", "json")
                .text("--provider", "scip-java").text("--worker", "w1").choice("--worker-network", "allow")
                .refusalsOnly());
        rules.check(command("remote (ref)", "remote materialize https://github.com/a/b --commit " + COMMIT)
                .text("--ref", "main").refusalsOnly());
        rules.check(command("remote (commit)", "remote materialize https://github.com/a/b --ref main")
                .text("--commit", COMMIT).refusalsOnly());
    }

    @Test
    void runtime() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("runtime import", "runtime import p --file f.tsv").choice("--format", "json"));
        rules.check(command("runtime import (file)", "runtime import p").text("--file", "f.tsv"));
        rules.check(command("runtime sessions", "runtime sessions p")
                .integer("--limit", 1, 128, 5).choice("--format", "json"));
        rules.check(command("runtime report", "runtime report p")
                .text("--session", "s1").integer("--limit", 1, 1000, 5).choice("--format", "json"));
        rules.check(command("runtime symbol", "runtime symbol p --symbol x")
                .text("--session", "s1").integer("--limit", 1, 1000, 5).choice("--format", "json"));
        rules.check(command("runtime symbol (symbol)", "runtime symbol p").text("--symbol", "x"));
    }
}

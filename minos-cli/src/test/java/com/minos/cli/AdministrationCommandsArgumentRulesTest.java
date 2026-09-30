package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.minos.cli.CliArgumentRules.command;

/**
 * Q11 : les commandes d'administration (registre de projets, import SCIP, providers, états de
 * récupération, export NEXUS, poignée de main IDE) obéissent aux règles uniformes de {@link CliOptions}.
 */
class AdministrationCommandsArgumentRulesTest {

    @TempDir Path home;

    @Test
    void projectAdd() throws Exception {
        new CliArgumentRules(home).check(command("project add", "project add /nonexistent-minos-dir")
                .text("--name", "Demo").choice("--format", "json"));
    }

    @Test
    void projectList() throws Exception {
        new CliArgumentRules(home).check(command("project list", "project list").choice("--format", "json"));
    }

    @Test
    void projectInspectAndItsAliases() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("project inspect", "project inspect p").choice("--format", "json"));
        rules.check(command("inspect", "inspect p").choice("--format", "json"));
        rules.check(command("index-status", "index-status p").choice("--format", "json"));
    }

    @Test
    void importScip() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("import-scip", "import-scip p --file /nonexistent.scip --provider x")
                .text("--provider-version", "1").text("--module", "m").text("--snapshot", "s")
                .choice("--format", "json"));
        // The two required options are ordinary options too.
        rules.check(command("import-scip (file)", "import-scip p --provider x")
                .text("--file", "/nonexistent.scip"));
        rules.check(command("import-scip (provider)", "import-scip p --file /nonexistent.scip")
                .text("--provider", "x"));
    }

    @Test
    void providers() throws Exception {
        new CliArgumentRules(home).check(command("providers", "providers")
                .choice("--format", "json").operandsAfterOptions());
    }

    @Test
    void semanticAndHybridStatus() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("semantic status", "semantic status p").choice("--format", "json"));
        rules.check(command("hybrid status", "hybrid status p").choice("--format", "json"));
    }

    @Test
    void nexusExport() throws Exception {
        new CliArgumentRules(home).check(command("nexus-export", "nexus-export")
                .text("--root", "/nonexistent-minos-root"));
    }

    @Test
    void ideHandshake() throws Exception {
        new CliArgumentRules(home).check(command("ide handshake", "ide handshake").choice("--format", "json"));
    }
}

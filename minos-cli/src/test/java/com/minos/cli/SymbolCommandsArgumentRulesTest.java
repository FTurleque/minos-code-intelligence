package com.minos.cli;

import com.minos.context.CodeSearchCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.minos.cli.CliArgumentRules.command;

/** Q11 : les commandes de requête de symboles obéissent aux règles uniformes de {@link CliOptions}. */
class SymbolCommandsArgumentRulesTest {

    @TempDir Path home;

    @Test
    void findSymbol() throws Exception {
        new CliArgumentRules(home).check(command("find-symbol", "find-symbol p S")
                .text("--qualified-name", "com.acme.S").choice("--kind", "class").text("--module", "m")
                .integer("--limit", 1, FindSymbolCommand.MAX_LIMIT, 5).choice("--format", "json"));
    }

    @Test
    void search() throws Exception {
        new CliArgumentRules(home).check(command("search", "search p Q")
                .text("--qualified-name", "com.acme.S").choice("--kind", "class").text("--module", "m")
                .integer("--limit", 1, 20, 5)
                .integer("--depth", 0, CodeSearchCriteria.MAX_DEPTH, 1)
                .integer("--usages", 0, CodeSearchCriteria.MAX_ITEMS_PER_NODE, 3)
                .integer("--relationships", 0, CodeSearchCriteria.MAX_ITEMS_PER_NODE, 3)
                .integer("--context-lines", 0, CodeSearchCriteria.MAX_CONTEXT_LINES, 2)
                .integer("--max-tokens", CodeSearchCriteria.MIN_TOKEN_BUDGET, CodeSearchCriteria.MAX_TOKEN_BUDGET, 4000)
                .flag("--no-source").choice("--format", "json"));
    }

    @Test
    void getSource() throws Exception {
        new CliArgumentRules(home).check(command("get-source", "get-source p F").choice("--format", "json"));
    }

    @Test
    void findUsages() throws Exception {
        new CliArgumentRules(home).check(command("find-usages", "find-usages p S")
                .integer("--limit", 1, FindSymbolCommand.MAX_LIMIT, 5).choice("--format", "json"));
    }

    @Test
    void everyRelationshipCommand() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        for (RelationshipCommand.Operation operation : RelationshipCommand.Operation.values()) {
            rules.check(command(operation.commandName(), operation.commandName() + " p S")
                    .integer("--limit", 1, FindSymbolCommand.MAX_LIMIT, 5).choice("--format", "json"));
        }
    }
}

package com.minos.cli;

import com.minos.hosted.HostedRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.minos.cli.CliArgumentRules.command;

/**
 * Q11 : {@code team} obéit aux règles uniformes de {@link CliOptions}, avec ses messages propres
 * (« unknown team option »). Le verrou « valider avant d'appeler le service » de chaque opération est
 * dans {@link TeamOperationGuardTest}.
 */
class TeamCommandsArgumentRulesTest {

    @TempDir Path home;

    @Test
    void audit() throws Exception {
        new CliArgumentRules(home).check(command("team audit", "team audit").scope("team ")
                .integer("--limit", 1, 10_000, 5));
    }

    @Test
    void mutationsAcceptAnOptionalRequestId() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("team workspace-create", "team workspace-create --name X").scope("team ")
                .text("--request-id", "req-1"));
        rules.check(command("team workspace-create (name)", "team workspace-create").scope("team ")
                .text("--name", "X"));
        rules.check(command("team member-grant", "team member-grant --principal p --display-name P --role viewer")
                .scope("team ").text("--request-id", "req-1"));
    }

    @Test
    void tokenLifetimeIsBoundedAtAnalysis() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("team token-issue", "team token-issue --principal p").scope("team ")
                .integer("--token-hours", 1, 24, 2).text("--request-id", "req-1"));
        rules.check(command("team key-rotate", "team key-rotate --key-id k").scope("team ")
                .integer("--token-hours", 1, 24, 2));
    }

    @Test
    void retentionBoundsAreCheckedAtAnalysis() throws Exception {
        CliArgumentRules rules = new CliArgumentRules(home);
        rules.check(command("team retention-set (events)",
                "team retention-set --audit-days 1 --archived-workspace-days 1").scope("team ")
                .integer("--max-audit-events", HostedRetentionPolicy.MIN_AUDIT_EVENTS,
                        HostedRetentionPolicy.MAX_AUDIT_EVENTS, 100));
        rules.check(command("team retention-set (audit days)",
                "team retention-set --max-audit-events 100 --archived-workspace-days 1").scope("team ")
                .integer("--audit-days", 1, 3650, 30));
        rules.check(command("team retention-set (archived days)",
                "team retention-set --max-audit-events 100 --audit-days 1").scope("team ")
                .integer("--archived-workspace-days", 1, 3650, 30));
    }
}

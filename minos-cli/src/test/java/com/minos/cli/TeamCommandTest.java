package com.minos.cli;

import com.minos.application.LocalProjectOperations;
import com.minos.application.LocalProjectSymbolQuery;
import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.minos.cli.TeamFixtures.SpyStore;

import static com.minos.cli.TeamFixtures.bootstrap;
import static com.minos.cli.TeamFixtures.extract;
import static com.minos.cli.TeamFixtures.keys;
import static com.minos.cli.TeamFixtures.service;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamCommandTest {
    @TempDir Path home;

    @Test
    void bootstrapsAuthenticatesAndNeverAcceptsBearerTokenAsArgument() throws Exception {
        MinosApplication application = MinosApplication.builder(home)
                .hostedTenantKeyProvider(keys()).build();
        AtomicReference<String> token = new AtomicReference<>();
        TeamCommand command = new TeamCommand(application.hostedControlPlaneService().orElseThrow(), token::get);
        UUID tenant = UUID.randomUUID();
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int bootstrap = command.run(new String[]{"bootstrap", "--tenant", tenant.toString(), "--name", "Acme",
                "--key-id", "key-a", "--owner", "alice", "--owner-name", "Alice",
                "--request-id", "req-bootstrap"}, output, error);
        assertEquals(0, bootstrap);
        assertTrue(output.toString().contains("\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\""));
        token.set(extract(output.toString(), "bearerToken"));

        output.setLength(0);
        assertEquals(0, command.run(new String[]{"workspace-create", "--name", "Platform",
                "--request-id", "req-workspace"}, output, error));
        assertTrue(output.toString().contains("\"name\":\"Platform\""));
        output.setLength(0);
        assertEquals(0, command.run(new String[]{"workspaces"}, output, error));
        assertTrue(output.toString().contains("\"isolation\":\"TENANT_SCOPED\""));

        error.setLength(0);
        String secret = "must-never-appear";
        assertEquals(2, command.run(new String[]{"tenant", "--token", secret}, output, error));
        assertFalse(error.toString().contains(secret));
        assertTrue(error.toString().contains("MINOS_TEAM_TOKEN"));
    }

    @Test
    void reportsDisabledTeamModeWithoutBreakingLocalCli() throws Exception {
        MinosApplication local = MinosApplication.builder(home).build();
        StringBuilder error = new StringBuilder();
        assertEquals(1, MinosCli.builder(new LocalProjectSymbolQuery(local))
                .projectOperations(new LocalProjectOperations(local))
                .architectureQuery(local.architectureQuery())
                .impactQuery(local.impactQuery())
                .autonomousOperations(new LocalAutonomousIndexOperations(local))
                .home(home)
                .runtimeIntelligenceService(local.runtimeIntelligenceService())
                .build()
                .run(new String[]{"team", "tenant"}, new StringBuilder(), error));
        assertTrue(error.toString().contains("team is not configured"));
    }

    @Test
    void tokenIssueWithUnknownOptionFailsBeforeAnyServiceCall() throws Exception {
        SpyStore store = new SpyStore();
        AtomicReference<String> token = new AtomicReference<>();
        TeamCommand command = new TeamCommand(service(store), token::get);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        token.set(bootstrap(command, UUID.randomUUID(), output, error));
        store.calls.clear();
        output.setLength(0);

        int code = command.run(new String[]{"token-issue", "--principal", "p", "--request_id", "x"}, output, error);

        assertEquals(List.of(), store.calls, "token-issue reached the service before rejecting --request_id");
        assertEquals(2, code);
        assertTrue(error.toString().contains("unknown team option: --request_id"));
        assertEquals("", output.toString());
    }

    @Test
    void usageDocumentsExactlyTheDeclaredOperations() {
        Set<String> documented = new LinkedHashSet<>();
        for (String line : TeamCommand.usage().lines().toList()) {
            var matcher = java.util.regex.Pattern.compile("^  ([a-z][a-z-]*)(?:\\s|$)").matcher(line);
            if (matcher.find()) documented.add(matcher.group(1));
        }
        assertEquals(new TreeSet<>(TeamCommand.operations()), new TreeSet<>(documented));
    }

    @Test
    void auditLimitOutsideTheDocumentedBoundsIsAUsageErrorBeforeAnyServiceCall() throws Exception {
        SpyStore store = new SpyStore();
        AtomicReference<String> token = new AtomicReference<>();
        TeamCommand command = new TeamCommand(service(store), token::get);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        token.set(bootstrap(command, UUID.randomUUID(), output, error));
        assertTrue(TeamCommand.usage().contains("audit [--limit <1..10000>]"));

        for (String limit : List.of("0", "-1", "10001", String.valueOf(Integer.MAX_VALUE))) {
            store.calls.clear();
            output.setLength(0);
            error.setLength(0);
            int code = command.run(new String[]{"audit", "--limit", limit}, output, error);
            assertEquals(2, code, "--limit " + limit + ": " + error);
            assertTrue(error.toString().contains("limit must be between 1 and 10000"), error.toString());
            assertTrue(error.toString().contains("Usage: minos team"), error.toString());
            assertEquals("", output.toString());
            assertEquals(List.of(), store.calls, "--limit " + limit + " reached the service");
        }
        for (String limit : List.of("1", "10000")) {
            output.setLength(0);
            error.setLength(0);
            assertEquals(0, command.run(new String[]{"audit", "--limit", limit}, output, error), error.toString());
        }
    }

    @Test
    void serviceFailuresExitWithExecutionCodeNotUsageCode() throws Exception {
        SpyStore store = new SpyStore();
        AtomicReference<String> token = new AtomicReference<>();
        TeamCommand command = new TeamCommand(service(store), token::get);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        String owner = bootstrap(command, UUID.randomUUID(), output, error);
        token.set(owner);

        error.setLength(0);
        assertEquals(1, command.run(new String[]{"member-revoke", "--principal", "ghost"}, output, error),
                "IllegalArgumentException raised by the service must not be a usage error");
        assertTrue(error.toString().contains("tenant member not found"));
        assertFalse(error.toString().contains("Usage: minos team"));

        error.setLength(0);
        token.set("not-a-token");
        assertEquals(1, command.run(new String[]{"tenant"}, output, error),
                "SecurityException raised by the service must not be a usage error");
        assertFalse(error.toString().contains("Usage: minos team"));

        error.setLength(0);
        token.set(null);
        assertEquals(1, command.run(new String[]{"tenant"}, output, error),
                "IllegalStateException raised for a missing token must keep the execution code");
        assertTrue(error.toString().contains("MINOS_TEAM_TOKEN"));

        error.setLength(0);
        store.failure = new IOException("store unavailable");
        token.set(owner);
        assertEquals(1, command.run(new String[]{"workspaces"}, output, error),
                "IOException raised by the service must be an execution error, not a crash");
        assertTrue(error.toString().contains("store unavailable"));
        assertFalse(error.toString().contains("Usage: minos team"));
    }

    @Test
    void existingJsonOutputsAreUnchanged() throws Exception {
        SpyStore store = new SpyStore();
        AtomicReference<String> token = new AtomicReference<>();
        TeamCommand command = new TeamCommand(service(store), token::get);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        token.set(bootstrap(command, UUID.randomUUID(), output, error));
        assertTrue(output.toString().contains("\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\""));
        UUID project = UUID.randomUUID();

        String workspace = run(command, output, error, "workspace-create", "--name", "Platform");
        assertTrue(workspace.contains("\"name\":\"Platform\""));
        String workspaceId = extract(workspace, "workspaceId");
        assertTrue(run(command, output, error, "workspaces").contains("\"isolation\":\"TENANT_SCOPED\""));
        assertTrue(run(command, output, error, "workspace-show", "--workspace", workspaceId)
                .contains("\"workspaceId\":\"" + workspaceId + "\""));
        assertTrue(run(command, output, error, "member-grant", "--principal", "bob", "--display-name", "Bob",
                "--role", "viewer").contains("\"principalId\":\"bob\""));
        assertEquals("{\"status\":\"REVOKED\"}\n",
                run(command, output, error, "member-revoke", "--principal", "bob"));
        assertEquals("{\"projectId\":\"" + project + "\",\"snapshotId\":\"snap-1\",\"status\":\"BOUND\"}\n",
                run(command, output, error, "project-bind", "--workspace", workspaceId,
                        "--project", project.toString(), "--snapshot", "snap-1"));
        assertEquals("{\"status\":\"UNBOUND\"}\n",
                run(command, output, error, "project-unbind", "--workspace", workspaceId,
                        "--project", project.toString()));
        String issued = run(command, output, error, "token-issue", "--principal", "alice", "--token-hours", "2");
        assertTrue(issued.contains("\"bearerToken\":\""));
        assertTrue(issued.contains("\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\""));
        String rotated = run(command, output, error, "key-rotate", "--key-id", "key-b");
        assertTrue(rotated.contains("\"replacementBearerToken\":\""));
        assertTrue(rotated.contains("\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\""));
        token.set(extract(rotated, "replacementBearerToken"));
        assertTrue(run(command, output, error, "retention-set", "--max-audit-events", "100", "--audit-days", "1",
                "--archived-workspace-days", "1").contains("\"maxAuditEvents\":100"));
        assertTrue(run(command, output, error, "retention-plan").contains("\"maxAuditEvents\":100"));
        assertTrue(run(command, output, error, "retention-apply").contains("\"newTenantVersion\":"));
        assertTrue(run(command, output, error, "members").contains("\"principalId\":\"alice\""));
        assertTrue(run(command, output, error, "audit", "--limit", "5").contains("\"action\":\"RETENTION_APPLY\""));
        assertTrue(run(command, output, error, "tenant").contains("\"isolation\":\"TENANT_SCOPED\""));
        assertTrue(run(command, output, error, "workspace-archive", "--workspace", workspaceId)
                .contains("\"status\":\"ARCHIVED\""));
    }

    private static String run(TeamCommand command, StringBuilder output, StringBuilder error, String... arguments)
            throws IOException {
        output.setLength(0);
        error.setLength(0);
        assertEquals(0, command.run(arguments, output, error), arguments[0] + ": " + error);
        return output.toString();
    }

    @Test
    void projectBindEscapesEveryCharacterOfTheSnapshotIdentifier() throws Exception {
        SpyStore store = new SpyStore();
        AtomicReference<String> token = new AtomicReference<>();
        TeamCommand command = new TeamCommand(service(store), token::get);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        token.set(bootstrap(command, UUID.randomUUID(), output, error));
        String workspaceId = extract(run(command, output, error, "workspace-create", "--name", "Platform"), "workspaceId");
        UUID project = UUID.randomUUID();
        StringBuilder snapshot = new StringBuilder("snap");
        for (char control = 1; control < 0x20; control++) {
            // The service already refuses NUL, tab, LF and CR in a snapshot identifier; every other control is accepted.
            if (control != '\t' && control != '\n' && control != '\r') snapshot.append(control);
        }
        snapshot.append("\"quoted\"\\path");

        String bound = run(command, output, error, "project-bind", "--workspace", workspaceId,
                "--project", project.toString(), "--snapshot", snapshot.toString());

        for (int index = 0; index < bound.length() - 1; index++) {
            assertTrue(bound.charAt(index) >= 0x20, "raw control character U+%04X in the JSON".formatted((int) bound.charAt(index)));
        }
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bound);
        assertEquals(snapshot.toString(), parsed.get("snapshotId").textValue());
        assertEquals(project.toString(), parsed.get("projectId").textValue());
        assertEquals("BOUND", parsed.get("status").textValue());
    }
}

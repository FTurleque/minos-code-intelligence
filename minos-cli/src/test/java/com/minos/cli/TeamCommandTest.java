package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.hosted.HmacHostedIdentityProvider;
import com.minos.hosted.HostedAuditSink;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        assertEquals(1, new MinosCli(
                new LocalProjectSymbolQuery(local), new LocalProjectOperations(local), local.architectureQuery(),
                local.impactQuery(), null, new LocalAutonomousIndexOperations(local), home, null, null, null,
                local.runtimeIntelligenceService(), null
        ).run(new String[]{"team", "tenant"}, new StringBuilder(), error));
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

    /**
     * Valid arguments of every declared team operation. The key set must equal
     * {@link TeamCommand#operations()}: a new operation fails this test until it is covered.
     */
    private static Map<String, List<String>> validArguments(String workspace, String project) {
        Map<String, List<String>> arguments = new LinkedHashMap<>();
        arguments.put("bootstrap", List.of("--tenant", UUID.randomUUID().toString(), "--name", "Other",
                "--key-id", "key-b", "--owner", "bob", "--owner-name", "Bob"));
        arguments.put("tenant", List.of());
        arguments.put("workspaces", List.of());
        arguments.put("workspace-show", List.of("--workspace", workspace));
        arguments.put("workspace-create", List.of("--name", "Platform"));
        arguments.put("workspace-archive", List.of("--workspace", workspace));
        arguments.put("members", List.of());
        arguments.put("member-grant", List.of("--principal", "bob", "--display-name", "Bob", "--role", "viewer"));
        arguments.put("member-revoke", List.of("--principal", "bob"));
        arguments.put("project-bind", List.of("--workspace", workspace, "--project", project, "--snapshot", "snap-1"));
        arguments.put("project-unbind", List.of("--workspace", workspace, "--project", project));
        arguments.put("token-issue", List.of("--principal", "alice", "--token-hours", "2"));
        arguments.put("key-rotate", List.of("--key-id", "key-b", "--token-hours", "2"));
        arguments.put("retention-plan", List.of());
        arguments.put("retention-set", List.of("--max-audit-events", "100", "--audit-days", "1",
                "--archived-workspace-days", "1"));
        arguments.put("retention-apply", List.of());
        arguments.put("audit", List.of("--limit", "5"));
        return arguments;
    }

    @Test
    void everyDeclaredOperationRejectsUnknownOptionsBeforeReachingTheService() throws Exception {
        SpyStore store = new SpyStore();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicInteger tokenReads = new AtomicInteger();
        TeamCommand command = new TeamCommand(service(store), () -> {
            tokenReads.incrementAndGet();
            return token.get();
        });
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        token.set(bootstrap(command, UUID.randomUUID(), output, error));
        Map<String, List<String>> valid = validArguments(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertEquals(TeamCommand.operations(), valid.keySet(),
                "every declared team operation must be covered by the validate-before-mutate guard");

        for (String operation : TeamCommand.operations()) {
            List<String> invocation = new ArrayList<>();
            invocation.add(operation);
            invocation.addAll(valid.get(operation));
            invocation.addAll(List.of("--bogus", "x"));
            store.calls.clear();
            tokenReads.set(0);
            output.setLength(0);
            error.setLength(0);
            int code = command.run(invocation.toArray(String[]::new), output, error);
            assertEquals(2, code, operation);
            assertTrue(error.toString().contains("unknown team option: --bogus"), operation);
            assertEquals("", output.toString(), operation);
            assertEquals(List.of(), store.calls, operation + " reached the service before rejecting --bogus");
            assertEquals(0, tokenReads.get(), operation + " read the bearer token before rejecting --bogus");
        }
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

    private static String bootstrap(TeamCommand command, UUID tenant, StringBuilder output, StringBuilder error)
            throws IOException {
        assertEquals(0, command.run(new String[]{"bootstrap", "--tenant", tenant.toString(), "--name", "Acme",
                "--key-id", "key-a", "--owner", "alice", "--owner-name", "Alice",
                "--request-id", "req-bootstrap"}, output, error), error.toString());
        return extract(output.toString(), "bearerToken");
    }

    private static HostedControlPlaneService service(SpyStore store) {
        return new HostedControlPlaneService(store, new HmacHostedIdentityProvider(keys()), keys(),
                (project, snapshot) -> { }, HostedAuditSink.embeddedNoop(), Clock.systemUTC());
    }

    /** In-memory store that records every persistence call: any entry proves the service was reached. */
    private static final class SpyStore implements HostedControlPlaneStore {
        private final Map<UUID, HostedTenantState> states = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private IOException failure;

        @Override
        public void create(HostedTenantState state) throws IOException {
            record("create");
            states.put(state.tenantId(), state);
        }

        @Override
        public Optional<HostedTenantState> find(UUID tenantId) throws IOException {
            record("find");
            return Optional.ofNullable(states.get(tenantId));
        }

        @Override
        public void save(HostedTenantState state, long expectedVersion) throws IOException {
            record("save");
            states.put(state.tenantId(), state);
        }

        private void record(String call) throws IOException {
            calls.add(call);
            if (failure != null) throw failure;
        }
    }

    private static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
    }

    private static String extract(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + field + "\\\":\\\"([^\\\"]+)\\\"").matcher(json);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}

package com.minos.cli;

import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.output.HostedControlPlaneRenderer;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Explicit opt-in M27 team/hosted control-plane CLI. */
final class TeamCommand {
    static final String NAME = "team";
    static final String TOKEN_ENVIRONMENT_VARIABLE = "MINOS_TEAM_TOKEN";

    private static final String USAGE = """
            Usage: minos team <operation> [options]

            Read-only:
              tenant
              workspaces
              workspace-show --workspace <uuid>
              members
              audit [--limit <1..10000>]
              retention-plan

            Mutations:
              bootstrap --tenant <uuid> --name <name> --key-id <id> --owner <id> --owner-name <name>
              workspace-create --name <name>
              workspace-archive --workspace <uuid>
              member-grant --principal <id> --display-name <name> --role <role>
              member-revoke --principal <id>
              project-bind --workspace <uuid> --project <uuid> --snapshot <id>
              project-unbind --workspace <uuid> --project <uuid>
              token-issue --principal <id> [--token-hours <1..24>]
              key-rotate --key-id <id> [--token-hours <1..24>]
              retention-set --max-audit-events <100..100000> --audit-days <1..3650>
                            --archived-workspace-days <1..3650>
              retention-apply

            Authentication:
              Set MINOS_TEAM_TOKEN for every operation except bootstrap.
              Bearer tokens are never accepted as command-line arguments.
              Mutations accept optional --request-id <id>; otherwise a UUID is generated.
            """.stripTrailing();

    /**
     * The single table of team operations. A parser only consumes and validates the options of its
     * operation and returns the deferred service call; parsers are static and never receive the
     * service, so no operation can reach the service (nor read the bearer token) before
     * {@link #execute} has rejected the options left unconsumed. Tests derive the operation list
     * from this table.
     */
    private static final Map<String, Parser> OPERATIONS = operationTable();

    private final HostedControlPlaneService service;
    private final Supplier<String> bearerToken;

    TeamCommand(HostedControlPlaneService service, Supplier<String> bearerToken) {
        this.service = Objects.requireNonNull(service, "service");
        this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
    }

    int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) return usageError("team operation is required", error);
        String rendered;
        try {
            rendered = execute(arguments[0], parseOptions(arguments));
        } catch (UsageException exception) {
            return usageError(safeMessage(exception), error);
        } catch (IllegalArgumentException | SecurityException | IllegalStateException | IOException exception) {
            error.append("error: ").append(safeMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
        output.append(rendered).append('\n');
        return FindSymbolCommand.SUCCESS;
    }

    static String usage() { return USAGE; }

    /** Names of every declared team operation, in declaration order. */
    static Set<String> operations() { return OPERATIONS.keySet(); }

    /**
     * Every option of the operation is consumed and validated (including the rejection of unknown
     * options) before the first service call, so a usage error can never follow a mutation.
     */
    private String execute(String operation, Map<String, String> options) throws IOException {
        Parser parser = OPERATIONS.get(operation);
        if (parser == null) throw new UsageException("unknown team operation: " + operation);
        Invocation invocation = parser.parse(options);
        rejectUnknown(options);
        return invocation.run(service, this::token);
    }

    private static Map<String, Parser> operationTable() {
        Map<String, Parser> table = new LinkedHashMap<>();
        table.put("bootstrap", TeamCommand::bootstrap);
        table.put("tenant", options -> (service, token) ->
                HostedControlPlaneRenderer.renderTenant(service.tenant(token.get())));
        table.put("workspaces", options -> (service, token) ->
                HostedControlPlaneRenderer.renderWorkspaces(service.listWorkspaces(token.get())));
        table.put("workspace-show", options -> {
            UUID workspace = uuid(required(options, "workspace"), "workspace");
            return (service, token) -> HostedControlPlaneRenderer.renderWorkspace(
                    service.workspace(token.get(), workspace));
        });
        table.put("workspace-create", options -> {
            String requestId = requestId(options);
            String name = required(options, "name");
            return (service, token) -> HostedControlPlaneRenderer.renderWorkspace(
                    service.createWorkspace(token.get(), requestId, name));
        });
        table.put("workspace-archive", options -> {
            String requestId = requestId(options);
            UUID workspace = uuid(required(options, "workspace"), "workspace");
            return (service, token) -> HostedControlPlaneRenderer.renderWorkspace(
                    service.archiveWorkspace(token.get(), requestId, workspace));
        });
        table.put("members", options -> (service, token) ->
                HostedControlPlaneRenderer.renderMembers(service.listMembers(token.get())));
        table.put("member-grant", options -> {
            String requestId = requestId(options);
            String principal = required(options, "principal");
            String displayName = required(options, "display-name");
            HostedRole role = role(required(options, "role"));
            return (service, token) -> HostedControlPlaneRenderer.renderMembers(List.of(
                    service.grantMember(token.get(), requestId, principal, displayName, role)));
        });
        table.put("member-revoke", options -> {
            String requestId = requestId(options);
            String principal = required(options, "principal");
            return (service, token) -> {
                service.revokeMember(token.get(), requestId, principal);
                return "{\"status\":\"REVOKED\"}";
            };
        });
        table.put("project-bind", options -> {
            String requestId = requestId(options);
            UUID workspace = uuid(required(options, "workspace"), "workspace");
            UUID project = uuid(required(options, "project"), "project");
            String snapshot = required(options, "snapshot");
            return (service, token) -> {
                var binding = service.bindProject(token.get(), requestId, workspace, project, snapshot);
                return "{\"projectId\":\"" + binding.projectId() + "\",\"snapshotId\":\""
                        + jsonEscape(binding.snapshotId()) + "\",\"status\":\"BOUND\"}";
            };
        });
        table.put("project-unbind", options -> {
            String requestId = requestId(options);
            UUID workspace = uuid(required(options, "workspace"), "workspace");
            UUID project = uuid(required(options, "project"), "project");
            return (service, token) -> {
                service.unbindProject(token.get(), requestId, workspace, project);
                return "{\"status\":\"UNBOUND\"}";
            };
        });
        table.put("token-issue", options -> {
            String requestId = requestId(options);
            String principal = required(options, "principal");
            Duration lifetime = tokenLifetime(options);
            return (service, token) -> HostedControlPlaneRenderer.renderToken(
                    service.issueToken(token.get(), requestId, principal, lifetime));
        });
        table.put("key-rotate", options -> {
            String requestId = requestId(options);
            String keyId = required(options, "key-id");
            Duration lifetime = tokenLifetime(options);
            return (service, token) -> HostedControlPlaneRenderer.renderRotation(
                    service.rotateKey(token.get(), requestId, keyId, lifetime));
        });
        table.put("retention-plan", options -> (service, token) -> {
            String bearer = token.get();
            return HostedControlPlaneRenderer.renderRetention(service.tenant(bearer).retentionPolicy(),
                    service.retentionPlan(bearer));
        });
        table.put("retention-set", options -> {
            String requestId = requestId(options);
            HostedRetentionPolicy policy = retentionPolicy(options);
            return (service, token) -> {
                String bearer = token.get();
                return HostedControlPlaneRenderer.renderRetention(
                        service.setRetention(bearer, requestId, policy), service.retentionPlan(bearer));
            };
        });
        table.put("retention-apply", options -> {
            String requestId = requestId(options);
            return (service, token) -> HostedControlPlaneRenderer.renderRetentionApply(
                    service.applyRetention(token.get(), requestId));
        });
        table.put("audit", options -> {
            int limit = auditLimit(options);
            return (service, token) -> HostedControlPlaneRenderer.renderAudit(service.audit(token.get(), limit));
        });
        return Collections.unmodifiableMap(table);
    }

    private static Invocation bootstrap(Map<String, String> options) {
        UUID tenant = uuid(required(options, "tenant"), "tenant");
        String name = required(options, "name");
        String keyId = required(options, "key-id");
        String owner = required(options, "owner");
        String ownerName = required(options, "owner-name");
        Duration lifetime = tokenLifetime(options);
        String requestId = requestId(options);
        return (service, token) -> HostedControlPlaneRenderer.renderBootstrap(
                service.bootstrap(tenant, name, keyId, owner, ownerName, lifetime, requestId));
    }

    /** The documented audit bound is a usage error: it is rejected before any service call. */
    private static int auditLimit(Map<String, String> options) {
        int limit = optionalInteger(options, "limit", 200);
        if (limit < HostedControlPlaneService.MIN_AUDIT_LIMIT || limit > HostedControlPlaneService.MAX_AUDIT_LIMIT) {
            throw new UsageException("audit limit must be between " + HostedControlPlaneService.MIN_AUDIT_LIMIT
                    + " and " + HostedControlPlaneService.MAX_AUDIT_LIMIT);
        }
        return limit;
    }

    /** Invalid retention bounds are a usage error: they are rejected before any service call. */
    private static HostedRetentionPolicy retentionPolicy(Map<String, String> options) {
        int maxAuditEvents = integer(required(options, "max-audit-events"), "max-audit-events");
        int auditDays = integer(required(options, "audit-days"), "audit-days");
        int archivedWorkspaceDays = integer(required(options, "archived-workspace-days"), "archived-workspace-days");
        try {
            return new HostedRetentionPolicy(maxAuditEvents, auditDays, archivedWorkspaceDays);
        } catch (IllegalArgumentException exception) {
            throw new UsageException(exception.getMessage());
        }
    }

    private String token() {
        String value = bearerToken.get();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(TOKEN_ENVIRONMENT_VARIABLE + " is required for authenticated team operations");
        }
        return value.trim();
    }

    private static Duration tokenLifetime(Map<String, String> options) {
        int hours = optionalInteger(options, "token-hours", 1);
        if (hours < 1 || hours > 24) throw new UsageException("token-hours must be between 1 and 24");
        return Duration.ofHours(hours);
    }

    private static String requestId(Map<String, String> options) {
        String value = options.remove("request-id");
        return value == null ? UUID.randomUUID().toString() : value;
    }

    private static Map<String, String> parseOptions(String[] arguments) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index += 2) {
            String argument = arguments[index];
            if (!argument.startsWith("--") || argument.length() == 2) {
                throw new UsageException("unexpected team argument: " + argument);
            }
            if ("--token".equals(argument) || "--bearer-token".equals(argument)) {
                throw new UsageException("bearer tokens are accepted only through " + TOKEN_ENVIRONMENT_VARIABLE);
            }
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")) {
                throw new UsageException("missing value for " + argument);
            }
            String key = argument.substring(2);
            if (options.putIfAbsent(key, arguments[index + 1]) != null) {
                throw new UsageException("duplicate team option: " + argument);
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.remove(key);
        if (value == null || value.isBlank()) throw new UsageException("missing required option: --" + key);
        return value;
    }

    private static int optionalInteger(Map<String, String> options, String key, int defaultValue) {
        String value = options.remove(key);
        return value == null ? defaultValue : integer(value, key);
    }

    private static int integer(String value, String key) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new UsageException(key + " must be an integer");
        }
    }

    private static UUID uuid(String value, String key) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new UsageException(key + " must be a UUID");
        }
    }

    private static HostedRole role(String value) {
        try {
            return HostedRole.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new UsageException("unsupported hosted role: " + value);
        }
    }

    private static void rejectUnknown(Map<String, String> options) {
        if (!options.isEmpty()) throw new UsageException("unknown team option: --" + options.keySet().iterator().next());
    }

    private static boolean isHelp(String value) { return "--help".equals(value) || "-h".equals(value); }

    private static int usageError(String message, Appendable error) throws IOException {
        error.append("error: ").append(message).append('\n').append(USAGE).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    private static String safeMessage(Exception exception) {
        return CliCommandSupport.failureMessage(exception);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Consumes and validates the options of one operation; it never receives the service. */
    @FunctionalInterface
    private interface Parser {
        Invocation parse(Map<String, String> options);
    }

    /** The service call of one operation, run only once every option has been validated. */
    @FunctionalInterface
    private interface Invocation {
        String run(HostedControlPlaneService service, Supplier<String> token) throws IOException;
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) { super(message); }
    }
}

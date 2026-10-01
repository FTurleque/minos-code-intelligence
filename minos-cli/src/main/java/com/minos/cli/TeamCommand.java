package com.minos.cli;

import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.output.DeterministicJson;
import com.minos.output.HostedControlPlaneRenderer;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final int DEFAULT_AUDIT_LIMIT = 200;
    private static final int DEFAULT_TOKEN_HOURS = 1;
    private static final int MIN_TOKEN_HOURS = 1;
    private static final int MAX_TOKEN_HOURS = 24;

    /**
     * The single table of team operations. An operation declares the options it accepts
     * ({@link Operation#options}) and a static parser that only reads them; neither ever receives
     * the service, so no operation can reach the service (nor read the bearer token) before
     * {@link #execute} has analysed the whole argument list against the declaration: unknown,
     * repeated, valueless and out-of-range options are usage errors raised before the first service
     * call. Tests derive the operation list, each operation's options and its documented bounds from
     * this table and from {@link #usage()}.
     */
    private static final Map<String, Operation> OPERATIONS = operationTable();

    private final Supplier<HostedControlPlaneService> service;
    private final Supplier<String> bearerToken;

    TeamCommand(HostedControlPlaneService service, Supplier<String> bearerToken) {
        this(supplying(service), bearerToken);
    }

    /** Service construit à son premier appel, c'est-à-dire après l'analyse des arguments. */
    TeamCommand(Supplier<HostedControlPlaneService> service, Supplier<String> bearerToken) {
        this.service = Objects.requireNonNull(service, "service");
        this.bearerToken = Objects.requireNonNull(bearerToken, "bearerToken");
    }

    private static Supplier<HostedControlPlaneService> supplying(HostedControlPlaneService service) {
        Objects.requireNonNull(service, "service");
        return () -> service;
    }

    int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 1 && CliCommandSupport.isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) return usageError("team operation is required", error);
        String rendered;
        try {
            rendered = execute(arguments[0], arguments);
        } catch (UsageException exception) {
            return usageError(exception.trusted ? exception.getMessage() : safeMessage(exception), error);
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

    /** The options an operation declares, in declaration order (what its analysis accepts and checks). */
    static List<CliOptions.Declared> declaredOptions(String operation) {
        Operation declared = OPERATIONS.get(operation);
        if (declared == null) throw new IllegalArgumentException("unknown team operation: " + operation);
        return declared.options.declared();
    }

    /**
     * The whole argument list is analysed against the operation's declaration, then its options are
     * read and validated, before the first service call: a usage error can never follow a mutation.
     */
    private String execute(String operation, String[] arguments) throws IOException {
        Operation declared = OPERATIONS.get(operation);
        if (declared == null) throw new UsageException("unknown team operation: " + operation);
        CliOptions options;
        try {
            options = declared.options.parse(arguments, 1);
        } catch (CliOptions.ForbiddenOptionException forbidden) {
            throw new UsageException(forbidden.getMessage(), true);
        } catch (IllegalArgumentException invalid) {
            throw new UsageException(invalid.getMessage());
        }
        Invocation invocation = declared.parser.parse(options);
        return invocation.run(service.get(), this::token);
    }

    private static Map<String, Operation> operationTable() {
        Map<String, Operation> table = new LinkedHashMap<>();
        table.put("bootstrap", new Operation(mutation()
                .text("--tenant", "--name", "--key-id", "--owner", "--owner-name")
                .integer("--token-hours", MIN_TOKEN_HOURS, MAX_TOKEN_HOURS), TeamCommand::bootstrap));
        table.put("tenant", new Operation(readOnly(), options -> (service, token) ->
                HostedControlPlaneRenderer.renderTenant(service.tenant(token.get()))));
        table.put("workspaces", new Operation(readOnly(), options -> (service, token) ->
                HostedControlPlaneRenderer.renderWorkspaces(service.listWorkspaces(token.get()))));
        table.put("workspace-show", new Operation(readOnly().text("--workspace"), options -> {
            UUID workspace = uuid(required(options, "--workspace"), "workspace");
            return (service, token) -> HostedControlPlaneRenderer.renderWorkspace(
                    service.workspace(token.get(), workspace));
        }));
        table.put("workspace-create", new Operation(mutation().text("--name"), options -> {
            String requestId = requestId(options);
            String name = required(options, "--name");
            return (service, token) -> HostedControlPlaneRenderer.renderWorkspace(
                    service.createWorkspace(token.get(), requestId, name));
        }));
        table.put("workspace-archive", new Operation(mutation().text("--workspace"), options -> {
            String requestId = requestId(options);
            UUID workspace = uuid(required(options, "--workspace"), "workspace");
            return (service, token) -> HostedControlPlaneRenderer.renderWorkspace(
                    service.archiveWorkspace(token.get(), requestId, workspace));
        }));
        table.put("members", new Operation(readOnly(), options -> (service, token) ->
                HostedControlPlaneRenderer.renderMembers(service.listMembers(token.get()))));
        table.put("member-grant", new Operation(mutation().text("--principal", "--display-name", "--role"), options -> {
            String requestId = requestId(options);
            String principal = required(options, "--principal");
            String displayName = required(options, "--display-name");
            HostedRole role = role(required(options, "--role"));
            return (service, token) -> HostedControlPlaneRenderer.renderMembers(List.of(
                    service.grantMember(token.get(), requestId, principal, displayName, role)));
        }));
        table.put("member-revoke", new Operation(mutation().text("--principal"), options -> {
            String requestId = requestId(options);
            String principal = required(options, "--principal");
            return (service, token) -> {
                service.revokeMember(token.get(), requestId, principal);
                return DeterministicJson.render(DeterministicJson.object("status", "REVOKED"));
            };
        }));
        table.put("project-bind", new Operation(mutation().text("--workspace", "--project", "--snapshot"), options -> {
            String requestId = requestId(options);
            UUID workspace = uuid(required(options, "--workspace"), "workspace");
            UUID project = uuid(required(options, "--project"), "project");
            String snapshot = required(options, "--snapshot");
            return (service, token) -> {
                var binding = service.bindProject(token.get(), requestId, workspace, project, snapshot);
                return DeterministicJson.render(DeterministicJson.object(
                        "projectId", binding.projectId().toString(),
                        "snapshotId", binding.snapshotId(),
                        "status", "BOUND"));
            };
        }));
        table.put("project-unbind", new Operation(mutation().text("--workspace", "--project"), options -> {
            String requestId = requestId(options);
            UUID workspace = uuid(required(options, "--workspace"), "workspace");
            UUID project = uuid(required(options, "--project"), "project");
            return (service, token) -> {
                service.unbindProject(token.get(), requestId, workspace, project);
                return DeterministicJson.render(DeterministicJson.object("status", "UNBOUND"));
            };
        }));
        table.put("token-issue", new Operation(mutation().text("--principal")
                .integer("--token-hours", MIN_TOKEN_HOURS, MAX_TOKEN_HOURS), options -> {
            String requestId = requestId(options);
            String principal = required(options, "--principal");
            Duration lifetime = tokenLifetime(options);
            return (service, token) -> HostedControlPlaneRenderer.renderToken(
                    service.issueToken(token.get(), requestId, principal, lifetime));
        }));
        table.put("key-rotate", new Operation(mutation().text("--key-id")
                .integer("--token-hours", MIN_TOKEN_HOURS, MAX_TOKEN_HOURS), options -> {
            String requestId = requestId(options);
            String keyId = required(options, "--key-id");
            Duration lifetime = tokenLifetime(options);
            return (service, token) -> HostedControlPlaneRenderer.renderRotation(
                    service.rotateKey(token.get(), requestId, keyId, lifetime));
        }));
        table.put("retention-plan", new Operation(readOnly(), options -> (service, token) -> {
            String bearer = token.get();
            return HostedControlPlaneRenderer.renderRetention(service.tenant(bearer).retentionPolicy(),
                    service.retentionPlan(bearer));
        }));
        table.put("retention-set", new Operation(mutation()
                .integer("--max-audit-events", HostedRetentionPolicy.MIN_AUDIT_EVENTS, HostedRetentionPolicy.MAX_AUDIT_EVENTS)
                .integer("--audit-days", HostedRetentionPolicy.MIN_RETENTION_DAYS, HostedRetentionPolicy.MAX_RETENTION_DAYS)
                .integer("--archived-workspace-days", HostedRetentionPolicy.MIN_RETENTION_DAYS,
                        HostedRetentionPolicy.MAX_RETENTION_DAYS), options -> {
            String requestId = requestId(options);
            HostedRetentionPolicy policy = retentionPolicy(options);
            return (service, token) -> {
                String bearer = token.get();
                return HostedControlPlaneRenderer.renderRetention(
                        service.setRetention(bearer, requestId, policy), service.retentionPlan(bearer));
            };
        }));
        table.put("retention-apply", new Operation(mutation(), options -> {
            String requestId = requestId(options);
            return (service, token) -> HostedControlPlaneRenderer.renderRetentionApply(
                    service.applyRetention(token.get(), requestId));
        }));
        table.put("audit", new Operation(readOnly().integer("--limit",
                HostedControlPlaneService.MIN_AUDIT_LIMIT, HostedControlPlaneService.MAX_AUDIT_LIMIT), options -> {
            int limit = options.integer("--limit", DEFAULT_AUDIT_LIMIT);
            return (service, token) -> HostedControlPlaneRenderer.renderAudit(service.audit(token.get(), limit));
        }));
        return Collections.unmodifiableMap(table);
    }

    private static Invocation bootstrap(CliOptions options) {
        UUID tenant = uuid(required(options, "--tenant"), "tenant");
        String name = required(options, "--name");
        String keyId = required(options, "--key-id");
        String owner = required(options, "--owner");
        String ownerName = required(options, "--owner-name");
        Duration lifetime = tokenLifetime(options);
        String requestId = requestId(options);
        return (service, token) -> HostedControlPlaneRenderer.renderBootstrap(
                service.bootstrap(tenant, name, keyId, owner, ownerName, lifetime, requestId));
    }

    /** Declaration shared by every operation: the bearer token is never an option, only {@value #TOKEN_ENVIRONMENT_VARIABLE}. */
    private static CliOptions.Spec readOnly() {
        String message = "bearer tokens are accepted only through " + TOKEN_ENVIRONMENT_VARIABLE;
        return CliOptions.spec().scope("team ").forbid("--token", message).forbid("--bearer-token", message);
    }

    /** {@link #readOnly()} plus the optional idempotency key that every mutation accepts. */
    private static CliOptions.Spec mutation() {
        return readOnly().text("--request-id");
    }

    /** Invalid retention bounds are a usage error: they are rejected before any service call. */
    private static HostedRetentionPolicy retentionPolicy(CliOptions options) {
        try {
            return new HostedRetentionPolicy(
                    requiredInteger(options, "--max-audit-events"),
                    requiredInteger(options, "--audit-days"),
                    requiredInteger(options, "--archived-workspace-days"));
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

    private static Duration tokenLifetime(CliOptions options) {
        return Duration.ofHours(options.integer("--token-hours", DEFAULT_TOKEN_HOURS));
    }

    private static String requestId(CliOptions options) {
        String value = options.text("--request-id");
        return value == null ? UUID.randomUUID().toString() : value;
    }

    private static String required(CliOptions options, String option) {
        String value = options.text(option);
        if (value == null) throw new UsageException("missing required option: " + option);
        return value;
    }

    private static int requiredInteger(CliOptions options, String option) {
        Integer value = options.optionalInteger(option);
        if (value == null) throw new UsageException("missing required option: " + option);
        return value;
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
            return HostedRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new UsageException("unsupported hosted role: " + value);
        }
    }

    private static int usageError(String message, Appendable error) throws IOException {
        error.append("error: ").append(message).append('\n').append(USAGE).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    private static String safeMessage(Exception exception) {
        return CliCommandSupport.failureMessage(exception);
    }

    /** One team operation: the options it accepts and the parser that reads them. Neither receives the service. */
    private record Operation(CliOptions.Spec options, Parser parser) {
        private Operation {
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(parser, "parser");
        }
    }

    /** Reads and validates the already analysed options of one operation; it never receives the service. */
    @FunctionalInterface
    private interface Parser {
        Invocation parse(CliOptions options);
    }

    /** The service call of one operation, run only once every option has been validated. */
    @FunctionalInterface
    private interface Invocation {
        String run(HostedControlPlaneService service, Supplier<String> token) throws IOException;
    }

    /** A usage error (code 2); {@code trusted} marks fixed CLI text, which is printed as is instead of being redacted. */
    private static final class UsageException extends IllegalArgumentException {
        private final boolean trusted;

        private UsageException(String message) { this(message, false); }

        private UsageException(String message, boolean trusted) {
            super(message);
            this.trusted = trusted;
        }
    }
}

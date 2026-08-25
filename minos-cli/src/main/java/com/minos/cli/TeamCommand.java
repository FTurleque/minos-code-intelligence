package com.minos.cli;

import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.output.HostedControlPlaneRenderer;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
        try {
            String operation = arguments[0];
            Map<String, String> options = parseOptions(arguments);
            String rendered = switch (operation) {
                case "bootstrap" -> bootstrap(options);
                case "tenant" -> noOptions(options, HostedControlPlaneRenderer.renderTenant(service.tenant(token())));
                case "workspaces" -> noOptions(options, HostedControlPlaneRenderer.renderWorkspaces(service.listWorkspaces(token())));
                case "workspace-show" -> HostedControlPlaneRenderer.renderWorkspace(
                        service.workspace(token(), uuid(required(options, "workspace"), "workspace")));
                case "workspace-create" -> HostedControlPlaneRenderer.renderWorkspace(
                        service.createWorkspace(token(), requestId(options), required(options, "name")));
                case "workspace-archive" -> HostedControlPlaneRenderer.renderWorkspace(service.archiveWorkspace(
                        token(), requestId(options), uuid(required(options, "workspace"), "workspace")));
                case "members" -> noOptions(options, HostedControlPlaneRenderer.renderMembers(service.listMembers(token())));
                case "member-grant" -> HostedControlPlaneRenderer.renderMembers(java.util.List.of(service.grantMember(
                        token(), requestId(options), required(options, "principal"), required(options, "display-name"),
                        role(required(options, "role")))));
                case "member-revoke" -> {
                    service.revokeMember(token(), requestId(options), required(options, "principal"));
                    yield "{\"status\":\"REVOKED\"}";
                }
                case "project-bind" -> {
                    var binding = service.bindProject(token(), requestId(options),
                            uuid(required(options, "workspace"), "workspace"),
                            uuid(required(options, "project"), "project"), required(options, "snapshot"));
                    yield "{\"projectId\":\"" + binding.projectId() + "\",\"snapshotId\":\""
                            + jsonEscape(binding.snapshotId()) + "\",\"status\":\"BOUND\"}";
                }
                case "project-unbind" -> {
                    service.unbindProject(token(), requestId(options),
                            uuid(required(options, "workspace"), "workspace"),
                            uuid(required(options, "project"), "project"));
                    yield "{\"status\":\"UNBOUND\"}";
                }
                case "token-issue" -> HostedControlPlaneRenderer.renderToken(service.issueToken(
                        token(), requestId(options), required(options, "principal"), tokenLifetime(options)));
                case "key-rotate" -> HostedControlPlaneRenderer.renderRotation(service.rotateKey(
                        token(), requestId(options), required(options, "key-id"), tokenLifetime(options)));
                case "retention-plan" -> {
                    rejectUnknown(options);
                    String token = token();
                    yield HostedControlPlaneRenderer.renderRetention(service.tenant(token).retentionPolicy(),
                            service.retentionPlan(token));
                }
                case "retention-set" -> {
                    HostedRetentionPolicy policy = new HostedRetentionPolicy(
                            integer(required(options, "max-audit-events"), "max-audit-events"),
                            integer(required(options, "audit-days"), "audit-days"),
                            integer(required(options, "archived-workspace-days"), "archived-workspace-days"));
                    String token = token();
                    yield HostedControlPlaneRenderer.renderRetention(
                            service.setRetention(token, requestId(options), policy), service.retentionPlan(token));
                }
                case "retention-apply" -> noOptions(options, HostedControlPlaneRenderer.renderRetentionApply(
                        service.applyRetention(token(), requestId(options))));
                case "audit" -> HostedControlPlaneRenderer.renderAudit(service.audit(
                        token(), optionalInteger(options, "limit", 200)));
                default -> throw new UsageException("unknown team operation: " + operation);
            };
            rejectUnknown(options);
            output.append(rendered).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (IllegalArgumentException exception) {
            return usageError(safeMessage(exception), error);
        } catch (SecurityException | IllegalStateException exception) {
            error.append("error: ").append(safeMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    static String usage() { return USAGE; }

    private String bootstrap(Map<String, String> options) throws IOException {
        var result = service.bootstrap(uuid(required(options, "tenant"), "tenant"), required(options, "name"),
                required(options, "key-id"), required(options, "owner"), required(options, "owner-name"),
                tokenLifetime(options), requestId(options));
        return HostedControlPlaneRenderer.renderBootstrap(result);
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

    private static String noOptions(Map<String, String> options, String result) {
        rejectUnknown(options);
        return result;
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

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) { super(message); }
    }
}

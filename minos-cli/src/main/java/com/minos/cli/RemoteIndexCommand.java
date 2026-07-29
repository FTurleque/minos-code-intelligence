package com.minos.cli;

import com.minos.output.SymbolOutputFormat;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteRepositoryRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Opt-in CLI surface for immutable remote materialization and worker-backed indexing. */
public final class RemoteIndexCommand {

    public static final String NAME = "remote";
    private static final Set<String> COMMON_OPTIONS = Set.of(
            "--ref", "--commit", "--subdir", "--credential-env", "--format"
    );
    private static final Set<String> INDEX_OPTIONS = Set.of(
            "--name", "--provider", "--worker", "--worker-network"
    );
    private static final String USAGE = """
            Usage:
              minos remote materialize <https-url> --ref <branch|tag> --commit <sha> [options]
              minos remote index <https-url> --ref <branch|tag> --commit <sha> --name <name>
                    --worker-network <allow|deny> [options]

            Common options:
              --subdir <relative-path>       Project root inside the repository
              --credential-env <NAME>        Environment variable containing an HTTPS token
              --format <text|json>           Output format (default: text)

            Index options:
              --provider <id>                Override provider negotiation
              --worker <id>                  Worker identity (default: local-native)
              --worker-network <allow|deny>  Required explicit provider network policy

            Security contract:
              github.com/gitlab.com HTTPS only; full commit pin required; no URL credentials.
              Native workers reject deny because they cannot prove OS-level network isolation.
            """.stripTrailing();

    private final RemoteIndexOperations operations;

    public RemoteIndexCommand(RemoteIndexOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        Options options;
        try {
            options = Options.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n').append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }
        try {
            RemoteRepositoryRequest request = RemoteRepositoryRequest.of(
                    options.repository(),
                    options.reference(),
                    options.commit(),
                    options.subdirectory(),
                    options.credentialEnvironmentVariable()
            );
            if (options.action() == Action.MATERIALIZE) {
                output.append(renderMaterialization(operations.materialize(request), options.format())).append('\n');
            } else {
                output.append(renderIndex(operations.index(
                        request,
                        options.displayName(),
                        options.provider(),
                        options.workerId(),
                        options.workerNetworkPolicy()
                ), options.format())).append('\n');
            }
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: remote ").append(options.action().command()).append(" failed: ")
                    .append(failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage() {
        return USAGE;
    }

    private static String renderMaterialization(
            RemoteIndexOperations.RemoteMaterializationView value,
            SymbolOutputFormat format
    ) {
        Map<String, Object> map = materializationMap(value);
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        return String.join("\n",
                "host: " + value.host(),
                "repository: " + value.repository(),
                "reference: " + value.reference(),
                "commit: " + value.commit(),
                "repositoryRoot: " + value.repositoryRoot(),
                "projectRoot: " + value.projectRoot(),
                "cacheKey: " + value.cacheKey(),
                "cacheHit: " + value.cacheHit(),
                "materializedAt: " + value.materializedAt()
        );
    }

    private static String renderIndex(RemoteIndexOperations.RemoteIndexView value, SymbolOutputFormat format) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("materialization", materializationMap(value.materialization()));
        map.put("projectId", value.projectId());
        map.put("projectName", value.projectName());
        map.put("execution", executionMap(value.execution()));
        map.put("artifacts", value.artifacts().stream().map(RemoteIndexCommand::artifactMap).toList());
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        List<String> lines = new ArrayList<>();
        lines.add(renderMaterialization(value.materialization(), SymbolOutputFormat.TEXT));
        lines.add("projectId: " + value.projectId());
        lines.add("projectName: " + value.projectName());
        lines.add("status: " + value.execution().status());
        lines.add("activeSnapshotId: " + nullable(value.execution().activeSnapshotId()));
        for (RemoteIndexOperations.ArtifactEvidence artifact : value.artifacts()) {
            lines.add("artifact[" + artifact.providerId() + "]: PASS sha256=" + artifact.artifactSha256()
                    + " worker=" + artifact.workerId()
                    + " isolation=" + artifact.isolation()
                    + " network=" + artifact.networkPolicy()
                    + " cacheHit=" + artifact.cacheHit());
        }
        return String.join("\n", lines);
    }

    private static Map<String, Object> materializationMap(RemoteIndexOperations.RemoteMaterializationView value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("host", value.host());
        map.put("repository", value.repository());
        map.put("reference", value.reference());
        map.put("commit", value.commit());
        map.put("repositoryRoot", value.repositoryRoot());
        map.put("projectRoot", value.projectRoot());
        map.put("cacheKey", value.cacheKey());
        map.put("cacheHit", value.cacheHit());
        map.put("materializedAt", value.materializedAt());
        return map;
    }

    private static Map<String, Object> executionMap(AutonomousIndexOperations.IndexExecutionView value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", value.runId());
        map.put("status", value.status());
        map.put("activeSnapshotId", value.activeSnapshotId());
        map.put("fingerprintPromoted", value.fingerprintPromoted());
        map.put("diagnostic", value.diagnostic());
        map.put("providers", value.plan().providerIds());
        map.put("languages", value.plan().languages());
        map.put("buildSystems", value.plan().buildSystems());
        map.put("mode", value.plan().mode().name());
        return map;
    }

    private static Map<String, Object> artifactMap(RemoteIndexOperations.ArtifactEvidence value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("providerId", value.providerId());
        map.put("providerVersion", value.providerVersion());
        map.put("language", value.language());
        map.put("workerId", value.workerId());
        map.put("isolation", value.isolation());
        map.put("networkPolicy", value.networkPolicy());
        map.put("networkDenyEnforced", value.networkDenyEnforced());
        map.put("artifactSha256", value.artifactSha256());
        map.put("bundleSha256", value.bundleSha256());
        map.put("cacheKey", value.cacheKey());
        map.put("cacheHit", value.cacheHit());
        return map;
    }

    private static String failureMessage(Exception exception) {
        Throwable effective = exception;
        while (effective.getCause() != null && effective instanceof RuntimeException) {
            effective = effective.getCause();
        }
        String message = effective.getMessage();
        return message == null || message.isBlank()
                ? effective.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private enum Action {
        MATERIALIZE("materialize"),
        INDEX("index");

        private final String command;

        Action(String command) {
            this.command = command;
        }

        String command() {
            return command;
        }
    }

    private record Options(
            Action action,
            String repository,
            String reference,
            String commit,
            String subdirectory,
            String credentialEnvironmentVariable,
            SymbolOutputFormat format,
            String displayName,
            String provider,
            String workerId,
            WorkerNetworkPolicy workerNetworkPolicy
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <materialize|index> <https-url>");
            }
            Action action = switch (arguments[0]) {
                case "materialize" -> Action.MATERIALIZE;
                case "index" -> Action.INDEX;
                default -> throw new IllegalArgumentException("unknown remote action: " + arguments[0]);
            };
            String repository = operand(arguments[1], "https-url");
            String reference = null;
            String commit = null;
            String subdirectory = null;
            String credential = null;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            String displayName = null;
            String provider = null;
            String workerId = "local-native";
            WorkerNetworkPolicy workerNetwork = null;
            Set<String> seen = new HashSet<>();
            Set<String> supported = new HashSet<>(COMMON_OPTIONS);
            if (action == Action.INDEX) {
                supported.addAll(INDEX_OPTIONS);
            }
            for (int index = 2; index < arguments.length; index++) {
                String option = arguments[index];
                if (!supported.contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }
                if (++index >= arguments.length || arguments[index] == null || arguments[index].isBlank()
                        || arguments[index].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[index];
                switch (option) {
                    case "--ref" -> reference = value;
                    case "--commit" -> commit = value;
                    case "--subdir" -> subdirectory = value;
                    case "--credential-env" -> credential = value;
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    case "--name" -> displayName = value;
                    case "--provider" -> provider = value;
                    case "--worker" -> workerId = value;
                    case "--worker-network" -> workerNetwork = parseNetwork(value);
                    default -> throw new IllegalStateException("unhandled option: " + option);
                }
            }
            if (reference == null) {
                throw new IllegalArgumentException("--ref is required");
            }
            if (commit == null) {
                throw new IllegalArgumentException("--commit is required");
            }
            if (action == Action.INDEX && (displayName == null || displayName.isBlank())) {
                throw new IllegalArgumentException("--name is required for remote index");
            }
            if (action == Action.INDEX && workerNetwork == null) {
                throw new IllegalArgumentException("--worker-network is required for remote index");
            }
            return new Options(action, repository, reference, commit, subdirectory, credential, format,
                    displayName, provider, workerId, workerNetwork);
        }

        private static WorkerNetworkPolicy parseNetwork(String value) {
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "allow" -> WorkerNetworkPolicy.ALLOW;
                case "deny" -> WorkerNetworkPolicy.DENY;
                default -> throw new IllegalArgumentException("worker network policy must be allow or deny");
            };
        }

        private static String operand(String value, String name) {
            if (value == null || value.isBlank() || value.startsWith("-")) {
                throw new IllegalArgumentException("invalid <" + name + "> operand");
            }
            return value;
        }
    }
}

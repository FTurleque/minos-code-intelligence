package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaProcessPlanFactory;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.local.process.IndexerProcessPlan;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Entry point of the child JVM started by {@link ChildJvm}: drives one managed-runtime manager against the
 * {@code PATH} the parent prepared, then prints what happened as {@code @@action.key=value} lines. It never
 * asserts anything: the parent does.
 *
 * <p>Arguments: manager ({@code python | polyglot | provider}), provider id, home directory, and the
 * comma-separated actions among {@code inspect}, {@code install}, {@code executor}.</p>
 *
 * <p>The {@code javaplan} scenario instead builds the scip-java process plan of a project: the arguments are
 * the project root, the run directory, and the {@code ;}-separated coursier, runner, managed Maven and classpath
 * file paths ({@code -} for an absent one).</p>
 */
public final class ManagedRuntimeScenarios {

    private ManagedRuntimeScenarios() {
    }

    public static void main(String[] args) {
        if ("javaplan".equals(args[0])) {
            javaPlan(Path.of(args[1]), Path.of(args[2]), args[3].split(";"));
            return;
        }
        String manager = args[0];
        String provider = args[1];
        Path home = Path.of(args[2]);
        Set<String> actions = Arrays.stream(args[3].split(",")).collect(Collectors.toSet());
        ProviderRuntimeManager runtime = switch (manager) {
            case "python" -> new ManagedScipPythonRuntimeManager(home);
            case "polyglot" -> new ManagedPolyglotScipRuntimeManager(home);
            case "provider" -> new ManagedScipProviderRuntimeManager(home);
            default -> throw new IllegalArgumentException("unknown manager: " + manager);
        };
        if (actions.contains("inspect")) {
            try {
                describe("inspect", runtime.inspect(provider));
            } catch (Exception failure) {
                failure("inspect", failure);
            }
        }
        if (actions.contains("install")) {
            try {
                describe("install", runtime.install(provider));
            } catch (Exception failure) {
                failure("install", failure);
            }
        }
        if (actions.contains("executor")) {
            try {
                runtime.executor(provider);
                print("executor.result", "created");
            } catch (Exception failure) {
                failure("executor", failure);
            }
        }
    }

    private static void javaPlan(Path project, Path runDirectory, String[] paths) {
        ScipJavaProcessPlanFactory factory = new ScipJavaProcessPlanFactory(
                Path.of(paths[0]), "org.scip-code:scip-java:0.13.1", Path.of(paths[1]),
                "-".equals(paths[2]) ? null : Path.of(paths[2]),
                "-".equals(paths[3]) ? null : Path.of(paths[3]));
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-java", "0.13.1", "scip-java", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        try {
            IndexerProcessPlan plan = factory.create(new IndexingExecutionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), project,
                    new IndexerSelection(Language.JAVA, descriptor)), runDirectory);
            print("plan.command", String.join(" | ", plan.command()));
            print("plan.workingDirectory", plan.workingDirectory().toString());
            print("plan.artifact", plan.generatedArtifact().toString());
            print("plan.timeout", plan.timeout().toString());
        } catch (Exception failure) {
            failure("plan", failure);
        }
    }

    private static void describe(String action, ProviderRuntimeStatus status) {
        print(action + ".state", status.state().name());
        print(action + ".version", status.version());
        print(action + ".executable", status.executable().isPresent() ? "present" : "absent");
        print(action + ".diagnostics", String.join(" | ", status.diagnostics()));
    }

    private static void failure(String action, Exception failure) {
        print(action + ".failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    private static void print(String key, String value) {
        System.out.println(ChildJvm.marker() + key + "=" + value.replace('\r', ' ').replace('\n', ' '));
    }
}

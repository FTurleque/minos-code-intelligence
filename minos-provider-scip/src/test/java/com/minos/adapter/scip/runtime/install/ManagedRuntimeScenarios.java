package com.minos.adapter.scip.runtime.install;

import com.minos.adapter.scip.runtime.plan.ScipJavaClasspath;
import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOffline;
import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOfflineFixtures;
import com.minos.adapter.scip.runtime.plan.ScipJavaProcessPlanFactory;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.local.process.IndexerProcessPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * file paths ({@code -} for an absent one), optionally followed by {@code refuse}: the classpath verifier then
 * refuses the first jar as the pinned lock would refuse an altered one (by default it accepts every jar), then by
 * the language of the request ({@code JAVA} by default).</p>
 */
public final class ManagedRuntimeScenarios {

    private ManagedRuntimeScenarios() {
    }

    public static void main(String[] args) throws IOException {
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
            case "provider" -> new ManagedScipProviderRuntimeManager(home, mavenOffline(home));
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

    /**
     * The offline Maven build of the child: a qualified fixture under {@value #MAVEN_FIXTURE_ENVIRONMENT} when the
     * test sets it, otherwise the unconfigured build of the host.
     */
    private static ScipJavaMavenOffline mavenOffline(Path home) {
        String fixture = System.getenv(MAVEN_FIXTURE_ENVIRONMENT);
        if (fixture == null || fixture.isBlank()) return ScipJavaMavenOfflineFixtures.unconfigured(home);
        try {
            return ScipJavaMavenOfflineFixtures.qualified(Path.of(fixture));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static final String MAVEN_FIXTURE_ENVIRONMENT = "MINOS_TEST_MAVEN_OFFLINE_FIXTURE";

    private static void javaPlan(Path project, Path runDirectory, String[] paths) throws IOException {
        boolean refuse = paths.length > 4 && "refuse".equals(paths[4]);
        Language language = paths.length > 5 ? Language.valueOf(paths[5]) : Language.JAVA;
        ScipJavaClasspath.Verifier verifier = jars -> {
            if (refuse) {
                throw new IllegalStateException("scip-java classpath verification failed: "
                        + jars.getFirst().getFileName() + " does not match its pinned SHA-256");
            }
        };
        ScipJavaProcessPlanFactory factory = new ScipJavaProcessPlanFactory(
                Path.of(paths[0]), "org.scip-code:scip-java:0.13.1", Path.of(paths[1]),
                "-".equals(paths[2]) ? null : Path.of(paths[2]),
                "-".equals(paths[3]) ? null : Path.of(paths[3]), verifier,
                paths.length > 6 && "unset".equals(paths[6])
                        ? ScipJavaMavenOfflineFixtures.designating(runDirectory.resolveSibling("maven-fixture"), null)
                        : ScipJavaMavenOfflineFixtures.qualified(runDirectory.resolveSibling("maven-fixture")));
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "scip-java", "0.13.1", "scip-java", Set.of(language), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        try {
            IndexerProcessPlan plan = factory.create(new IndexingExecutionRequest(
                    UUID.randomUUID(), UUID.randomUUID(), project,
                    new IndexerSelection(language, descriptor)), runDirectory);
            print("plan.command", String.join(" | ", plan.command()));
            print("plan.workingDirectory", plan.workingDirectory().toString());
            print("plan.artifact", plan.generatedArtifact().toString());
            print("plan.timeout", plan.timeout().toString());
            print("plan.readOnlyRoots", plan.readOnlyRoots().toString());
            print("plan.maskedFiles", plan.maskedFiles().toString());
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

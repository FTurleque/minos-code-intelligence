package com.minos.runtime.local;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrongProcessOwnershipIndexerExecutorTest {

    @TempDir
    Path temporary;

    @Test
    void unavailableCapabilityFailsClosedBeforeBuildingOrStartingProviderPlan() {
        AtomicBoolean planBuilt = new AtomicBoolean();
        Path project = temporary.resolve("unavailable-project");
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("unavailable-home"),
                (request, runDirectory) -> {
                    planBuilt.set(true);
                    throw new AssertionError("plan must not be built without strong ownership");
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate,
                new StrongProcessOwnershipIndexerExecutor.BoundaryProvider() {
                    @Override
                    public StrongProcessOwnershipIndexerExecutor.Capability capability() {
                        return StrongProcessOwnershipIndexerExecutor.Capability.unavailable(
                                "fixture", "kernel ownership unavailable");
                    }

                    @Override
                    public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
                        throw new AssertionError("transformer must not be requested without capability");
                    }
                });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(request(project)));

        assertFalse(planBuilt.get());
        assertTrue(failure.getMessage().contains("required but unavailable"));
    }

    @Test
    void invalidTransformedPlanStillReleasesBoundaryBeforeProviderStart() throws Exception {
        Path project = temporary.resolve("invalid-plan-project");
        Files.createDirectories(project);
        Path missingWorkingDirectory = temporary.resolve("missing-working-directory");
        Path executionMarker = temporary.resolve("provider-executed.marker");
        Path source = temporary.resolve("PreStartMarkerProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class PreStartMarkerProvider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "executed");
                    }
                }
                """);
        AtomicBoolean transformed = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();

        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("invalid-plan-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(javaExecutable(), source.toString(), executionMarker.toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("index.scip"),
                        Duration.ofSeconds(30)));
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate,
                new StrongProcessOwnershipIndexerExecutor.BoundaryProvider() {
                    @Override
                    public StrongProcessOwnershipIndexerExecutor.Capability capability() {
                        return StrongProcessOwnershipIndexerExecutor.Capability.available("fixture-strong-boundary");
                    }

                    @Override
                    public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
                        return new ProcessIndexerExecutor.ProcessPlanTransformer() {
                            @Override
                            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) {
                                transformed.set(true);
                                return new IndexerProcessPlan(
                                        plan.command(),
                                        missingWorkingDirectory,
                                        Map.of(),
                                        runDirectory.resolve("index.scip"),
                                        Duration.ofSeconds(30));
                            }

                            @Override
                            public void releaseContainment() {
                                released.set(true);
                            }
                        };
                    }
                });

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(request(project)));

        assertTrue(transformed.get(), "the fixture must create the containment boundary before validation fails");
        assertTrue(released.get(), "pre-start validation failure must reclaim transform-created containment");
        assertFalse(Files.exists(executionMarker),
                "provider code must not execute when transformed-plan validation fails before start");
        assertTrue(failure.getMessage().contains("working directory is missing"));
    }

    @Test
    void timeoutAlwaysKillsAndReleasesStrongBoundary() throws Exception {
        Path project = temporary.resolve("timeout-project");
        Files.createDirectories(project);
        Path source = temporary.resolve("TimeoutProvider.java");
        Files.writeString(source, """
                public class TimeoutProvider {
                    public static void main(String[] args) throws Exception {
                        Thread.sleep(300_000L);
                    }
                }
                """);
        AtomicBoolean killed = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("timeout-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(javaExecutable(), source.toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("index.scip"),
                        Duration.ofMillis(200)));
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate,
                strongFixtureBoundary(killed, released, false));

        assertThrows(IllegalStateException.class, () -> executor.execute(request(project)));
        assertTrue(killed.get(), "timeout cleanup must kill the OS boundary");
        assertTrue(released.get(), "timeout cleanup must reclaim the OS boundary");
    }

    @Test
    void containmentReleaseExceptionIsNotSilentlyIgnoredAfterProviderCleanup() throws Exception {
        Path project = temporary.resolve("release-project");
        Files.createDirectories(project);
        Path source = temporary.resolve("SuccessfulProvider.java");
        Files.writeString(source, """
                import java.nio.file.*;
                public class SuccessfulProvider {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of(args[0]), "artifact");
                    }
                }
                """);
        AtomicBoolean killed = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("release-home"),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of(javaExecutable(), source.toString(), runDirectory.resolve("generated.scip").toString()),
                        project,
                        Map.of(),
                        runDirectory.resolve("generated.scip"),
                        Duration.ofSeconds(30)));
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate,
                strongFixtureBoundary(killed, released, true));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(request(project)));

        assertTrue(killed.get(), "successful provider completion must still terminate remaining job members");
        assertTrue(released.get(), "release must be attempted on every exit path");
        assertTrue(failure.getMessage().contains("fixture release failure"));
    }

    /**
     * ADR 0041 / audit S11 point 5. The Docker admin plane has no OS sandbox (no bwrap, no user
     * namespaces, read-only cgroup), so managed-local selection falls back to the native
     * process-only backend there. This is the line that keeps project code from running in that
     * case, independently of the provider readiness check upstream: it must refuse before the
     * project is copied and before any run directory exists.
     */
    @Test
    void nativeOnlyHostRefusesManagedProviderBeforeCopyingTheProjectOrCreatingARun() {
        for (WorkerNetworkPolicy policy : WorkerNetworkPolicy.values()) {
            Path home = temporary.resolve("native-only-home-" + policy);
            Path project = temporary.resolve("native-only-project-" + policy);
            AtomicBoolean planBuilt = new AtomicBoolean();
            ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                    "fake-provider",
                    home,
                    (request, runDirectory) -> {
                        planBuilt.set(true);
                        throw new AssertionError("a provider plan must never be built without a managed sandbox");
                    });
            StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                    delegate, home, policy, selectedHome -> WorkerSandboxBackend.nativeEphemeralWorkspace());

            var submitted = request(project);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> executor.execute(submitted));

            assertTrue(failure.getMessage().contains("qualified managed local provider sandbox is unavailable"),
                    failure.getMessage());
            assertFalse(planBuilt.get(), policy + ": no provider plan");
            assertFalse(Files.exists(home.resolve("local-provider-workspaces")), policy + ": no project copy");
            assertFalse(Files.exists(home.resolve("runs")), policy + ": no run directory");
        }
    }

    @Test
    void managedSandboxWithoutNetworkDenyProofRefusesDenyBeforeCopyingTheProject() {
        Path home = temporary.resolve("no-deny-proof-home");
        AtomicBoolean sandboxReached = new AtomicBoolean();
        WorkerSandboxBackend backend = managedBackend(false, sandboxReached);
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider", home,
                (request, runDirectory) -> {
                    throw new AssertionError("a provider plan must never be built here");
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate, home, WorkerNetworkPolicy.DENY, selectedHome -> backend);

        var submitted = request(temporary.resolve("no-deny-proof-project"));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(submitted));

        assertTrue(failure.getMessage().contains("cannot prove OS-level network denial"), failure.getMessage());
        assertFalse(sandboxReached.get());
        assertFalse(Files.exists(home.resolve("local-provider-workspaces")));
    }

    /** Positive control: the guards above are not a blanket refusal, a qualified sandbox is reached. */
    @Test
    void qualifiedManagedSandboxIsReachedOnlyThroughTheSelectedBackend() {
        Path home = temporary.resolve("qualified-home");
        AtomicBoolean sandboxReached = new AtomicBoolean();
        WorkerSandboxBackend backend = managedBackend(true, sandboxReached);
        ProcessIndexerExecutor delegate = new ProcessIndexerExecutor(
                "fake-provider", home,
                (request, runDirectory) -> {
                    throw new AssertionError("the fixture sandbox never starts the delegate");
                });
        StrongProcessOwnershipIndexerExecutor executor = new StrongProcessOwnershipIndexerExecutor(
                delegate, home, WorkerNetworkPolicy.DENY, selectedHome -> backend);

        var submitted = request(temporary.resolve("qualified-project"));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(submitted));

        assertTrue(sandboxReached.get(), "a managed-qualified sandbox must receive the copied request");
        assertTrue(failure.getMessage().contains("fixture sandbox reached"), failure.getMessage());
    }

    private static WorkerSandboxBackend managedBackend(boolean enforcesNetworkDeny, AtomicBoolean reached) {
        WorkerResourceContainment containment = new WorkerResourceContainment(
                "fixture-managed-job",
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                List.of("fixture"));
        WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                "fixture-managed-sandbox",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED,
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                containment,
                Map.of(WorkerSandboxQualification.currentPlatform(),
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                List.of());
        return new WorkerSandboxBackend() {
            @Override public String id() { return qualification.backendId(); }
            @Override public WorkerIsolation isolation() { return qualification.isolation(); }
            @Override public NetworkGuarantee networkGuarantee() { return qualification.networkGuarantee(); }
            @Override public WorkerSandboxQualification qualification() { return qualification; }
            @Override public boolean enforcesNetworkDeny() { return enforcesNetworkDeny; }

            @Override
            public IndexingArtifact execute(
                    IndexerExecutor delegate,
                    IndexingExecutionRequest request,
                    WorkerNetworkPolicy networkPolicy
            ) {
                reached.set(true);
                throw new IllegalStateException("fixture sandbox reached");
            }
        };
    }

    private static StrongProcessOwnershipIndexerExecutor.BoundaryProvider strongFixtureBoundary(
            AtomicBoolean killed,
            AtomicBoolean released,
            boolean failRelease
    ) {
        return new StrongProcessOwnershipIndexerExecutor.BoundaryProvider() {
            @Override
            public StrongProcessOwnershipIndexerExecutor.Capability capability() {
                return StrongProcessOwnershipIndexerExecutor.Capability.available("fixture-strong-boundary");
            }

            @Override
            public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
                return new ProcessIndexerExecutor.ProcessPlanTransformer() {
                    @Override
                    public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) {
                        return plan;
                    }

                    @Override
                    public void killContainedJob() {
                        killed.set(true);
                    }

                    @Override
                    public void releaseContainment() {
                        released.set(true);
                        if (failRelease) throw new IllegalStateException("fixture release failure");
                    }
                };
            }
        };
    }

    private static IndexingExecutionRequest request(Path project) {
        try {
            Files.createDirectories(project);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }

    private static String javaExecutable() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                CommandLocator.isWindows() ? "java.exe" : "java").toString();
    }
}

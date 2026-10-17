package com.minos.runtime.local.process;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor;
import com.minos.runtime.local.sandbox.WorkerSandboxBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An executor that starts a provider process with no boundary behind it must refuse, and no public way of
 * building an ownership-only executor may leave the network policy out (AUD-ARC-09).
 */
class ProcessIndexerExecutorBoundaryRefusalTest {

    private static final String PROVIDER = "fake-provider";

    @TempDir
    Path temporary;

    @Test
    void executeRefusesWithoutStartingAProcessOrCreatingARunDirectory() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path home = temporary.resolve("home");
        AtomicInteger planRequests = new AtomicInteger();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(PROVIDER, home, (request, runDirectory) -> {
            planRequests.incrementAndGet();
            return new IndexerProcessPlan(List.of("never-started"), project, Map.of(),
                    project.resolve("index.scip"), Duration.ofSeconds(5));
        });

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> executor.execute(request(project)));

        assertEquals("provider process requires an ownership or sandbox boundary", refusal.getMessage());
        assertEquals(0, planRequests.get(), "no plan may be built for a run that is refused");
        assertFalse(Files.exists(home.resolve("runs")), "the refusal must come before any run directory");
        assertFalse(refusal.getMessage().contains(home.toString()), "the refusal carries no path");
    }

    @Test
    void theNativeBackendCannotRunABareProcessExecutor() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("native-project"));
        Path home = temporary.resolve("native-home");
        ProcessIndexerExecutor bare = new ProcessIndexerExecutor(PROVIDER, home, (request, runDirectory) -> {
            throw new AssertionError("a bare executor must not be asked for a plan");
        });

        assertThrows(IllegalStateException.class, () -> WorkerSandboxBackend.nativeEphemeralWorkspace()
                .execute(bare, request(project), WorkerNetworkPolicy.ALLOW));
        assertFalse(Files.exists(home.resolve("runs")));
    }

    @Test
    void everyPublicConstructorOfTheOwnershipExecutorTakesANetworkPolicy() {
        for (Constructor<?> constructor : StrongProcessOwnershipIndexerExecutor.class.getConstructors()) {
            assertTrue(Arrays.asList(constructor.getParameterTypes()).contains(WorkerNetworkPolicy.class),
                    "a public constructor without a network policy builds an executor with no sandbox: "
                            + constructor);
            assertTrue(Modifier.isPublic(constructor.getModifiers()));
        }
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                PROVIDER, "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}

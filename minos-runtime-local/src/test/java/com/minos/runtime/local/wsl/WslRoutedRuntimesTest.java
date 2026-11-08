package com.minos.runtime.local.wsl;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR SEC-0079, decision 2: scip-java is routed (readiness from the probe, executor from the route) and every other
 * provider stays native; the native status and executor of scip-java are never consulted.
 */
class WslRoutedRuntimesTest {

    private final NativeRuntimes natives = new NativeRuntimes();
    private final AtomicInteger probes = new AtomicInteger();
    private final List<String> distributions = new ArrayList<>();
    private final IndexerExecutor routedExecutor = executor("scip-java");

    @Test
    void aReadyRouteMakesScipJavaReadyAndYieldsTheRoutedExecutorForTheResolvedDistribution() {
        WslRoutedRuntimes runtimes = runtimes(WslRoutedRuntimes.Readiness.ready("Ubuntu"));

        assertEquals(ProviderRuntimeStatus.State.READY, runtimes.inspect("scip-java").state());
        assertSame(routedExecutor, runtimes.executor("scip-java"));
        assertEquals(List.of("Ubuntu"), distributions);
        assertEquals(1, probes.get(), "the route is probed once per indexing");
    }

    @Test
    void aRefusedRouteBlocksScipJavaWithItsActionAndBuildsNoExecutor() {
        String refusal = "WSL route prerequisite bubblewrap is missing: install bubblewrap in the distribution";
        WslRoutedRuntimes runtimes = runtimes(WslRoutedRuntimes.Readiness.refused(refusal));

        ProviderRuntimeStatus status = runtimes.inspect("scip-java");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> runtimes.executor("scip-java"));

        assertEquals(ProviderRuntimeStatus.State.BLOCKED, status.state());
        assertEquals(List.of(refusal), status.diagnostics());
        assertEquals(refusal, refused.getMessage());
        assertEquals(List.of(), distributions);
    }

    @Test
    void everyOtherProviderIsNativeAndScipJavaIsNeverInstalledFromWindows() throws Exception {
        WslRoutedRuntimes runtimes = runtimes(WslRoutedRuntimes.Readiness.ready("Ubuntu"));

        assertSame(natives.typeScript, runtimes.inspect("scip-typescript"));
        assertSame(natives.typeScriptExecutor, runtimes.executor("scip-typescript"));
        assertSame(natives.typeScript, runtimes.install("scip-typescript"));
        assertThrows(IllegalStateException.class, () -> runtimes.install("scip-java"));
        List<ProviderRuntimeStatus> listed = runtimes.list();
        assertEquals(ProviderRuntimeStatus.State.READY, listed.get(0).state(), "scip-java is listed routed");
        assertSame(natives.typeScript, listed.get(1));
    }

    @Test
    void aReadinessIsEitherADistributionOrARefusal() {
        assertThrows(IllegalArgumentException.class,
                () -> new WslRoutedRuntimes.Readiness(Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new WslRoutedRuntimes.Readiness(Optional.of("Ubuntu"), Optional.of("refused")));
        List<WslCheck> ready = WslCheck.IDENTIFIERS.stream().map(WslCheck::ok).toList();
        assertEquals(WslRoutedRuntimes.Readiness.ready("Ubuntu"),
                WslRoutedRuntimes.Readiness.of(new WslProbeReport("Ubuntu", ready)));
        assertEquals(WslRoutedRuntimes.Readiness.refused("the WSL distribution could not be resolved"),
                WslRoutedRuntimes.Readiness.of(new WslProbeReport(null, ready)));
    }

    private WslRoutedRuntimes runtimes(WslRoutedRuntimes.Readiness readiness) {
        return new WslRoutedRuntimes(natives, "0.13.2", () -> {
            probes.incrementAndGet();
            return readiness;
        }, distribution -> {
            distributions.add(distribution);
            return routedExecutor;
        });
    }

    private static IndexerExecutor executor(String id) {
        return new IndexerExecutor() {
            @Override public String indexerId() { return id; }
            @Override public IndexingArtifact execute(IndexingExecutionRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Native runtimes in which scip-java must never be asked anything. */
    private static final class NativeRuntimes implements ProviderRuntimeManager {
        private final ProviderRuntimeStatus scipJava = new ProviderRuntimeStatus("scip-java", "0.13.2",
                ProviderRuntimeStatus.State.BLOCKED, Optional.empty(), List.of("not qualified"), true);
        private final ProviderRuntimeStatus typeScript = new ProviderRuntimeStatus("scip-typescript", "0.3.0",
                ProviderRuntimeStatus.State.READY, Optional.empty(), List.of(), true);
        private final IndexerExecutor typeScriptExecutor = executor("scip-typescript");

        @Override public List<ProviderRuntimeStatus> list() { return List.of(scipJava, typeScript); }

        @Override
        public ProviderRuntimeStatus inspect(String providerId) {
            if ("scip-java".equals(providerId)) throw new AssertionError("native scip-java status consulted");
            return typeScript;
        }

        @Override
        public ProviderRuntimeStatus install(String providerId) {
            if ("scip-java".equals(providerId)) throw new AssertionError("native scip-java installed");
            return typeScript;
        }

        @Override
        public IndexerExecutor executor(String providerId) {
            if ("scip-java".equals(providerId)) throw new AssertionError("native scip-java executor resolved");
            return typeScriptExecutor;
        }
    }
}

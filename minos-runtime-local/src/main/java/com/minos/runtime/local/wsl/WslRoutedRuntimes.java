package com.minos.runtime.local.wsl;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Provider runtimes of the WSL2 route (ADR SEC-0079, decision 2): scip-java takes its readiness from the WSL
 * prerequisite probe and its executor from the route; every other provider is the native one, unchanged.
 *
 * <p>The native {@code BLOCKED} status of scip-java is never lifted: {@link #inspect} and {@link #executor} never
 * reach the native runtime for it, and {@link #list} replaces its native entry by the routed one. The probe runs once
 * per instance, on the first question about scip-java, and launches no provider.</p>
 */
public final class WslRoutedRuntimes implements ProviderRuntimeManager {

    private final ProviderRuntimeManager nativeRuntimes;
    private final String providerVersion;
    private final Supplier<Readiness> probe;
    private final Function<String, IndexerExecutor> executors;
    private Readiness readiness;

    /**
     * @param nativeRuntimes  runtimes of this installation, which answer for every other provider
     * @param providerVersion version of scip-java in this jar
     * @param probe           readiness of the route, probed on first use
     * @param executors       executor of scip-java for the resolved distribution name
     */
    public WslRoutedRuntimes(ProviderRuntimeManager nativeRuntimes, String providerVersion, Supplier<Readiness> probe,
                             Function<String, IndexerExecutor> executors) {
        this.nativeRuntimes = Objects.requireNonNull(nativeRuntimes, "nativeRuntimes");
        this.providerVersion = Objects.requireNonNull(providerVersion, "providerVersion");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    /** Readiness of the route: the resolved distribution, or the actionable refusal that names the missing check. */
    public record Readiness(Optional<String> distribution, Optional<String> refusal) {

        public Readiness {
            Objects.requireNonNull(distribution, "distribution");
            Objects.requireNonNull(refusal, "refusal");
            if (distribution.isPresent() == refusal.isPresent()) {
                throw new IllegalArgumentException("a route is either ready in a distribution or refused");
            }
        }

        public static Readiness of(WslProbeReport report) {
            Objects.requireNonNull(report, "report");
            Optional<String> refusal = report.runRefusal();
            if (refusal.isPresent()) {
                return refused(refusal.orElseThrow());
            }
            return report.distribution().map(Readiness::ready)
                    .orElseGet(() -> refused("the WSL distribution could not be resolved"));
        }

        public static Readiness ready(String distribution) {
            return new Readiness(Optional.of(distribution), Optional.empty());
        }

        public static Readiness refused(String refusal) {
            return new Readiness(Optional.empty(), Optional.of(refusal));
        }
    }

    @Override
    public List<ProviderRuntimeStatus> list() {
        return nativeRuntimes.list().stream()
                .map(status -> routed(status.providerId()) ? inspect(status.providerId()) : status)
                .toList();
    }

    @Override
    public ProviderRuntimeStatus inspect(String providerId) {
        if (!routed(providerId)) {
            return nativeRuntimes.inspect(providerId);
        }
        Readiness current = readiness();
        return current.refusal()
                .map(refusal -> new ProviderRuntimeStatus(providerId, providerVersion,
                        ProviderRuntimeStatus.State.BLOCKED, Optional.empty(), List.of(refusal), true))
                .orElseGet(() -> new ProviderRuntimeStatus(providerId, providerVersion,
                        ProviderRuntimeStatus.State.READY, Optional.empty(), List.of(), true));
    }

    /** A routed provider is installed in the distribution by its user, never by this installation. */
    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        if (routed(providerId)) {
            throw new IllegalStateException(providerId + " runs in the WSL distribution: install it there");
        }
        return nativeRuntimes.install(providerId);
    }

    @Override
    public IndexerExecutor executor(String providerId) {
        if (!routed(providerId)) {
            return nativeRuntimes.executor(providerId);
        }
        Readiness current = readiness();
        if (current.refusal().isPresent()) {
            throw new IllegalStateException(current.refusal().orElseThrow());
        }
        return Objects.requireNonNull(executors.apply(current.distribution().orElseThrow()), "routed executor");
    }

    private static boolean routed(String providerId) {
        return WslLinuxWorker.ACCEPTED_PROVIDER.equals(providerId);
    }

    private synchronized Readiness readiness() {
        if (readiness == null) {
            readiness = Objects.requireNonNull(probe.get(), "route readiness");
        }
        return readiness;
    }
}

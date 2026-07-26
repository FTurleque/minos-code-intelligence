package com.minos.application;

import com.minos.orchestration.IndexerProvider;
import com.minos.orchestration.ProviderConformanceKit;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shared read-only provider/discovery platform view for CLI, API and MCP. */
public final class ProviderPlatformService {
    private final List<IndexerProvider> providers;
    private final ProviderRuntimeManager runtimes;
    private final ProviderConformanceKit conformanceKit = new ProviderConformanceKit();

    public ProviderPlatformService(List<? extends IndexerProvider> providers, ProviderRuntimeManager runtimes) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    public List<ProviderView> listProviders() {
        Map<String, ProviderRuntimeStatus> statuses = runtimes.list().stream()
                .collect(Collectors.toUnmodifiableMap(ProviderRuntimeStatus::providerId, Function.identity()));
        return providers.stream()
                .map(provider -> view(provider, statuses.get(provider.descriptor().id())))
                .sorted(java.util.Comparator.comparing(ProviderView::id))
                .toList();
    }

    public ProviderView inspect(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        IndexerProvider provider = providers.stream()
                .filter(candidate -> providerId.equals(candidate.descriptor().id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown provider: " + providerId));
        ProviderRuntimeStatus status = runtimes.inspect(providerId);
        return view(provider, status);
    }

    private ProviderView view(IndexerProvider provider, ProviderRuntimeStatus status) {
        ProviderConformanceKit.ConformanceResult result = conformanceKit.evaluate(provider);
        return new ProviderView(
                result.providerId(),
                result.version(),
                result.languages(),
                result.buildSystems(),
                result.capabilities(),
                result.scorePercent(),
                result.limitations(),
                status == null ? "UNMANAGED" : status.state().name(),
                status == null ? List.of("no runtime manager registered") : status.diagnostics()
        );
    }

    public record ProviderView(
            String id,
            String version,
            List<String> languages,
            List<String> buildSystems,
            Map<String, String> capabilities,
            int conformanceScorePercent,
            List<String> limitations,
            String runtimeState,
            List<String> runtimeDiagnostics
    ) {
        public ProviderView {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
            if (runtimeState == null || runtimeState.isBlank()) throw new IllegalArgumentException("runtimeState must not be blank");
            runtimeDiagnostics = List.copyOf(Objects.requireNonNull(runtimeDiagnostics, "runtimeDiagnostics"));
        }
    }
}

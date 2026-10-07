package com.minos.application;

import com.minos.diagnostics.PublicErrorMessages;
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
    private static final String REDACTED_DIAGNOSTIC = "internal diagnostic redacted";
    /** The runtime state of a profile that was built without asking any runtime: never a state deduced blindly. */
    public static final String RUNTIME_NOT_INSPECTED = "NOT_INSPECTED";
    static final String NOT_INSPECTED_DIAGNOSTIC =
            "runtime state is not inspected by the read-only MCP status; run `minos providers` or `minos doctor`";

    private final List<IndexerProvider> providers;
    private final ProviderRuntimeManager runtimes;
    private final ProviderConformanceKit conformanceKit = new ProviderConformanceKit();

    public ProviderPlatformService(List<? extends IndexerProvider> providers, ProviderRuntimeManager runtimes) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    /** Default provider platform bound to one already-composed application. */
    public static ProviderPlatformService defaults(MinosApplication application) {
        MinosApplication app = Objects.requireNonNull(application, "application");
        return new ProviderPlatformService(app.providerCatalog().providers(), app.providerRuntimeManager());
    }

    public List<ProviderView> listProviders() {
        Map<String, ProviderRuntimeStatus> statuses = runtimes.list().stream()
                .collect(Collectors.toUnmodifiableMap(ProviderRuntimeStatus::providerId, Function.identity()));
        return providers.stream()
                .map(provider -> view(provider, statuses.get(provider.descriptor().id())))
                .sorted(java.util.Comparator.comparing(ProviderView::id))
                .toList();
    }

    /**
     * The provider profiles that need no runtime: conformance, capabilities and limitations come from the declared
     * descriptors, and the runtime state is {@value #RUNTIME_NOT_INSPECTED}. It never lists, inspects or installs a
     * runtime (no file written, no tree hashed, no process started), which is what a read-only surface needs;
     * {@link #listProviders} is the call that inspects, for {@code providers}, {@code doctor} and the Java API.
     */
    public List<ProviderView> listStaticProfiles() {
        return providers.stream()
                .map(provider -> view(provider, RUNTIME_NOT_INSPECTED, List.of(NOT_INSPECTED_DIAGNOSTIC)))
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
        return view(provider,
                status == null ? "UNMANAGED" : status.state().name(),
                status == null ? List.of("no runtime manager registered") : publicDiagnostics(status.diagnostics()));
    }

    private ProviderView view(IndexerProvider provider, String runtimeState, List<String> runtimeDiagnostics) {
        ProviderConformanceKit.ConformanceResult result = conformanceKit.evaluate(provider);
        return new ProviderView(
                result.providerId(),
                result.version(),
                result.qualification(),
                result.languages(),
                result.buildSystems(),
                result.capabilities(),
                result.scorePercent(),
                result.limitations(),
                result.operationalProfileExplicit(),
                result.qualificationPlatforms(),
                result.runtimeRequirements(),
                result.readinessBehavior(),
                result.installationBehavior(),
                result.stableIdentityBehavior(),
                result.provenanceBehavior(),
                runtimeState,
                runtimeDiagnostics
        );
    }

    static List<String> publicDiagnostics(List<String> diagnostics) {
        return List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics")).stream()
                .map(value -> PublicErrorMessages.sanitize(value, REDACTED_DIAGNOSTIC))
                .toList();
    }

    public record ProviderView(
            String id,
            String version,
            String qualification,
            List<String> languages,
            List<String> buildSystems,
            Map<String, String> capabilities,
            int conformanceScorePercent,
            List<String> limitations,
            boolean operationalProfileExplicit,
            List<String> qualificationPlatforms,
            List<String> runtimeRequirements,
            String readinessBehavior,
            String installationBehavior,
            String stableIdentityBehavior,
            String provenanceBehavior,
            String runtimeState,
            List<String> runtimeDiagnostics
    ) {
        public ProviderView {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            if (qualification == null || qualification.isBlank()) throw new IllegalArgumentException("qualification must not be blank");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            capabilities = ProviderConformanceKit.sortedCopy(Objects.requireNonNull(capabilities, "capabilities"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
            qualificationPlatforms = List.copyOf(Objects.requireNonNull(qualificationPlatforms, "qualificationPlatforms"));
            runtimeRequirements = List.copyOf(Objects.requireNonNull(runtimeRequirements, "runtimeRequirements"));
            if (readinessBehavior == null || readinessBehavior.isBlank()) throw new IllegalArgumentException("readinessBehavior must not be blank");
            if (installationBehavior == null || installationBehavior.isBlank()) throw new IllegalArgumentException("installationBehavior must not be blank");
            if (stableIdentityBehavior == null || stableIdentityBehavior.isBlank()) throw new IllegalArgumentException("stableIdentityBehavior must not be blank");
            if (provenanceBehavior == null || provenanceBehavior.isBlank()) throw new IllegalArgumentException("provenanceBehavior must not be blank");
            if (runtimeState == null || runtimeState.isBlank()) throw new IllegalArgumentException("runtimeState must not be blank");
            runtimeDiagnostics = List.copyOf(Objects.requireNonNull(runtimeDiagnostics, "runtimeDiagnostics"));
        }
    }
}

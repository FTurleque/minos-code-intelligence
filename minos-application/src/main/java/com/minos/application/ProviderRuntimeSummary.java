package com.minos.application;

import com.minos.runtime.ProviderRuntimeStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.minos.domain.Preconditions.requireText;

/**
 * One managed provider runtime as the surfaces show it: identity, version, state name, executable, diagnostics and
 * whether the baseline requires it. Returned by {@link ProviderPlatformService#runtimes()} and
 * {@link ProviderPlatformService#installRuntime(String)}, and carried by the indexing plan.
 */
public record ProviderRuntimeSummary(
        String providerId,
        String version,
        String state,
        Optional<String> executable,
        List<String> diagnostics,
        boolean requiredByDefault
) {
    public ProviderRuntimeSummary {
        providerId = requireText(providerId, "providerId");
        version = requireText(version, "version");
        state = requireText(state, "state");
        Objects.requireNonNull(executable, "executable");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    /** The summary of an engine runtime status; the state is its enumeration name. */
    public static ProviderRuntimeSummary of(ProviderRuntimeStatus status) {
        Objects.requireNonNull(status, "status");
        return new ProviderRuntimeSummary(status.providerId(), status.version(), status.state().name(),
                status.executable().map(Path::toString), status.diagnostics(), status.requiredByDefault());
    }
}

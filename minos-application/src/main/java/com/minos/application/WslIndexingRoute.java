package com.minos.application;

import com.minos.runtime.ProviderRuntimeManager;

import java.util.Optional;

/**
 * Port of the Windows route that runs scip-java in a WSL2 distribution, under the Linux sandbox, and hands back a
 * verified artifact (ADR SEC-0079).
 *
 * <p>The route is not a second orchestration: the single indexing use case
 * ({@code com.minos.application.indexing.ProjectIndexingUseCase}) keeps preparation, lease, promotion and retention,
 * and only receives other provider runtimes. They route scip-java (readiness from the WSL prerequisite probe,
 * executor run in the distribution) and delegate every other provider to the native runtimes, whose refusal of
 * scip-java stays intact.</p>
 */
@FunctionalInterface
public interface WslIndexingRoute {

    /**
     * The provider runtimes of the route for one indexing; nothing is probed or launched yet.
     *
     * @param nativeRuntimes the runtimes of this installation, which answer for every non-routed provider
     * @param distribution   the WSL distribution name, or empty for the default distribution of WSL
     */
    ProviderRuntimeManager runtimes(ProviderRuntimeManager nativeRuntimes, Optional<String> distribution);
}

package com.minos.application;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Port of the Windows route that runs the selected providers in a WSL2 distribution, under the Linux sandbox, and
 * hands back a verified artifact.
 *
 * <p>The route is not a second orchestration: it only supplies the executor decorator that the single indexing use
 * case ({@code com.minos.application.indexing.ProjectIndexingUseCase}) applies to its executors, so preparation,
 * promotion and retention stay there.</p>
 */
public interface WslIndexingRoute {

    /**
     * Returns the decorator that replaces each selected executor by one that runs in the given distribution.
     *
     * @param distribution the WSL distribution name, or empty for the default distribution of WSL
     */
    UnaryOperator<IndexerExecutor> executorDecorator(Optional<String> distribution);
}

package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import java.nio.file.Path;

/**
 * Construit la commande concrète d'un provider sans faire fuiter cette logique
 * dans l'orchestration MINOS.
 */
@FunctionalInterface
public interface IndexerProcessPlanFactory {

    IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws Exception;
}

package com.minos.orchestration;

import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;

import java.nio.file.Path;
import java.util.Objects;

record IndexingExecutionTarget(IndexerSelection selection, Path projectRelativeRoot) {
    IndexingExecutionTarget {
        Objects.requireNonNull(selection, "selection");
        projectRelativeRoot = Objects.requireNonNull(projectRelativeRoot, "projectRelativeRoot").normalize();
        if (projectRelativeRoot.isAbsolute() || projectRelativeRoot.startsWith("..")) {
            throw new IllegalArgumentException("projectRelativeRoot must stay inside project");
        }
    }
}

package com.minos.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Backend-neutral maintenance contract applied by the production indexing lifecycle. */
@FunctionalInterface
public interface StorageRetentionService {

    RetentionResult compact(UUID projectId, PersistentRetentionPolicy policy) throws IOException;

    default RetentionResult compact(UUID projectId) throws IOException {
        return compact(projectId, PersistentRetentionPolicy.DEFAULT);
    }

    static StorageRetentionService noOp() {
        return (projectId, policy) -> {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(policy, "policy");
            return new RetentionResult(0, 0, 0);
        };
    }

    record RetentionResult(
            int deletedKnowledgeSnapshots,
            int deletedFingerprintSnapshots,
            int deletedIndexingRuns
    ) {
        public RetentionResult {
            if (deletedKnowledgeSnapshots < 0 || deletedFingerprintSnapshots < 0 || deletedIndexingRuns < 0) {
                throw new IllegalArgumentException("retention deletion counts must not be negative");
            }
        }
    }
}

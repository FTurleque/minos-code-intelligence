package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Résultat explicable d'une négociation d'indexeurs.
 */
public record IndexerNegotiationResult(
        List<IndexerSelection> selections,
        Set<Language> uncoveredLanguages,
        List<IndexerEvaluation> evaluations
) {

    public IndexerNegotiationResult {
        selections = List.copyOf(Objects.requireNonNull(selections, "selections"));
        Objects.requireNonNull(uncoveredLanguages, "uncoveredLanguages");
        uncoveredLanguages = uncoveredLanguages.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(uncoveredLanguages));
        evaluations = List.copyOf(Objects.requireNonNull(evaluations, "evaluations"));
    }

    public boolean complete() {
        return uncoveredLanguages.isEmpty();
    }

    public record IndexerSelection(Language language, IndexerDescriptor indexer) {
        public IndexerSelection {
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(indexer, "indexer");
        }
    }

    public record IndexerEvaluation(
            Language language,
            String indexerId,
            EvaluationStatus status,
            Set<IndexerCapability> missingCapabilities,
            String reason
    ) {
        public IndexerEvaluation {
            Objects.requireNonNull(language, "language");
            if (indexerId == null || indexerId.isBlank()) {
                throw new IllegalArgumentException("indexerId must not be blank");
            }
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(missingCapabilities, "missingCapabilities");
            missingCapabilities = missingCapabilities.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(missingCapabilities));
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    public enum EvaluationStatus {
        SELECTED,
        REJECTED_BUILD_SYSTEM,
        REJECTED_MISSING_CAPABILITIES,
        REJECTED_EXPERIMENTAL,
        NOT_SELECTED_LOWER_PRIORITY
    }
}

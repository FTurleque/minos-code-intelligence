package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.EvaluationStatus;
import com.minos.orchestration.IndexerNegotiationResult.IndexerEvaluation;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registre fournisseur-indépendant des indexeurs connus de MINOS.
 *
 * <p>Le registre négocie un plan mais n'exécute jamais un indexeur.</p>
 */
public final class IndexerRegistry {

    private static final Comparator<IndexerDescriptor> NEGOTIATION_ORDER =
            Comparator.comparingInt(IndexerDescriptor::priority)
                    .reversed()
                    .thenComparing(IndexerDescriptor::id);

    private final Map<String, IndexerDescriptor> indexers = new LinkedHashMap<>();

    public synchronized void register(IndexerDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        IndexerDescriptor previous = indexers.putIfAbsent(descriptor.id(), descriptor);
        if (previous != null) {
            throw new IllegalArgumentException("Indexer already registered: " + descriptor.id());
        }
    }

    public synchronized void registerAll(Iterable<IndexerDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        for (IndexerDescriptor descriptor : descriptors) {
            register(descriptor);
        }
    }

    public synchronized Optional<IndexerDescriptor> find(String indexerId) {
        if (indexerId == null || indexerId.isBlank()) {
            throw new IllegalArgumentException("indexerId must not be blank");
        }
        return Optional.ofNullable(indexers.get(indexerId));
    }

    public synchronized List<IndexerDescriptor> list() {
        return indexers.values().stream()
                .sorted(Comparator.comparing(IndexerDescriptor::id))
                .toList();
    }

    public IndexerNegotiationResult negotiate(
            ProjectDiscovery discovery,
            IndexingRequirements requirements
    ) {
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(requirements, "requirements");

        List<IndexerDescriptor> candidates;
        synchronized (this) {
            candidates = indexers.values().stream()
                    .sorted(NEGOTIATION_ORDER)
                    .toList();
        }

        Map<String, IndexerSelection> selectionsByIndexer = new LinkedHashMap<>();
        List<IndexerEvaluation> evaluations = new ArrayList<>();
        EnumSet<Language> uncoveredLanguages = EnumSet.noneOf(Language.class);

        for (Language language : discovery.languages()) {
            boolean selected = false;

            for (IndexerDescriptor candidate : candidates) {
                if (!candidate.supports(language)) {
                    continue;
                }

                if (candidate.qualification() == IndexerQualification.EXPERIMENTAL
                        && !requirements.allowExperimental()) {
                    evaluations.add(new IndexerEvaluation(
                            language,
                            candidate.id(),
                            EvaluationStatus.REJECTED_EXPERIMENTAL,
                            EnumSet.noneOf(IndexerCapability.class),
                            "experimental indexer is not allowed by the requirements"
                    ));
                    continue;
                }

                if (!candidate.acceptsBuildSystems(discovery.buildSystems())) {
                    evaluations.add(new IndexerEvaluation(
                            language,
                            candidate.id(),
                            EvaluationStatus.REJECTED_BUILD_SYSTEM,
                            EnumSet.noneOf(IndexerCapability.class),
                            "detected build systems are not qualified for this indexer"
                    ));
                    continue;
                }

                EnumSet<IndexerCapability> missingCapabilities = EnumSet.noneOf(IndexerCapability.class);
                missingCapabilities.addAll(requirements.requiredCapabilities());
                missingCapabilities.removeAll(candidate.capabilities());
                if (!missingCapabilities.isEmpty()) {
                    evaluations.add(new IndexerEvaluation(
                            language,
                            candidate.id(),
                            EvaluationStatus.REJECTED_MISSING_CAPABILITIES,
                            missingCapabilities,
                            "required capabilities are missing"
                    ));
                    continue;
                }

                if (!selected) {
                    selectionsByIndexer.putIfAbsent(
                            candidate.id(),
                            new IndexerSelection(language, candidate)
                    );
                    evaluations.add(new IndexerEvaluation(
                            language,
                            candidate.id(),
                            EvaluationStatus.SELECTED,
                            EnumSet.noneOf(IndexerCapability.class),
                            "best compatible indexer by qualification constraints and priority"
                    ));
                    selected = true;
                } else {
                    evaluations.add(new IndexerEvaluation(
                            language,
                            candidate.id(),
                            EvaluationStatus.NOT_SELECTED_LOWER_PRIORITY,
                            EnumSet.noneOf(IndexerCapability.class),
                            "another compatible indexer has a higher deterministic priority"
                    ));
                }
            }

            if (!selected) {
                uncoveredLanguages.add(language);
            }
        }

        return new IndexerNegotiationResult(
                List.copyOf(selectionsByIndexer.values()),
                uncoveredLanguages,
                evaluations
        );
    }
}

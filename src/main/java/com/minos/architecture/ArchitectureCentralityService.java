package com.minos.architecture;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transforme les mesures M6.3 en rangs relatifs directionnels explicables.
 *
 * <p>Le service n'applique aucun seuil absolu et ne calcule aucun score composite.
 * Les rangs sont denses, fondés sur les compteurs de dépendances positifs ; un
 * compteur nul reste non classé avec le rang 0.</p>
 */
public final class ArchitectureCentralityService {

    public ArchitectureCentralityReport rank(ArchitectureConcentrationReport concentration) {
        Objects.requireNonNull(concentration, "concentration");

        Map<Integer, Integer> incomingRanks = denseRanks(
                concentration.modules().stream()
                        .map(ArchitectureModuleConcentration::incomingDependencyCount)
                        .toList()
        );
        Map<Integer, Integer> outgoingRanks = denseRanks(
                concentration.modules().stream()
                        .map(ArchitectureModuleConcentration::outgoingDependencyCount)
                        .toList()
        );

        List<ArchitectureModuleCentrality> modules = concentration.modules().stream()
                .sorted(Comparator.comparing(ArchitectureModuleConcentration::moduleId))
                .map(metric -> toCentrality(metric, incomingRanks, outgoingRanks))
                .toList();

        List<String> topIncoming = modules.stream()
                .filter(module -> module.incomingRank() == 1)
                .map(ArchitectureModuleCentrality::moduleId)
                .toList();
        List<String> topOutgoing = modules.stream()
                .filter(module -> module.outgoingRank() == 1)
                .map(ArchitectureModuleCentrality::moduleId)
                .toList();

        return new ArchitectureCentralityReport(
                concentration.projectId(),
                concentration.snapshotId(),
                modules.size(),
                (int) modules.stream().filter(module -> module.incomingRank() > 0).count(),
                (int) modules.stream().filter(module -> module.outgoingRank() > 0).count(),
                topIncoming,
                topOutgoing,
                modules,
                InformationNature.DERIVED,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Ranked modules relatively from directional inter-module dependency counts; "
                                + "zero-count modules remain unranked and no absolute threshold is applied",
                        null,
                        null,
                        null,
                        1.0
                ))
        );
    }

    private static Map<Integer, Integer> denseRanks(List<Integer> counts) {
        List<Integer> distinctPositiveCounts = counts.stream()
                .filter(count -> count > 0)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        Map<Integer, Integer> ranks = new HashMap<>();
        for (int index = 0; index < distinctPositiveCounts.size(); index++) {
            ranks.put(distinctPositiveCounts.get(index), index + 1);
        }
        return ranks;
    }

    private static ArchitectureModuleCentrality toCentrality(
            ArchitectureModuleConcentration metric,
            Map<Integer, Integer> incomingRanks,
            Map<Integer, Integer> outgoingRanks
    ) {
        int incomingRank = metric.incomingDependencyCount() == 0
                ? 0
                : incomingRanks.get(metric.incomingDependencyCount());
        int outgoingRank = metric.outgoingDependencyCount() == 0
                ? 0
                : outgoingRanks.get(metric.outgoingDependencyCount());
        CodeEntityRef moduleRef = new CodeEntityRef(CodeEntityType.MODULE, metric.moduleId());

        List<Evidence> evidence = new ArrayList<>(metric.evidence());
        evidence.add(new Evidence(
                EvidenceType.DERIVATION_PATH,
                "Dense directional ranks derived from dependency counts: incomingRank=" + incomingRank
                        + ", outgoingRank=" + outgoingRank,
                moduleRef,
                null,
                null,
                1.0
        ));

        return new ArchitectureModuleCentrality(
                metric.moduleId(),
                incomingRank,
                outgoingRank,
                metric.incomingDependencyCount(),
                metric.outgoingDependencyCount(),
                metric.incomingModuleCount(),
                metric.outgoingModuleCount(),
                metric.incomingShare(),
                metric.outgoingShare(),
                InformationNature.DERIVED,
                evidence
        );
    }
}

package com.minos.architecture;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Calcule des mesures descriptives de concentration depuis le graphe M6.2.
 *
 * <p>Le service ne produit aucun label de centralité ou de criticité. Il expose
 * seulement des degrés pondérés, des parts et deux indices de Herfindahl afin
 * que leur stabilité puisse être mesurée avant toute qualification sémantique.</p>
 */
public final class ArchitectureConcentrationService {

    public ArchitectureConcentrationReport analyze(
            ArchitectureOverview overview,
            ArchitectureDependencyGraph graph
    ) {
        Objects.requireNonNull(overview, "overview");
        Objects.requireNonNull(graph, "graph");
        requireSameSnapshot(overview, graph);

        Map<String, MutableModuleMetrics> metrics = new LinkedHashMap<>();
        overview.modules().stream()
                .sorted(Comparator.comparing(ArchitectureModule::id))
                .forEach(module -> {
                    MutableModuleMetrics previous = metrics.putIfAbsent(
                            module.id(),
                            new MutableModuleMetrics(module.id())
                    );
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate module id in overview: " + module.id());
                    }
                });

        int edgeDependencyCount = 0;
        for (ArchitectureModuleDependency dependency : graph.dependencies()) {
            MutableModuleMetrics source = metrics.get(dependency.sourceModuleId());
            MutableModuleMetrics target = metrics.get(dependency.targetModuleId());
            if (source == null || target == null) {
                throw new IllegalArgumentException(
                        "dependency graph references a module absent from architecture overview: "
                                + dependency.id()
                );
            }

            edgeDependencyCount = Math.addExact(edgeDependencyCount, dependency.dependencyCount());
            source.addOutgoing(dependency.targetModuleId(), dependency.dependencyCount());
            target.addIncoming(dependency.sourceModuleId(), dependency.dependencyCount());
        }

        if (edgeDependencyCount != graph.interModuleDependencyCount()) {
            throw new IllegalArgumentException(
                    "module edge dependency counts do not match interModuleDependencyCount"
            );
        }

        int denominator = graph.interModuleDependencyCount();
        List<ArchitectureModuleConcentration> moduleMetrics = metrics.values().stream()
                .map(module -> module.toResult(denominator))
                .toList();

        double incomingHerfindahl = moduleMetrics.stream()
                .mapToDouble(metric -> square(metric.incomingShare()))
                .sum();
        double outgoingHerfindahl = moduleMetrics.stream()
                .mapToDouble(metric -> square(metric.outgoingShare()))
                .sum();
        double maxIncomingShare = moduleMetrics.stream()
                .mapToDouble(ArchitectureModuleConcentration::incomingShare)
                .max()
                .orElse(0.0);
        double maxOutgoingShare = moduleMetrics.stream()
                .mapToDouble(ArchitectureModuleConcentration::outgoingShare)
                .max()
                .orElse(0.0);

        return new ArchitectureConcentrationReport(
                overview.projectId(),
                overview.snapshotId(),
                moduleMetrics.size(),
                denominator,
                incomingHerfindahl,
                outgoingHerfindahl,
                maxIncomingShare,
                maxOutgoingShare,
                moduleMetrics,
                InformationNature.DERIVED,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Computed descriptive concentration metrics from " + denominator
                                + " persisted inter-module dependency contributions",
                        null,
                        null,
                        null,
                        1.0
                ))
        );
    }

    private static void requireSameSnapshot(
            ArchitectureOverview overview,
            ArchitectureDependencyGraph graph
    ) {
        if (!overview.projectId().equals(graph.projectId())) {
            throw new IllegalArgumentException("overview and dependency graph belong to different projects");
        }
        if (!overview.snapshotId().equals(graph.snapshotId())) {
            throw new IllegalArgumentException("overview and dependency graph belong to different snapshots");
        }
    }

    private static double square(double value) {
        return value * value;
    }

    private static final class MutableModuleMetrics {
        private final String moduleId;
        private final Set<String> incomingModules = new LinkedHashSet<>();
        private final Set<String> outgoingModules = new LinkedHashSet<>();
        private int incomingDependencyCount;
        private int outgoingDependencyCount;

        private MutableModuleMetrics(String moduleId) {
            this.moduleId = moduleId;
        }

        private void addIncoming(String sourceModuleId, int count) {
            incomingDependencyCount = Math.addExact(incomingDependencyCount, count);
            incomingModules.add(sourceModuleId);
        }

        private void addOutgoing(String targetModuleId, int count) {
            outgoingDependencyCount = Math.addExact(outgoingDependencyCount, count);
            outgoingModules.add(targetModuleId);
        }

        private ArchitectureModuleConcentration toResult(int denominator) {
            double incomingShare = denominator == 0
                    ? 0.0
                    : (double) incomingDependencyCount / denominator;
            double outgoingShare = denominator == 0
                    ? 0.0
                    : (double) outgoingDependencyCount / denominator;
            CodeEntityRef module = new CodeEntityRef(CodeEntityType.MODULE, moduleId);

            return new ArchitectureModuleConcentration(
                    moduleId,
                    incomingDependencyCount,
                    outgoingDependencyCount,
                    incomingModules.size(),
                    outgoingModules.size(),
                    incomingShare,
                    outgoingShare,
                    InformationNature.DERIVED,
                    List.of(new Evidence(
                            EvidenceType.DERIVATION_PATH,
                            "Computed module concentration from persisted inter-module dependencies",
                            module,
                            null,
                            null,
                            1.0
                    ))
            );
        }
    }
}

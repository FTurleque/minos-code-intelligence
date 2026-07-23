package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compose les vues M6 déjà calculées et extrait un contexte compact par module.
 */
public final class ArchitectureIntelligenceService {

    public ArchitectureIntelligenceView compose(
            ArchitectureOverview overview,
            ArchitectureDependencyGraph dependencies,
            ArchitectureConcentrationReport concentration,
            ArchitectureCentralityReport centrality,
            ArchitectureTechnologyReport technologies
    ) {
        Objects.requireNonNull(overview, "overview");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(concentration, "concentration");
        Objects.requireNonNull(centrality, "centrality");
        Objects.requireNonNull(technologies, "technologies");

        Map<String, ArchitectureModule> modules = indexModules(overview.modules());
        Map<String, ArchitectureModuleConcentration> concentrationByModule = indexConcentration(concentration.modules());
        Map<String, ArchitectureModuleCentrality> centralityByModule = indexCentrality(centrality.modules());

        if (!modules.keySet().equals(concentrationByModule.keySet())) {
            throw new IllegalArgumentException("concentration modules must exactly match architecture overview modules");
        }
        if (!modules.keySet().equals(centralityByModule.keySet())) {
            throw new IllegalArgumentException("centrality modules must exactly match architecture overview modules");
        }

        dependencies.dependencies().forEach(edge -> {
            requireKnownModule(modules, edge.sourceModuleId(), "dependency source");
            requireKnownModule(modules, edge.targetModuleId(), "dependency target");
        });
        technologies.technologies().forEach(technology -> technology.moduleIds()
                .forEach(moduleId -> requireKnownModule(modules, moduleId, "technology module")));

        return new ArchitectureIntelligenceView(
                overview.projectId(),
                overview.projectName(),
                overview.snapshotId(),
                overview,
                dependencies,
                concentration,
                centrality,
                technologies,
                InformationNature.DERIVED,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Architecture intelligence view composes topology, dependencies, concentration, centrality "
                                + "and technologies for snapshot " + overview.snapshotId(),
                        null,
                        null,
                        null,
                        null
                ))
        );
    }

    public ArchitectureModuleContext moduleContext(
            ArchitectureIntelligenceView view,
            String moduleIdentifier
    ) {
        Objects.requireNonNull(view, "view");
        ArchitectureModule module = resolveModule(view.overview().modules(), moduleIdentifier);
        String moduleId = module.id();

        List<ArchitectureModuleDependency> incoming = view.dependencies().dependencies().stream()
                .filter(edge -> moduleId.equals(edge.targetModuleId()))
                .sorted(Comparator.comparing(ArchitectureModuleDependency::id))
                .toList();
        List<ArchitectureModuleDependency> outgoing = view.dependencies().dependencies().stream()
                .filter(edge -> moduleId.equals(edge.sourceModuleId()))
                .sorted(Comparator.comparing(ArchitectureModuleDependency::id))
                .toList();

        ArchitectureModuleConcentration concentration = view.concentration().modules().stream()
                .filter(metric -> moduleId.equals(metric.moduleId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing concentration metric for module " + moduleId));
        ArchitectureModuleCentrality centrality = view.centrality().modules().stream()
                .filter(metric -> moduleId.equals(metric.moduleId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing centrality metric for module " + moduleId));
        List<ArchitectureTechnology> technologies = view.technologies().technologies().stream()
                .filter(technology -> technology.moduleIds().contains(moduleId))
                .sorted(Comparator.comparing(ArchitectureTechnology::id))
                .toList();

        return new ArchitectureModuleContext(
                view.projectId(),
                view.snapshotId(),
                module,
                incoming,
                outgoing,
                concentration,
                centrality,
                technologies,
                InformationNature.DERIVED,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Module context assembled from architecture intelligence view for module " + moduleId,
                        null,
                        null,
                        null,
                        null
                ))
        );
    }

    private static ArchitectureModule resolveModule(List<ArchitectureModule> modules, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("module identifier must not be blank");
        }
        String normalized = identifier.trim().replace('\\', '/');

        ArchitectureModule byId = modules.stream()
                .filter(module -> normalized.equals(module.id()))
                .findFirst()
                .orElse(null);
        if (byId != null) {
            return byId;
        }

        String relativePath = ".".equals(normalized) ? "" : normalized;
        ArchitectureModule byPath = modules.stream()
                .filter(module -> relativePath.equals(module.relativePath()))
                .findFirst()
                .orElse(null);
        if (byPath != null) {
            return byPath;
        }

        List<ArchitectureModule> byName = modules.stream()
                .filter(module -> normalized.equals(module.name()))
                .toList();
        if (byName.size() == 1) {
            return byName.getFirst();
        }
        if (byName.size() > 1) {
            throw new IllegalArgumentException("ambiguous module name, use module id or relative path: " + identifier);
        }
        throw new IllegalArgumentException("unknown module: " + identifier);
    }

    private static Map<String, ArchitectureModule> indexModules(List<ArchitectureModule> modules) {
        Map<String, ArchitectureModule> result = new LinkedHashMap<>();
        for (ArchitectureModule module : modules) {
            if (result.put(module.id(), module) != null) {
                throw new IllegalArgumentException("duplicate module id in architecture overview: " + module.id());
            }
        }
        return result;
    }

    private static Map<String, ArchitectureModuleConcentration> indexConcentration(
            List<ArchitectureModuleConcentration> metrics
    ) {
        Map<String, ArchitectureModuleConcentration> result = new LinkedHashMap<>();
        for (ArchitectureModuleConcentration metric : metrics) {
            if (result.put(metric.moduleId(), metric) != null) {
                throw new IllegalArgumentException("duplicate concentration module id: " + metric.moduleId());
            }
        }
        return result;
    }

    private static Map<String, ArchitectureModuleCentrality> indexCentrality(
            List<ArchitectureModuleCentrality> metrics
    ) {
        Map<String, ArchitectureModuleCentrality> result = new LinkedHashMap<>();
        for (ArchitectureModuleCentrality metric : metrics) {
            if (result.put(metric.moduleId(), metric) != null) {
                throw new IllegalArgumentException("duplicate centrality module id: " + metric.moduleId());
            }
        }
        return result;
    }

    private static void requireKnownModule(Map<String, ArchitectureModule> modules, String moduleId, String label) {
        if (!modules.containsKey(moduleId)) {
            throw new IllegalArgumentException(label + " references unknown module: " + moduleId);
        }
    }
}
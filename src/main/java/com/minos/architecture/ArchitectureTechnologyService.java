package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Détecte uniquement les technologies déjà observées factuellement par M1.
 *
 * <p>Aucun framework, runtime ou outil n'est inféré depuis un nom, une convention
 * ou une dépendance non analysée.</p>
 */
public final class ArchitectureTechnologyService {

    public ArchitectureTechnologyReport detect(
            ProjectDiscovery discovery,
            ArchitectureOverview overview
    ) {
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(overview, "overview");

        Map<String, String> moduleIdsByPath = moduleIdsByPath(overview);
        TreeMap<TechnologyKey, MutableTechnology> technologies = new TreeMap<>();

        discovery.modules().stream()
                .sorted(Comparator.comparing(module -> portable(module.relativePath())))
                .forEach(module -> acceptModule(module, moduleIdsByPath, technologies));

        List<ArchitectureTechnology> detected = technologies.values().stream()
                .map(MutableTechnology::toResult)
                .toList();

        return new ArchitectureTechnologyReport(
                overview.projectId(),
                overview.snapshotId(),
                detected.size(),
                detected,
                InformationNature.DERIVED,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Technology report aggregates factual ProjectDiscovery observations for snapshot "
                                + overview.snapshotId(),
                        null,
                        null,
                        null,
                        null
                ))
        );
    }

    private static Map<String, String> moduleIdsByPath(ArchitectureOverview overview) {
        Map<String, String> result = new LinkedHashMap<>();
        for (ArchitectureModule module : overview.modules()) {
            String previous = result.put(module.relativePath(), module.id());
            if (previous != null) {
                throw new IllegalArgumentException(
                        "architecture overview contains duplicate module path: " + module.relativePath());
            }
        }
        return result;
    }

    private static void acceptModule(
            DiscoveredModule module,
            Map<String, String> moduleIdsByPath,
            Map<TechnologyKey, MutableTechnology> technologies
    ) {
        String relativePath = portable(module.relativePath());
        String moduleId = moduleIdsByPath.get(relativePath);
        if (moduleId == null) {
            throw new IllegalArgumentException(
                    "discovered module is absent from architecture overview: " + displayPath(relativePath));
        }

        module.sourceRoots().stream()
                .sorted(Comparator
                        .comparing((SourceRoot root) -> root.language().name())
                        .thenComparing(root -> portable(root.relativePath()))
                        .thenComparing(root -> root.kind().name()))
                .forEach(root -> technology(
                        technologies,
                        ArchitectureTechnologyCategory.LANGUAGE,
                        root.language().name()
                ).observe(
                        moduleId,
                        "Language " + root.language().name()
                                + " observed in " + root.kind().name().toLowerCase()
                                + " root '" + displayPath(portable(root.relativePath())) + "'"
                ));

        module.buildSystems().stream()
                .sorted(Comparator.comparing(Enum::name))
                .forEach(buildSystem -> technology(
                        technologies,
                        ArchitectureTechnologyCategory.BUILD_SYSTEM,
                        buildSystem.name()
                ).observe(
                        moduleId,
                        "Build system " + buildSystem.name()
                                + " observed by ProjectDiscovery for module '" + displayPath(relativePath) + "'"
                ));
    }

    private static MutableTechnology technology(
            Map<TechnologyKey, MutableTechnology> technologies,
            ArchitectureTechnologyCategory category,
            String name
    ) {
        TechnologyKey key = new TechnologyKey(category, name);
        return technologies.computeIfAbsent(key, ignored -> new MutableTechnology(category, name));
    }

    private static String stableId(ArchitectureTechnologyCategory category, String name) {
        return "technology:"
                + category.name().toLowerCase().replace('_', '-')
                + ":"
                + name.toLowerCase().replace('_', '-');
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String displayPath(String relativePath) {
        return relativePath.isEmpty() ? "." : relativePath;
    }

    private record TechnologyKey(ArchitectureTechnologyCategory category, String name)
            implements Comparable<TechnologyKey> {
        private TechnologyKey {
            Objects.requireNonNull(category, "category");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("technology name must not be blank");
            }
        }

        @Override
        public int compareTo(TechnologyKey other) {
            int categoryOrder = category.name().compareTo(other.category.name());
            return categoryOrder != 0 ? categoryOrder : name.compareTo(other.name);
        }
    }

    private static final class MutableTechnology {
        private final ArchitectureTechnologyCategory category;
        private final String name;
        private final TreeSet<String> moduleIds = new TreeSet<>();
        private final List<Evidence> evidence = new ArrayList<>();

        private MutableTechnology(ArchitectureTechnologyCategory category, String name) {
            this.category = Objects.requireNonNull(category, "category");
            this.name = Objects.requireNonNull(name, "name");
        }

        private void observe(String moduleId, String description) {
            moduleIds.add(moduleId);
            evidence.add(new Evidence(
                    EvidenceType.OTHER,
                    description,
                    null,
                    null,
                    null,
                    null
            ));
        }

        private ArchitectureTechnology toResult() {
            return new ArchitectureTechnology(
                    stableId(category, name),
                    name,
                    category,
                    List.copyOf(moduleIds),
                    InformationNature.FACTUAL,
                    List.copyOf(evidence)
            );
        }
    }
}

package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Symbol;
import com.minos.store.CodeKnowledgeSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Agrège la découverte factuelle M1 et le snapshot de connaissance M3 afin de
 * produire une première topologie d'architecture M6.
 *
 * <p>Cette étape ne nomme aucun rôle architectural. Les modules proviennent
 * directement de {@link ProjectDiscovery}; les namespaces sont dérivés des
 * racines source et des chemins de fichiers conservés dans les symboles.</p>
 */
public final class ArchitectureTopologyService {

    private static final String DEFAULT_NAMESPACE = "<default>";

    public ArchitectureOverview build(
            ProjectDiscovery discovery,
            CodeKnowledgeSnapshot snapshot
    ) {
        Objects.requireNonNull(discovery, "discovery");
        Objects.requireNonNull(snapshot, "snapshot");

        String projectId = snapshot.projectId().toString();
        ArchitectureModuleResolver resolver = new ArchitectureModuleResolver(projectId, discovery);
        Map<String, MutableModule> modules = new LinkedHashMap<>();
        discovery.modules().stream()
                .sorted(Comparator.comparing(module -> ArchitectureModuleResolver.portable(module.relativePath())))
                .forEach(module -> modules.put(
                        ArchitectureModuleResolver.portable(module.relativePath()),
                        new MutableModule(module)
                ));

        int localSymbolCount = 0;
        int externalSymbolCount = 0;
        int unassignedLocalSymbolCount = 0;

        List<Symbol> orderedSymbols = snapshot.symbols().stream()
                .sorted(Comparator.comparing(Symbol::id))
                .toList();
        for (Symbol symbol : orderedSymbols) {
            if (symbol.external()) {
                externalSymbolCount++;
                continue;
            }
            localSymbolCount++;

            ArchitectureModuleResolver.Assignment assignment = resolver.resolve(symbol).orElse(null);
            if (assignment == null) {
                unassignedLocalSymbolCount++;
                continue;
            }
            MutableModule module = modules.get(
                    ArchitectureModuleResolver.portable(assignment.module().relativePath()));
            if (module == null) {
                unassignedLocalSymbolCount++;
                continue;
            }
            module.accept(symbol, assignment);
        }

        List<ArchitectureModule> resultModules = modules.values().stream()
                .map(module -> module.toResult(projectId))
                .toList();

        return new ArchitectureOverview(
                projectId,
                discovery.name(),
                snapshot.snapshotId(),
                discovery.languages().stream().map(Enum::name).sorted().toList(),
                discovery.buildSystems().stream().map(Enum::name).sorted().toList(),
                localSymbolCount,
                externalSymbolCount,
                snapshot.relationships().size(),
                unassignedLocalSymbolCount,
                resultModules,
                InformationNature.DERIVED,
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "Architecture topology aggregates project discovery and snapshot "
                                + snapshot.snapshotId(),
                        null,
                        null,
                        null,
                        null
                ))
        );
    }

    private static String stableId(String prefix, String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
            return prefix + ":" + hash;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static final class MutableModule {
        private final DiscoveredModule module;
        private final TreeSet<String> languages = new TreeSet<>();
        private final Map<String, MutableNamespace> namespaces = new LinkedHashMap<>();
        private int symbolCount;

        private MutableModule(DiscoveredModule module) {
            this.module = Objects.requireNonNull(module, "module");
            module.sourceRoots().stream()
                    .map(root -> root.language().name())
                    .forEach(languages::add);
        }

        private void accept(Symbol symbol, ArchitectureModuleResolver.Assignment assignment) {
            symbolCount++;
            languages.add(symbol.language().toUpperCase(Locale.ROOT));

            SourceRoot sourceRoot = assignment.sourceRoot();
            Path relativeFile;
            String evidenceRoot;
            if (sourceRoot != null) {
                relativeFile = sourceRoot.relativePath().relativize(assignment.filePath());
                evidenceRoot = ArchitectureModuleResolver.portable(sourceRoot.relativePath());
            } else {
                Path modulePath = module.relativePath();
                relativeFile = ArchitectureModuleResolver.portable(modulePath).isEmpty()
                        ? assignment.filePath()
                        : modulePath.relativize(assignment.filePath());
                evidenceRoot = ArchitectureModuleResolver.portable(modulePath);
            }

            Path parent = relativeFile.getParent();
            String namespacePath = parent == null ? "" : ArchitectureModuleResolver.portable(parent);
            String namespaceName = namespacePath.isEmpty()
                    ? DEFAULT_NAMESPACE
                    : namespacePath.replace('/', '.');

            MutableNamespace namespace = namespaces.computeIfAbsent(
                    namespacePath,
                    ignored -> new MutableNamespace(
                            namespaceName,
                            namespacePath,
                            evidenceRoot,
                            ArchitectureModuleResolver.portable(assignment.filePath())
                    )
            );
            namespace.accept(symbol.language());
        }

        private ArchitectureModule toResult(String projectId) {
            String relativePath = ArchitectureModuleResolver.portable(module.relativePath());
            String moduleId = ArchitectureModuleResolver.moduleId(projectId, module.relativePath());
            List<ArchitectureNamespace> namespaceResults = namespaces.values().stream()
                    .sorted(Comparator.comparing(namespace -> namespace.relativePath))
                    .map(namespace -> namespace.toResult(moduleId))
                    .toList();

            List<String> buildSystems = module.buildSystems().stream()
                    .map(Enum::name)
                    .sorted()
                    .toList();

            return new ArchitectureModule(
                    moduleId,
                    module.name(),
                    relativePath,
                    buildSystems,
                    List.copyOf(languages),
                    module.sourceRoots().size(),
                    symbolCount,
                    namespaceResults,
                    InformationNature.FACTUAL,
                    List.of(new Evidence(
                            EvidenceType.OTHER,
                            "Module discovered at '" + (relativePath.isEmpty() ? "." : relativePath)
                                    + "' with " + module.sourceRoots().size() + " source roots",
                            null,
                            null,
                            null,
                            null
                    ))
            );
        }
    }

    private static final class MutableNamespace {
        private final String name;
        private final String relativePath;
        private final String sourceRoot;
        private final String exampleFile;
        private final TreeSet<String> languages = new TreeSet<>();
        private int symbolCount;

        private MutableNamespace(
                String name,
                String relativePath,
                String sourceRoot,
                String exampleFile
        ) {
            this.name = name;
            this.relativePath = relativePath;
            this.sourceRoot = sourceRoot;
            this.exampleFile = exampleFile;
        }

        private void accept(String language) {
            symbolCount++;
            languages.add(language.toUpperCase(Locale.ROOT));
        }

        private ArchitectureNamespace toResult(String moduleId) {
            return new ArchitectureNamespace(
                    stableId("namespace", moduleId + "\u001F" + relativePath),
                    name,
                    relativePath,
                    symbolCount,
                    List.copyOf(languages),
                    InformationNature.DERIVED,
                    List.of(new Evidence(
                            EvidenceType.DERIVATION_PATH,
                            "Namespace derived from file '" + exampleFile + "' under source root '"
                                    + (sourceRoot.isEmpty() ? "." : sourceRoot) + "'",
                            null,
                            null,
                            null,
                            null
                    ))
            );
        }
    }
}

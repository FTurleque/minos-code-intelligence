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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
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

        List<MutableModule> modules = discovery.modules().stream()
                .map(MutableModule::new)
                .toList();

        int localSymbolCount = 0;
        int externalSymbolCount = 0;
        int unassignedLocalSymbolCount = 0;

        for (Symbol symbol : snapshot.symbols()) {
            if (symbol.external()) {
                externalSymbolCount++;
                continue;
            }
            localSymbolCount++;

            Path filePath = safeRelativePath(symbol.fileId());
            if (filePath == null) {
                unassignedLocalSymbolCount++;
                continue;
            }

            MutableModule module = selectModule(filePath, modules);
            if (module == null) {
                unassignedLocalSymbolCount++;
                continue;
            }
            module.accept(symbol, filePath);
        }

        String projectId = snapshot.projectId().toString();
        List<ArchitectureModule> resultModules = modules.stream()
                .sorted(Comparator.comparing(module -> portable(module.module.relativePath())))
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

    private static MutableModule selectModule(Path filePath, List<MutableModule> modules) {
        MutableModule best = null;
        int bestScore = -1;

        for (MutableModule module : modules) {
            int score = module.sourceRootSpecificity(filePath);
            if (score > bestScore) {
                best = module;
                bestScore = score;
            }
        }
        if (bestScore >= 0) {
            return best;
        }

        best = null;
        bestScore = -1;
        for (MutableModule module : modules) {
            Path modulePath = module.module.relativePath();
            if (!startsWith(filePath, modulePath)) {
                continue;
            }
            int score = portable(modulePath).length();
            if (score > bestScore) {
                best = module;
                bestScore = score;
            }
        }
        return best;
    }

    private static Path safeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String portable = value.replace('\\', '/');
        if (portable.startsWith("/")
                || portable.startsWith("file:")
                || portable.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        try {
            Path path = Path.of(portable).normalize();
            if (path.isAbsolute() || path.startsWith("..")) {
                return null;
            }
            return path;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static boolean startsWith(Path path, Path prefix) {
        return portable(prefix).isEmpty() || path.startsWith(prefix);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
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

        private int sourceRootSpecificity(Path filePath) {
            return module.sourceRoots().stream()
                    .map(SourceRoot::relativePath)
                    .filter(root -> startsWith(filePath, root))
                    .mapToInt(root -> portable(root).length())
                    .max()
                    .orElse(-1);
        }

        private void accept(Symbol symbol, Path filePath) {
            symbolCount++;
            languages.add(symbol.language().toUpperCase());

            SourceRoot sourceRoot = bestSourceRoot(filePath);
            Path relativeFile;
            String evidenceRoot;
            if (sourceRoot != null) {
                relativeFile = sourceRoot.relativePath().relativize(filePath);
                evidenceRoot = portable(sourceRoot.relativePath());
            } else {
                Path modulePath = module.relativePath();
                relativeFile = portable(modulePath).isEmpty()
                        ? filePath
                        : modulePath.relativize(filePath);
                evidenceRoot = portable(modulePath);
            }

            Path parent = relativeFile.getParent();
            String namespacePath = parent == null ? "" : portable(parent);
            String namespaceName = namespacePath.isEmpty()
                    ? DEFAULT_NAMESPACE
                    : namespacePath.replace('/', '.');

            MutableNamespace namespace = namespaces.computeIfAbsent(
                    namespacePath,
                    ignored -> new MutableNamespace(
                            namespaceName,
                            namespacePath,
                            evidenceRoot,
                            portable(filePath)
                    )
            );
            namespace.accept(symbol.language());
        }

        private SourceRoot bestSourceRoot(Path filePath) {
            return module.sourceRoots().stream()
                    .filter(root -> startsWith(filePath, root.relativePath()))
                    .max(Comparator.comparingInt(root -> portable(root.relativePath()).length()))
                    .orElse(null);
        }

        private ArchitectureModule toResult(String projectId) {
            String relativePath = portable(module.relativePath());
            String moduleId = stableId("module", projectId + "\u001F" + relativePath);
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
            languages.add(language.toUpperCase());
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

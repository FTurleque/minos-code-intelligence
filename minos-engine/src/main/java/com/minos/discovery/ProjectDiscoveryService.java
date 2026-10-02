package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.spi.BuildSystemDetector;
import com.minos.discovery.spi.LanguageDetector;
import com.minos.discovery.spi.ProjectDetector;
import com.minos.discovery.spi.SourceRootDetector;
import com.minos.io.FileTreeOperations;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Discovers observable local project structure by composing discovery extensions.
 *
 * <p>The orchestrator contains no language/build-system branch. Adding a new
 * ecosystem is done by registering SPI implementations.</p>
 */
public final class ProjectDiscoveryService {

    private final List<ProjectDetector> projectDetectors;
    private final List<BuildSystemDetector> buildSystemDetectors;
    private final List<SourceRootDetector> sourceRootDetectors;
    private final List<LanguageDetector> languageDetectors;
    private final SourceBudgetPolicy sourceBudgetPolicy;

    public ProjectDiscoveryService() {
        this(
                DefaultDiscoveryPlugins.projectDetectors(),
                DefaultDiscoveryPlugins.buildSystemDetectors(),
                DefaultDiscoveryPlugins.sourceRootDetectors(),
                DefaultDiscoveryPlugins.languageDetectors(),
                SourceBudgetPolicy.DEFAULT
        );
    }

    public ProjectDiscoveryService(
            List<ProjectDetector> projectDetectors,
            List<BuildSystemDetector> buildSystemDetectors,
            List<SourceRootDetector> sourceRootDetectors,
            List<LanguageDetector> languageDetectors
    ) {
        this(projectDetectors, buildSystemDetectors, sourceRootDetectors, languageDetectors, SourceBudgetPolicy.DEFAULT);
    }

    public ProjectDiscoveryService(
            List<ProjectDetector> projectDetectors,
            List<BuildSystemDetector> buildSystemDetectors,
            List<SourceRootDetector> sourceRootDetectors,
            List<LanguageDetector> languageDetectors,
            SourceBudgetPolicy sourceBudgetPolicy
    ) {
        this.projectDetectors = List.copyOf(Objects.requireNonNull(projectDetectors, "projectDetectors"));
        this.buildSystemDetectors = List.copyOf(Objects.requireNonNull(buildSystemDetectors, "buildSystemDetectors"));
        this.sourceRootDetectors = List.copyOf(Objects.requireNonNull(sourceRootDetectors, "sourceRootDetectors"));
        this.languageDetectors = List.copyOf(Objects.requireNonNull(languageDetectors, "languageDetectors"));
        this.sourceBudgetPolicy = Objects.requireNonNull(sourceBudgetPolicy, "sourceBudgetPolicy");
    }

    public ProjectDiscovery discover(Path projectRoot) throws IOException {
        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
            }

            SourceBudgetPolicy.Tracker budget = sourceBudgetPolicy.tracker("project discovery");
            ProjectIgnorePolicy ignorePolicy = ProjectIgnorePolicy.load(root, budget);
            Map<Path, EnumSet<BuildSystem>> moduleRoots = discoverModuleRoots(root, ignorePolicy, budget);
            if (moduleRoots.isEmpty()) {
                moduleRoots.put(root, EnumSet.noneOf(BuildSystem.class));
            }

            List<DiscoveredModule> modules = new ArrayList<>();
            EnumSet<Language> projectLanguages = EnumSet.noneOf(Language.class);
            EnumSet<BuildSystem> projectBuildSystems = EnumSet.noneOf(BuildSystem.class);

            for (Map.Entry<Path, EnumSet<BuildSystem>> entry : moduleRoots.entrySet()) {
                Path moduleRoot = entry.getKey();
                EnumSet<BuildSystem> buildSystems = entry.getValue();
                List<SourceRoot> sourceRoots = discoverSourceRoots(root, moduleRoot, ignorePolicy);
                sourceRoots.forEach(sourceRoot -> projectLanguages.add(sourceRoot.language()));
                projectBuildSystems.addAll(buildSystems);
                modules.add(new DiscoveredModule(
                        root.relativize(moduleRoot),
                        moduleName(root, moduleRoot),
                        buildSystems,
                        sourceRoots
                ));
            }

            modules.sort(Comparator.comparing(module -> portable(module.relativePath())));
            return new ProjectDiscovery(root, projectName(root), projectLanguages, projectBuildSystems, modules);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    /** Exposes registered language classifiers for plugin/conformance diagnostics. */
    public List<LanguageDetector> languageDetectors() {
        return languageDetectors;
    }

    private Map<Path, EnumSet<BuildSystem>> discoverModuleRoots(
            Path root,
            ProjectIgnorePolicy ignorePolicy,
            SourceBudgetPolicy.Tracker budget
    ) throws IOException {
        Map<Path, EnumSet<BuildSystem>> modules = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                if (!directory.equals(root) && !FileTreeOperations.isRecursableDirectory(attributes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relative = root.relativize(directory);
                if (!directory.equals(root) && ignorePolicy.isHardIgnored(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!ignorePolicy.isIgnored(relative, true)
                        && projectDetectors.stream().anyMatch(detector -> detector.isModuleRoot(root, directory, ignorePolicy))) {
                    modules.put(directory, detectBuildSystems(root, directory, ignorePolicy));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                budget.accountTraversalEntry();
                throw exception;
            }
        });
        return modules;
    }

    private EnumSet<BuildSystem> detectBuildSystems(
            Path projectRoot,
            Path moduleRoot,
            ProjectIgnorePolicy ignorePolicy
    ) {
        EnumSet<BuildSystem> systems = EnumSet.noneOf(BuildSystem.class);
        for (BuildSystemDetector detector : buildSystemDetectors) {
            detector.detect(projectRoot, moduleRoot, ignorePolicy).ifPresent(systems::add);
        }
        return systems;
    }

    private List<SourceRoot> discoverSourceRoots(
            Path projectRoot,
            Path moduleRoot,
            ProjectIgnorePolicy ignorePolicy
    ) throws IOException {
        Map<String, SourceRoot> unique = new LinkedHashMap<>();
        for (SourceRootDetector detector : sourceRootDetectors) {
            for (SourceRoot sourceRoot : detector.detect(projectRoot, moduleRoot, ignorePolicy)) {
                String key = portable(sourceRoot.relativePath()) + "|" + sourceRoot.kind() + "|" + sourceRoot.language();
                unique.putIfAbsent(key, sourceRoot);
            }
        }
        return unique.values().stream()
                .sorted(Comparator
                        .comparing((SourceRoot value) -> portable(value.relativePath()))
                        .thenComparing(value -> value.kind().name())
                        .thenComparing(value -> value.language().name()))
                .toList();
    }

    private static String projectName(Path root) {
        Path fileName = root.getFileName();
        return fileName == null ? root.toString() : fileName.toString();
    }

    private static String moduleName(Path projectRoot, Path moduleRoot) {
        if (projectRoot.equals(moduleRoot)) {
            return projectName(projectRoot);
        }
        Path fileName = moduleRoot.getFileName();
        return fileName == null ? portable(projectRoot.relativize(moduleRoot)) : fileName.toString();
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}

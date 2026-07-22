package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;

import java.io.IOException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Découvre la structure locale observable d'un projet sans invoquer d'indexeur.
 */
public final class ProjectDiscoveryService {

    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git",
            ".idea",
            ".minos-m0",
            "node_modules",
            "target",
            "dist",
            "out"
    );

    public ProjectDiscovery discover(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }

        Map<Path, EnumSet<BuildSystem>> moduleRoots = discoverModuleRoots(root);
        if (moduleRoots.isEmpty()) {
            moduleRoots.put(root, EnumSet.noneOf(BuildSystem.class));
        }

        List<DiscoveredModule> modules = new ArrayList<>();
        EnumSet<Language> projectLanguages = EnumSet.noneOf(Language.class);
        EnumSet<BuildSystem> projectBuildSystems = EnumSet.noneOf(BuildSystem.class);

        for (Map.Entry<Path, EnumSet<BuildSystem>> entry : moduleRoots.entrySet()) {
            Path moduleRoot = entry.getKey();
            EnumSet<BuildSystem> buildSystems = entry.getValue();
            List<SourceRoot> sourceRoots = discoverSourceRoots(root, moduleRoot);

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

        return new ProjectDiscovery(
                root,
                projectName(root),
                projectLanguages,
                projectBuildSystems,
                modules
        );
    }

    private static Map<Path, EnumSet<BuildSystem>> discoverModuleRoots(Path root) throws IOException {
        Map<Path, EnumSet<BuildSystem>> modules = new LinkedHashMap<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root) && isIgnoredDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (hasModuleMarker(directory)) {
                    modules.put(directory, detectBuildSystems(directory));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return modules;
    }

    private static boolean hasModuleMarker(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("package.json"));
    }

    private static EnumSet<BuildSystem> detectBuildSystems(Path directory) {
        EnumSet<BuildSystem> systems = EnumSet.noneOf(BuildSystem.class);
        if (Files.isRegularFile(directory.resolve("pom.xml"))) {
            systems.add(BuildSystem.MAVEN);
        }
        if (Files.isRegularFile(directory.resolve("package-lock.json"))) {
            systems.add(BuildSystem.NPM);
        }
        return systems;
    }

    private static List<SourceRoot> discoverSourceRoots(Path projectRoot, Path moduleRoot) throws IOException {
        List<SourceRoot> roots = new ArrayList<>();

        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("src/main/java"),
                SourceRootKind.SOURCE,
                Language.JAVA,
                Set.of(".java")
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("src/test/java"),
                SourceRootKind.TEST,
                Language.JAVA,
                Set.of(".java")
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("src"),
                SourceRootKind.SOURCE,
                Language.TYPESCRIPT,
                Set.of(".ts", ".tsx")
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("test"),
                SourceRootKind.TEST,
                Language.TYPESCRIPT,
                Set.of(".ts", ".tsx")
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("tests"),
                SourceRootKind.TEST,
                Language.TYPESCRIPT,
                Set.of(".ts", ".tsx")
        );

        roots.sort(Comparator
                .comparing((SourceRoot root) -> portable(root.relativePath()))
                .thenComparing(root -> root.kind().name())
                .thenComparing(root -> root.language().name()));
        return roots;
    }

    private static void addLanguageRootIfPresent(
            List<SourceRoot> roots,
            Path projectRoot,
            Path candidate,
            SourceRootKind kind,
            Language language,
            Set<String> extensions
    ) throws IOException {
        if (Files.isDirectory(candidate) && containsFileWithExtension(candidate, extensions)) {
            roots.add(new SourceRoot(projectRoot.relativize(candidate), kind, language));
        }
    }

    private static boolean containsFileWithExtension(Path root, Set<String> extensions) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !hasIgnoredSegment(root, path))
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .anyMatch(name -> extensions.stream().anyMatch(name::endsWith));
        }
    }

    private static boolean hasIgnoredSegment(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (IGNORED_DIRECTORY_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIgnoredDirectory(Path directory) {
        Path name = directory.getFileName();
        return name != null && IGNORED_DIRECTORY_NAMES.contains(name.toString());
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

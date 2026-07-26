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

    public ProjectDiscovery discover(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot must be an existing directory: " + projectRoot);
        }

        ProjectIgnorePolicy ignorePolicy = ProjectIgnorePolicy.load(root);
        Map<Path, EnumSet<BuildSystem>> moduleRoots = discoverModuleRoots(root, ignorePolicy);
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

        return new ProjectDiscovery(
                root,
                projectName(root),
                projectLanguages,
                projectBuildSystems,
                modules
        );
    }

    private static Map<Path, EnumSet<BuildSystem>> discoverModuleRoots(
            Path root,
            ProjectIgnorePolicy ignorePolicy
    ) throws IOException {
        Map<Path, EnumSet<BuildSystem>> modules = new LinkedHashMap<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Path relative = root.relativize(directory);
                if (!directory.equals(root) && ignorePolicy.isHardIgnored(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (!ignorePolicy.isIgnored(relative, true)
                        && hasModuleMarker(root, directory, ignorePolicy)) {
                    modules.put(directory, detectBuildSystems(root, directory, ignorePolicy));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return modules;
    }

    private static boolean hasModuleMarker(
            Path projectRoot,
            Path directory,
            ProjectIgnorePolicy ignorePolicy
    ) {
        return isVisibleFile(projectRoot, directory.resolve("pom.xml"), ignorePolicy)
                || isVisibleFile(projectRoot, directory.resolve("package.json"), ignorePolicy);
    }

    private static EnumSet<BuildSystem> detectBuildSystems(
            Path projectRoot,
            Path directory,
            ProjectIgnorePolicy ignorePolicy
    ) {
        EnumSet<BuildSystem> systems = EnumSet.noneOf(BuildSystem.class);
        if (isVisibleFile(projectRoot, directory.resolve("pom.xml"), ignorePolicy)) {
            systems.add(BuildSystem.MAVEN);
        }
        if (isVisibleFile(projectRoot, directory.resolve("package-lock.json"), ignorePolicy)) {
            systems.add(BuildSystem.NPM);
        }
        return systems;
    }

    private static boolean isVisibleFile(
            Path projectRoot,
            Path file,
            ProjectIgnorePolicy ignorePolicy
    ) {
        return Files.isRegularFile(file)
                && !ignorePolicy.isIgnored(projectRoot.relativize(file), false);
    }

    private static List<SourceRoot> discoverSourceRoots(
            Path projectRoot,
            Path moduleRoot,
            ProjectIgnorePolicy ignorePolicy
    ) throws IOException {
        List<SourceRoot> roots = new ArrayList<>();

        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("src/main/java"),
                SourceRootKind.SOURCE,
                Language.JAVA,
                Set.of(".java"),
                ignorePolicy
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("src/test/java"),
                SourceRootKind.TEST,
                Language.JAVA,
                Set.of(".java"),
                ignorePolicy
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("src"),
                SourceRootKind.SOURCE,
                Language.TYPESCRIPT,
                Set.of(".ts", ".tsx"),
                ignorePolicy
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("test"),
                SourceRootKind.TEST,
                Language.TYPESCRIPT,
                Set.of(".ts", ".tsx"),
                ignorePolicy
        );
        addLanguageRootIfPresent(
                roots,
                projectRoot,
                moduleRoot.resolve("tests"),
                SourceRootKind.TEST,
                Language.TYPESCRIPT,
                Set.of(".ts", ".tsx"),
                ignorePolicy
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
            Set<String> extensions,
            ProjectIgnorePolicy ignorePolicy
    ) throws IOException {
        Path relativeCandidate = projectRoot.relativize(candidate);
        if (Files.isDirectory(candidate)
                && !ignorePolicy.isIgnored(relativeCandidate, true)
                && containsVisibleFileWithExtension(projectRoot, candidate, extensions, ignorePolicy)) {
            roots.add(new SourceRoot(relativeCandidate, kind, language));
        }
    }

    private static boolean containsVisibleFileWithExtension(
            Path projectRoot,
            Path root,
            Set<String> extensions,
            ProjectIgnorePolicy ignorePolicy
    ) throws IOException {
        boolean[] found = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root)
                        && ignorePolicy.isHardIgnored(projectRoot.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                Path relative = projectRoot.relativize(file);
                if (!ignorePolicy.isIgnored(relative, false)) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (extensions.stream().anyMatch(name::endsWith)) {
                        found[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
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

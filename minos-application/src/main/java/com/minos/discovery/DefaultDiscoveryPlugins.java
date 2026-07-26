package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.discovery.spi.BuildSystemDetector;
import com.minos.discovery.spi.LanguageDetector;
import com.minos.discovery.spi.ProjectDetector;
import com.minos.discovery.spi.SourceRootDetector;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Built-in discovery extensions. The orchestration service only consumes the SPI lists. */
public final class DefaultDiscoveryPlugins {

    private DefaultDiscoveryPlugins() {
    }

    public static List<ProjectDetector> projectDetectors() {
        return List.of(
                markerProject("pom.xml"),
                markerProject("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
                markerProject("package.json"),
                markerProject("pyproject.toml", "setup.py")
        );
    }

    public static List<BuildSystemDetector> buildSystemDetectors() {
        return List.of(
                markerBuildSystem(BuildSystem.MAVEN, "pom.xml"),
                markerBuildSystem(BuildSystem.GRADLE,
                        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
                inheritedWorkspaceBuildSystem(BuildSystem.PNPM, "pnpm-lock.yaml", "pnpm-workspace.yaml"),
                inheritedWorkspaceBuildSystem(BuildSystem.YARN, "yarn.lock"),
                inheritedWorkspaceBuildSystem(BuildSystem.NPM, "package-lock.json")
        );
    }

    public static List<LanguageDetector> languageDetectors() {
        return List.of(
                extensionLanguage(Language.JAVA, ".java"),
                extensionLanguage(Language.KOTLIN, ".kt", ".kts"),
                extensionLanguage(Language.TYPESCRIPT, ".ts", ".tsx"),
                extensionLanguage(Language.PYTHON, ".py")
        );
    }

    public static List<SourceRootDetector> sourceRootDetectors() {
        return List.of(
                conventionalRoot("src/main/java", SourceRootKind.SOURCE, Language.JAVA, ".java"),
                conventionalRoot("src/test/java", SourceRootKind.TEST, Language.JAVA, ".java"),
                conventionalRoot("src/main/kotlin", SourceRootKind.SOURCE, Language.KOTLIN, ".kt", ".kts"),
                conventionalRoot("src/test/kotlin", SourceRootKind.TEST, Language.KOTLIN, ".kt", ".kts"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.TYPESCRIPT, ".ts", ".tsx"),
                conventionalRoot("test", SourceRootKind.TEST, Language.TYPESCRIPT, ".ts", ".tsx"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.TYPESCRIPT, ".ts", ".tsx"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.PYTHON, ".py"),
                conventionalRoot("test", SourceRootKind.TEST, Language.PYTHON, ".py"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.PYTHON, ".py")
        );
    }

    private static ProjectDetector markerProject(String... markers) {
        List<String> names = List.of(markers);
        return (projectRoot, directory, ignorePolicy) -> names.stream()
                .map(directory::resolve)
                .anyMatch(file -> visibleFile(projectRoot, file, ignorePolicy));
    }

    private static BuildSystemDetector markerBuildSystem(BuildSystem system, String... markers) {
        List<String> names = List.of(markers);
        return (projectRoot, moduleRoot, ignorePolicy) -> names.stream()
                .map(moduleRoot::resolve)
                .anyMatch(file -> visibleFile(projectRoot, file, ignorePolicy))
                ? Optional.of(system)
                : Optional.empty();
    }

    private static BuildSystemDetector inheritedWorkspaceBuildSystem(BuildSystem system, String... markers) {
        List<String> names = List.of(markers);
        return (projectRoot, moduleRoot, ignorePolicy) -> {
            Path current = moduleRoot.toAbsolutePath().normalize();
            Path root = projectRoot.toAbsolutePath().normalize();
            while (current != null && current.startsWith(root)) {
                Path directory = current;
                boolean present = names.stream()
                        .map(directory::resolve)
                        .anyMatch(file -> visibleFile(root, file, ignorePolicy));
                if (present) {
                    return Optional.of(system);
                }
                if (current.equals(root)) {
                    break;
                }
                current = current.getParent();
            }
            return Optional.empty();
        };
    }

    private static LanguageDetector extensionLanguage(Language language, String... extensions) {
        Set<String> values = Set.of(extensions);
        return file -> {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            return values.stream().anyMatch(name::endsWith) ? Optional.of(language) : Optional.empty();
        };
    }

    private static SourceRootDetector conventionalRoot(
            String relative,
            SourceRootKind kind,
            Language language,
            String... extensions
    ) {
        Set<String> values = Set.of(extensions);
        return (projectRoot, moduleRoot, ignorePolicy) -> {
            Path candidate = moduleRoot.resolve(relative);
            Path projectRelative = projectRoot.relativize(candidate);
            if (!Files.isDirectory(candidate)
                    || ignorePolicy.isIgnored(projectRelative, true)
                    || !containsVisibleExtension(projectRoot, candidate, values, ignorePolicy)) {
                return List.of();
            }
            return List.of(new SourceRoot(projectRelative, kind, language));
        };
    }

    private static boolean containsVisibleExtension(
            Path projectRoot,
            Path sourceRoot,
            Set<String> extensions,
            ProjectIgnorePolicy ignorePolicy
    ) throws IOException {
        boolean[] found = {false};
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (!directory.equals(sourceRoot)
                        && ignorePolicy.isHardIgnored(projectRoot.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
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

    private static boolean visibleFile(Path projectRoot, Path file, ProjectIgnorePolicy ignorePolicy) {
        return Files.isRegularFile(file)
                && !ignorePolicy.isIgnored(projectRoot.relativize(file), false);
    }
}

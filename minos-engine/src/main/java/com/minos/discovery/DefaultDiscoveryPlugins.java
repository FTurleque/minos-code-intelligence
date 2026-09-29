package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.discovery.spi.BuildSystemDetector;
import com.minos.discovery.spi.LanguageDetector;
import com.minos.discovery.spi.ProjectDetector;
import com.minos.discovery.spi.SourceRootDetector;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.FileTreeOperations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Built-in discovery extensions. The orchestration service only consumes the SPI lists. */
public final class DefaultDiscoveryPlugins {
    private static final String JAVA_EXTENSION = ".java";

    private DefaultDiscoveryPlugins() { }

    public static List<ProjectDetector> projectDetectors() {
        return List.of(
                markerProject("pom.xml"),
                markerProject("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
                markerProject("package.json"),
                markerProject("pyproject.toml", "setup.py"),
                markerProject("CMakeLists.txt"),
                extensionMarkerProject(".csproj", ".sln"),
                markerProject("go.mod", "go.work"),
                markerProject("Cargo.toml")
        );
    }

    public static List<BuildSystemDetector> buildSystemDetectors() {
        return List.of(
                markerBuildSystem(BuildSystem.MAVEN, "pom.xml"),
                markerBuildSystem(BuildSystem.GRADLE,
                        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
                inheritedWorkspaceBuildSystem(BuildSystem.PNPM, "pnpm-lock.yaml", "pnpm-workspace.yaml"),
                inheritedWorkspaceBuildSystem(BuildSystem.YARN, "yarn.lock"),
                inheritedWorkspaceBuildSystem(BuildSystem.NPM, "package-lock.json"),
                markerBuildSystem(BuildSystem.CMAKE, "CMakeLists.txt"),
                extensionMarkerBuildSystem(BuildSystem.DOTNET, ".csproj", ".sln"),
                markerBuildSystem(BuildSystem.GO_MODULE, "go.mod", "go.work"),
                markerBuildSystem(BuildSystem.CARGO, "Cargo.toml")
        );
    }

    public static List<LanguageDetector> languageDetectors() {
        return List.of(
                extensionLanguage(Language.JAVA, JAVA_EXTENSION),
                extensionLanguage(Language.KOTLIN, ".kt", ".kts"),
                extensionLanguage(Language.TYPESCRIPT, ".ts", ".tsx"),
                extensionLanguage(Language.PYTHON, ".py"),
                extensionLanguage(Language.C, ".c", ".h"),
                extensionLanguage(Language.CPP, ".cc", ".cpp", ".cxx", ".hh", ".hpp", ".hxx"),
                extensionLanguage(Language.CSHARP, ".cs"),
                extensionLanguage(Language.GO, ".go"),
                extensionLanguage(Language.RUST, ".rs")
        );
    }

    public static List<SourceRootDetector> sourceRootDetectors() {
        return List.of(
                conventionalRoot("src/main/java", SourceRootKind.SOURCE, Language.JAVA, JAVA_EXTENSION),
                conventionalRoot("src/test/java", SourceRootKind.TEST, Language.JAVA, JAVA_EXTENSION),
                conventionalRoot("src/main/kotlin", SourceRootKind.SOURCE, Language.KOTLIN, ".kt", ".kts"),
                conventionalRoot("src/test/kotlin", SourceRootKind.TEST, Language.KOTLIN, ".kt", ".kts"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.TYPESCRIPT, ".ts", ".tsx"),
                conventionalRoot("test", SourceRootKind.TEST, Language.TYPESCRIPT, ".ts", ".tsx"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.TYPESCRIPT, ".ts", ".tsx"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.PYTHON, ".py"),
                conventionalRoot("test", SourceRootKind.TEST, Language.PYTHON, ".py"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.PYTHON, ".py"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.C, ".c", ".h"),
                conventionalRoot("include", SourceRootKind.SOURCE, Language.C, ".h"),
                conventionalRoot("test", SourceRootKind.TEST, Language.C, ".c", ".h"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.C, ".c", ".h"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.CPP, ".cc", ".cpp", ".cxx", ".hh", ".hpp", ".hxx"),
                conventionalRoot("include", SourceRootKind.SOURCE, Language.CPP, ".hh", ".hpp", ".hxx"),
                conventionalRoot("test", SourceRootKind.TEST, Language.CPP, ".cc", ".cpp", ".cxx"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.CPP, ".cc", ".cpp", ".cxx"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.CSHARP, ".cs"),
                conventionalRoot("test", SourceRootKind.TEST, Language.CSHARP, ".cs"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.CSHARP, ".cs"),
                conventionalRoot(".", SourceRootKind.SOURCE, Language.GO, ".go"),
                conventionalRoot("src", SourceRootKind.SOURCE, Language.RUST, ".rs"),
                conventionalRoot("tests", SourceRootKind.TEST, Language.RUST, ".rs")
        );
    }

    private static ProjectDetector markerProject(String... markers) {
        List<String> names = List.of(markers);
        return (projectRoot, directory, ignorePolicy) -> names.stream()
                .map(directory::resolve)
                .anyMatch(file -> visibleFile(projectRoot, file, ignorePolicy));
    }

    private static ProjectDetector extensionMarkerProject(String... suffixes) {
        Set<String> values = normalizedSuffixes(suffixes);
        return (projectRoot, directory, ignorePolicy) -> containsVisibleMarkerExtension(
                projectRoot, directory, values, ignorePolicy);
    }

    private static BuildSystemDetector markerBuildSystem(BuildSystem system, String... markers) {
        List<String> names = List.of(markers);
        return (projectRoot, moduleRoot, ignorePolicy) -> names.stream()
                .map(moduleRoot::resolve)
                .anyMatch(file -> visibleFile(projectRoot, file, ignorePolicy))
                ? Optional.of(system)
                : Optional.empty();
    }

    private static BuildSystemDetector extensionMarkerBuildSystem(BuildSystem system, String... suffixes) {
        Set<String> values = normalizedSuffixes(suffixes);
        return (projectRoot, moduleRoot, ignorePolicy) -> containsVisibleMarkerExtension(
                projectRoot, moduleRoot, values, ignorePolicy)
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
                if (present) return Optional.of(system);
                if (current.equals(root)) break;
                current = current.getParent();
            }
            return Optional.empty();
        };
    }

    private static LanguageDetector extensionLanguage(Language language, String... extensions) {
        Set<String> values = normalizedSuffixes(extensions);
        return file -> {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            return values.stream().anyMatch(name::endsWith) ? Optional.of(language) : Optional.empty();
        };
    }

    private static SourceRootDetector conventionalRoot(
            String relative, SourceRootKind kind, Language language, String... extensions) {
        Set<String> values = normalizedSuffixes(extensions);
        return (projectRoot, moduleRoot, ignorePolicy) -> {
            Path candidate = moduleRoot.resolve(relative).normalize();
            Path projectRelative = projectRoot.relativize(candidate);
            if (!isPhysicalDirectory(projectRoot, candidate)
                    || ignorePolicy.isIgnored(projectRelative, true)
                    || !containsVisibleExtension(projectRoot, candidate, values, ignorePolicy)) {
                return List.of();
            }
            return List.of(new SourceRoot(projectRelative, kind, language));
        };
    }

    private static Set<String> normalizedSuffixes(String... suffixes) {
        return java.util.Arrays.stream(suffixes)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean containsVisibleMarkerExtension(
            Path projectRoot, Path directory, Set<String> suffixes, ProjectIgnorePolicy ignorePolicy) {
        if (!isPhysicalDirectory(projectRoot, directory)) return false;
        try (var entries = Files.list(directory)) {
            return entries.filter(file -> visibleFile(projectRoot, file, ignorePolicy))
                    .map(file -> file.getFileName().toString().toLowerCase(Locale.ROOT))
                    .anyMatch(name -> suffixes.stream().anyMatch(name::endsWith));
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean containsVisibleExtension(
            Path projectRoot, Path sourceRoot, Set<String> extensions, ProjectIgnorePolicy ignorePolicy)
            throws IOException {
        if (!sourceRoot.toAbsolutePath().normalize().startsWith(projectRoot.toAbsolutePath().normalize())) {
            throw new IOException("source root escapes project root");
        }
        return ignorePolicy.containsVisibleExtension(sourceRoot, extensions);
    }

    private static boolean visibleFile(Path projectRoot, Path file, ProjectIgnorePolicy ignorePolicy) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path candidate = file.toAbsolutePath().normalize();
        if (candidate.equals(root) || !candidate.startsWith(root)) return false;
        Path relative = root.relativize(candidate);
        try (var ignored = ConfinedFileOpener.openConfinedRegularFile(root, relative)) {
            return !ignorePolicy.isIgnored(relative, false);
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    private static boolean isPhysicalDirectory(Path projectRoot, Path directory) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path candidate = directory.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) return false;
        try {
            BasicFileAttributes rootAttributes =
                    Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!FileTreeOperations.isRecursableDirectory(rootAttributes)) return false;
            Path current = root;
            Path relative = root.relativize(candidate);
            for (int index = 0; index < relative.getNameCount(); index++) {
                current = current.resolve(relative.getName(index));
                BasicFileAttributes attributes =
                        Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!FileTreeOperations.isRecursableDirectory(attributes)) return false;
            }
            return true;
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }
}

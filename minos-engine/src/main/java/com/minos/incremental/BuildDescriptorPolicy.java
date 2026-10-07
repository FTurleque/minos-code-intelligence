package com.minos.incremental;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Data-driven policy defining which visible files change a project's build definition fingerprint.
 *
 * <p>The fingerprint service receives this policy rather than branching on ecosystem names.
 * Additional provider/discovery extensions can supply a different policy without changing the service.</p>
 */
public final class BuildDescriptorPolicy {
    private static final Set<String> M17_DEFAULT_FILE_NAMES = Set.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "package.json",
            "package-lock.json",
            "pnpm-lock.yaml",
            "pnpm-workspace.yaml",
            "yarn.lock",
            "pyproject.toml",
            "setup.py"
    );
    private static final Set<String> M24_ADDITIONAL_FILE_NAMES = Set.of(
            "CMakeLists.txt",
            "compile_commands.json",
            "go.mod",
            "go.sum",
            "go.work",
            "Cargo.toml",
            "Cargo.lock"
    );

    private final Set<String> fileNames;

    public BuildDescriptorPolicy(Set<String> fileNames) {
        Objects.requireNonNull(fileNames, "fileNames");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String fileName : fileNames) {
            if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
                throw new IllegalArgumentException("build descriptor names must be non-blank file names");
            }
            normalized.add(fileName);
        }
        this.fileNames = Set.copyOf(normalized);
    }

    /** Historical M17 policy retained for regression fixtures that explicitly request it. */
    public static BuildDescriptorPolicy m17Defaults() {
        return new BuildDescriptorPolicy(M17_DEFAULT_FILE_NAMES);
    }

    /** Current default policy through M24. */
    public static BuildDescriptorPolicy m24Defaults() {
        LinkedHashSet<String> values = new LinkedHashSet<>(M17_DEFAULT_FILE_NAMES);
        values.addAll(M24_ADDITIONAL_FILE_NAMES);
        return new BuildDescriptorPolicy(values);
    }

    public boolean isBuildDescriptor(Path relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        Path fileName = relativePath.getFileName();
        if (fileName != null && fileNames.contains(fileName.toString())) {
            return true;
        }
        String name = fileName == null ? "" : fileName.toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".csproj") || name.endsWith(".sln");
    }

    public Set<String> fileNames() {
        return fileNames;
    }
}

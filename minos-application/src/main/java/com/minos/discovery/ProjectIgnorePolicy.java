package com.minos.discovery;

import com.minos.source.ProjectIgnoreRules;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Operation-scoped ignore policy and visible-file inventory for project discovery. */
public final class ProjectIgnorePolicy {

    private final Path root;
    private final SourceBudgetPolicy.Tracker budget;
    private final ProjectIgnoreRules rules;
    private final Set<Path> accountedRegularFiles = new HashSet<>();
    private final Map<Path, Set<String>> visibleFileNamesByRoot = new HashMap<>();

    private ProjectIgnorePolicy(
            Path root,
            SourceBudgetPolicy.Tracker budget,
            ProjectIgnoreRules rules
    ) {
        this.root = root;
        this.budget = budget;
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public static ProjectIgnorePolicy load(Path projectRoot) throws IOException {
        return load(projectRoot, null);
    }

    static ProjectIgnorePolicy load(Path projectRoot, SourceBudgetPolicy.Tracker budget) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        return new ProjectIgnorePolicy(root, budget, ProjectIgnoreRules.load(root));
    }

    public boolean isIgnored(Path relativePath, boolean directory) {
        Path normalized = normalizeRelative(relativePath);
        accountTraversal();
        boolean ignored = rules.isIgnored(normalized, directory);
        if (!directory && !ignored) accountRegularFile(normalized);
        return ignored;
    }

    public boolean isHardIgnored(Path relativePath) {
        Path normalized = normalizeRelative(relativePath);
        accountTraversal();
        return rules.isHardIgnored(normalized);
    }

    /**
     * Returns whether a source root contains a visible matching file. The complete visible-name
     * inventory of a root is built once per discovery operation and reused by every language detector.
     */
    boolean containsVisibleExtension(Path sourceRoot, Set<String> extensions) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(extensions, "extensions");
        Path normalized = sourceRoot.toAbsolutePath().normalize();
        Set<String> names;
        synchronized (visibleFileNamesByRoot) {
            names = visibleFileNamesByRoot.get(normalized);
        }
        if (names == null) {
            Set<String> scanned = scanVisibleFileNames(normalized);
            synchronized (visibleFileNamesByRoot) {
                names = visibleFileNamesByRoot.computeIfAbsent(normalized, ignored -> scanned);
            }
        }
        for (String name : names) {
            for (String extension : extensions) {
                if (name.endsWith(extension)) return true;
            }
        }
        return false;
    }

    private Set<String> scanVisibleFileNames(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) return Set.of();
        Set<String> names = new HashSet<>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (!directory.equals(sourceRoot) && isHardIgnored(root.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = root.relativize(file);
                if (!isIgnored(relative, false)) {
                    names.add(file.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return Set.copyOf(names);
    }

    private void accountTraversal() {
        if (budget == null) return;
        try {
            budget.accountTraversalEntry();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void accountRegularFile(Path relative) {
        if (budget == null || !accountedRegularFiles.add(relative)) return;
        try {
            Path file = root.resolve(relative).normalize();
            if (file.startsWith(root) && Files.isRegularFile(file)) {
                budget.accountRegularFile(Files.size(file));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path normalizeRelative(Path path) {
        Objects.requireNonNull(path, "relativePath");
        if (path.isAbsolute()) throw new IllegalArgumentException("relativePath must be relative");
        Path normalized = path.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException("relativePath must stay inside the project root");
        }
        return normalized;
    }
}

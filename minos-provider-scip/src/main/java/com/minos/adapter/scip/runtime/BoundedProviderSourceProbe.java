package com.minos.adapter.scip.runtime;

import com.minos.source.ProjectIgnoreRules;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Predicate;

/** Shared bounded, ignore-aware source probe used before starting managed providers. */
final class BoundedProviderSourceProbe {

    private BoundedProviderSourceProbe() {
    }

    static boolean contains(
            Path root,
            int maxDepth,
            String boundary,
            Predicate<String> fileNamePredicate
    ) throws IOException {
        return contains(root, maxDepth, boundary, fileNamePredicate, SourceBudgetPolicy.DEFAULT);
    }

    static boolean contains(
            Path root,
            int maxDepth,
            String boundary,
            Predicate<String> fileNamePredicate,
            SourceBudgetPolicy budgetPolicy
    ) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) return false;
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
        Predicate<String> predicate = Objects.requireNonNull(fileNamePredicate, "fileNamePredicate");
        ProjectIgnoreRules ignoreRules = ProjectIgnoreRules.load(normalizedRoot);
        SourceBudgetPolicy.Tracker budget = Objects.requireNonNull(budgetPolicy, "budgetPolicy")
                .tracker(boundary);
        boolean[] found = {false};

        Files.walkFileTree(normalizedRoot, EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                            throws IOException {
                        budget.accountTraversalEntry();
                        if (!directory.equals(normalizedRoot)
                                && ignoreRules.isHardIgnored(normalizedRoot.relativize(directory))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        budget.accountTraversalEntry();
                        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path relative = normalizedRoot.relativize(file);
                        if (ignoreRules.isIgnored(relative, false)) {
                            return FileVisitResult.CONTINUE;
                        }
                        budget.accountRegularFile(attributes.size());
                        if (predicate.test(file.getFileName().toString())) {
                            found[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                        budget.accountTraversalEntry();
                        throw exception;
                    }
                });
        return found[0];
    }
}

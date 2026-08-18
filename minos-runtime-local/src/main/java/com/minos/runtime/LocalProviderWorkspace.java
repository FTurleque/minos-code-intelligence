package com.minos.runtime;

import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.ProviderId;
import com.minos.source.ProjectIgnoreRules;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Objects;

/**
 * Bounded ephemeral copy used for local provider execution.
 *
 * <p>Providers are untrusted and must never receive the registered project tree as a writable
 * working directory. This boundary mirrors the distributed worker copy contract: only regular,
 * non-ignored source entries are copied, symbolic links and special files are rejected, source
 * cardinality/bytes are bounded, and the whole copy is reclaimed after execution.</p>
 */
final class LocalProviderWorkspace implements AutoCloseable {

    private final Path workspacesRoot;
    private final Path providerRoot;
    private final Path workspaceRoot;
    private final IndexingExecutionRequest isolatedRequest;

    private LocalProviderWorkspace(
            Path workspacesRoot,
            Path providerRoot,
            Path workspaceRoot,
            IndexingExecutionRequest isolatedRequest
    ) {
        this.workspacesRoot = workspacesRoot;
        this.providerRoot = providerRoot;
        this.workspaceRoot = workspaceRoot;
        this.isolatedRequest = isolatedRequest;
    }

    static LocalProviderWorkspace create(Path minosHome, IndexingExecutionRequest request) throws IOException {
        return create(minosHome, request, SourceBudgetPolicy.DEFAULT);
    }

    static LocalProviderWorkspace create(
            Path minosHome,
            IndexingExecutionRequest request,
            SourceBudgetPolicy budgetPolicy
    ) throws IOException {
        Objects.requireNonNull(request, "request");
        SourceBudgetPolicy policy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        Path workspacesRoot = home.resolve("local-provider-workspaces").toAbsolutePath().normalize();
        PrivateLocalStorage.ensurePrivateDirectory(workspacesRoot);

        Path providerRoot = workspacesRoot
                .resolve(request.runId().toString())
                .resolve(ProviderId.require(request.selection().indexer().id()))
                .toAbsolutePath().normalize();
        if (providerRoot.equals(workspacesRoot) || !providerRoot.startsWith(workspacesRoot)) {
            throw new IOException("local provider workspace escapes MINOS workspace root");
        }
        if (Files.exists(providerRoot, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(workspacesRoot, providerRoot);
        }
        PrivateLocalStorage.ensurePrivateDirectory(providerRoot);
        Path workspaceRoot = providerRoot.resolve("workspace").toAbsolutePath().normalize();
        PrivateLocalStorage.ensurePrivateDirectory(workspaceRoot);

        boolean success = false;
        try {
            copyWorkspace(request.registeredProjectRoot(), workspaceRoot, policy);
            Path isolatedProjectRoot = workspaceRoot.resolve(request.projectRelativeRoot()).normalize();
            if (!isolatedProjectRoot.startsWith(workspaceRoot)
                    || !Files.isDirectory(isolatedProjectRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("isolated provider project scope is missing or escapes its workspace");
            }
            IndexingExecutionRequest isolated = new IndexingExecutionRequest(
                    request.runId(),
                    request.projectId(),
                    workspaceRoot,
                    isolatedProjectRoot,
                    request.projectRelativeRoot(),
                    request.selection(),
                    request.mode(),
                    request.changedFiles());
            success = true;
            return new LocalProviderWorkspace(workspacesRoot, providerRoot, workspaceRoot, isolated);
        } finally {
            if (!success && Files.exists(providerRoot, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(workspacesRoot, providerRoot);
            }
        }
    }

    IndexingExecutionRequest request() {
        return isolatedRequest;
    }

    Path workspaceRoot() {
        return workspaceRoot;
    }

    @Override
    public void close() throws IOException {
        if (Files.exists(providerRoot, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(workspacesRoot, providerRoot);
        }
        Path runRoot = providerRoot.getParent();
        if (runRoot != null && runRoot.startsWith(workspacesRoot)) {
            try {
                Files.deleteIfExists(runRoot);
            } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                // Another provider belonging to the same run still owns its isolated workspace.
            }
        }
    }

    private static void copyWorkspace(
            Path sourceRoot,
            Path targetRoot,
            SourceBudgetPolicy budgetPolicy
    ) throws IOException {
        Path source = Objects.requireNonNull(sourceRoot, "sourceRoot").toRealPath();
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("local provider source workspace is not a directory");
        }
        ProjectIgnoreRules ignoreRules = ProjectIgnoreRules.load(source);
        SourceBudgetPolicy.Tracker budget = budgetPolicy.tracker("local provider workspace");
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                budget.accountTraversalEntry();
                Path relative = source.relativize(directory);
                if (!relative.toString().isEmpty() && ignoreRules.isHardIgnored(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isSymbolicLink(directory) || attributes.isSymbolicLink()) {
                    throw new IOException("local provider workspace rejects symbolic links: " + portable(relative));
                }
                Path target = checkedTarget(targetRoot, relative);
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                Path relative = source.relativize(file);
                if (ignoreRules.isIgnored(relative, false)) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isSymbolicLink(file) || attributes.isSymbolicLink()) {
                    throw new IOException("local provider workspace rejects symbolic links: " + portable(relative));
                }
                if (!attributes.isRegularFile()) {
                    throw new IOException("local provider workspace rejects non-regular entry: " + portable(relative));
                }
                budget.accountFile();
                Path target = checkedTarget(targetRoot, relative);
                Files.createDirectories(target.getParent());
                copyBounded(file, target, budget);
                Files.setLastModifiedTime(target, attributes.lastModifiedTime());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                budget.accountTraversalEntry();
                throw failure;
            }
        });
    }

    private static void copyBounded(Path source, Path target, SourceBudgetPolicy.Tracker budget) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                budget.accountBytes(read);
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(target);
            throw failure;
        }
    }

    private static Path checkedTarget(Path targetRoot, Path relative) throws IOException {
        Path target = targetRoot.resolve(relative).normalize();
        if (!target.startsWith(targetRoot)) {
            throw new IOException("local provider workspace path escapes its target root");
        }
        return target;
    }

    private static void deleteTree(Path workspacesRoot, Path target) throws IOException {
        Path root = workspacesRoot.toAbsolutePath().normalize();
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new IOException("refusing to delete outside the local provider workspace root");
        }
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                clearReadOnly(file);
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                clearReadOnly(directory);
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes != null && attributes.readAttributes().isReadOnly()) {
                attributes.setReadOnly(false);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS file systems do not need this Windows-specific cleanup.
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}

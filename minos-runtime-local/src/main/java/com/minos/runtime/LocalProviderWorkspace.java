package com.minos.runtime;

import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.ProviderId;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Bounded ephemeral copy used for local provider execution.
 *
 * <p>Providers are untrusted and must never receive the registered project tree as a writable
 * working directory. This boundary mirrors the distributed worker copy contract through the
 * shared {@link ProviderWorkspaceFiles} implementation.</p>
 */
final class LocalProviderWorkspace implements AutoCloseable {

    private static final String WORKSPACE_BOUNDARY = "local provider workspace";

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
            ProviderWorkspaceFiles.deleteTree(workspacesRoot, providerRoot, WORKSPACE_BOUNDARY);
        }
        PrivateLocalStorage.ensurePrivateDirectory(providerRoot);
        Path workspaceRoot = providerRoot.resolve("workspace").toAbsolutePath().normalize();
        PrivateLocalStorage.ensurePrivateDirectory(workspaceRoot);

        boolean success = false;
        try {
            ProviderWorkspaceFiles.copyWorkspace(
                    request.registeredProjectRoot(), workspaceRoot, policy, WORKSPACE_BOUNDARY);
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
                ProviderWorkspaceFiles.deleteTree(workspacesRoot, providerRoot, WORKSPACE_BOUNDARY);
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
            ProviderWorkspaceFiles.deleteTree(workspacesRoot, providerRoot, WORKSPACE_BOUNDARY);
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
}

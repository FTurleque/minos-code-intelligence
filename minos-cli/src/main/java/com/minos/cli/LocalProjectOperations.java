package com.minos.cli;

import com.minos.application.MinosApplication;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Backward-compatible CLI facade delegating to the application-level implementation.
 *
 * @deprecated public surfaces should use {@link com.minos.application.LocalProjectOperations}.
 */
@Deprecated(forRemoval = false)
public final class LocalProjectOperations implements ProjectOperations {

    private final com.minos.application.LocalProjectOperations delegate;

    public LocalProjectOperations(Path home) throws IOException {
        this(new com.minos.application.LocalProjectOperations(home));
    }

    public LocalProjectOperations(MinosApplication application) {
        this(new com.minos.application.LocalProjectOperations(application));
    }

    private LocalProjectOperations(com.minos.application.LocalProjectOperations delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ProjectView addProject(Path rootPath, String displayName) throws IOException {
        return delegate.addProject(rootPath, displayName);
    }

    @Override
    public List<ProjectView> listProjects() throws IOException {
        return delegate.listProjects();
    }

    @Override
    public ProjectView inspectProject(String projectIdentifier) throws IOException {
        return delegate.inspectProject(projectIdentifier);
    }

    @Override
    public IndexImportResult importScip(
            String projectIdentifier,
            Path indexFile,
            String providerId,
            String providerVersion,
            String moduleId,
            String snapshotId
    ) throws IOException {
        return delegate.importScip(
                projectIdentifier,
                indexFile,
                providerId,
                providerVersion,
                moduleId,
                snapshotId);
    }
}

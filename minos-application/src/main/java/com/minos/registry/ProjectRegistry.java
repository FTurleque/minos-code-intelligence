package com.minos.registry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence-neutral project/workspace registry contract.
 *
 * <p>Physical project paths are runtime locations; project/workspace UUIDs remain the
 * authoritative identities regardless of the selected storage backend.</p>
 */
public interface ProjectRegistry {

    RegisteredProject registerProject(Path rootPath, String displayName) throws IOException;

    RegisteredWorkspace createWorkspace(String name) throws IOException;

    RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException;

    RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException;

    Optional<RegisteredProject> findProject(UUID projectId) throws IOException;

    Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException;

    List<RegisteredProject> listProjects() throws IOException;

    List<RegisteredWorkspace> listWorkspaces() throws IOException;
}

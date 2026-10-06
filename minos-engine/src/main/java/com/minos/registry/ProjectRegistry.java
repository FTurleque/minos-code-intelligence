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

    /**
     * Atomically reports whether this call created the durable registration. Implementations
     * that cannot prove creation return createdByThisCall=false so higher-level rollback never
     * deletes a registration that may belong to another concurrent operation.
     */
    default RegistrationResult registerProjectWithResult(Path rootPath, String displayName) throws IOException {
        return new RegistrationResult(registerProject(rootPath, displayName), false);
    }

    RegisteredWorkspace createWorkspace(String name) throws IOException;

    /**
     * Atomically gets or creates the unique workspace identified by its exact name. Implementations
     * that cannot prove creation return createdByThisCall=false.
     */
    default WorkspaceRegistrationResult createWorkspaceWithResult(String name) throws IOException {
        return new WorkspaceRegistrationResult(createWorkspace(name), false);
    }

    RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException;

    RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException;

    Optional<RegisteredProject> findProject(UUID projectId) throws IOException;

    Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException;

    List<RegisteredProject> listProjects() throws IOException;

    /**
     * The registry as an inventory: every readable project, and one {@link DegradedEntry} per entry that could
     * not be read. Unlike {@link #listProjects()}, which fails as a whole on the first unreadable entry, this
     * view isolates the damage to its entry and never hides it (Q8). A registry that cannot be listed at all
     * still fails with an {@link IOException}: there is then nothing to report entry by entry.
     */
    default Inventory inventory() throws IOException {
        return new Inventory(listProjects(), List.of());
    }

    List<RegisteredWorkspace> listWorkspaces() throws IOException;

    /**
     * The workspaces as an inventory (Q26): every workspace that could be established, and one {@link DegradedEntry}
     * per entry that could not be read. Unlike {@link #listWorkspaces()}, which fails as a whole on the first
     * unreadable entry, this view isolates the damage to its entry and never hides it.
     *
     * <p>Two kinds of entries are read to establish a workspace, and they say different things when unreadable. A
     * workspace entry that cannot be read is a workspace that may exist and is missing from the list
     * ({@link WorkspaceInventory#unreadableWorkspaces()}): an absence is then undetermined. A project entry that cannot
     * be read cannot create or remove a workspace, but it may belong to one: the membership of every listed workspace
     * is then a lower bound ({@link WorkspaceInventory#unreadableProjects()}). A registry that cannot be listed at all
     * still fails with an {@link IOException}.</p>
     *
     * <p>The default is the strict view with nothing unreadable: a backend that cannot have unreadable entries (a
     * database) is correct as it is. A decorator over a registry that can have them must override this method, or it
     * silently turns the tolerant view back into the strict one.</p>
     */
    default WorkspaceInventory workspaceInventory() throws IOException {
        return new WorkspaceInventory(listWorkspaces(), List.of(), List.of());
    }

    /**
     * Removes one project registration when a higher-level operation must roll back a registration.
     * Implementations that do not support deletion remain source-compatible and fail explicitly.
     */
    default boolean deleteProject(UUID projectId) throws IOException {
        throw new UnsupportedOperationException("project deletion is not supported by this registry");
    }

    record Inventory(List<RegisteredProject> projects, List<DegradedEntry> unreadable) {
        public Inventory {
            projects = List.copyOf(java.util.Objects.requireNonNull(projects, "projects"));
            unreadable = List.copyOf(java.util.Objects.requireNonNull(unreadable, "unreadable"));
        }
    }

    /**
     * The workspaces that could be established, with the entries that could not be read. {@code unreadableWorkspaces}
     * identify workspace entries (by their identifier, never a path); {@code unreadableProjects} identify the project
     * entries whose membership could not be taken into account. Both are the registry's own {@link DegradedEntry}.
     */
    record WorkspaceInventory(
            List<RegisteredWorkspace> workspaces,
            List<DegradedEntry> unreadableWorkspaces,
            List<DegradedEntry> unreadableProjects
    ) {
        public WorkspaceInventory {
            workspaces = List.copyOf(java.util.Objects.requireNonNull(workspaces, "workspaces"));
            unreadableWorkspaces = List.copyOf(java.util.Objects.requireNonNull(unreadableWorkspaces, "unreadableWorkspaces"));
            unreadableProjects = List.copyOf(java.util.Objects.requireNonNull(unreadableProjects, "unreadableProjects"));
        }
    }

    record RegistrationResult(RegisteredProject project, boolean createdByThisCall) {
        public RegistrationResult {
            java.util.Objects.requireNonNull(project, "project");
        }
    }

    record WorkspaceRegistrationResult(RegisteredWorkspace workspace, boolean createdByThisCall) {
        public WorkspaceRegistrationResult {
            java.util.Objects.requireNonNull(workspace, "workspace");
        }
    }
}

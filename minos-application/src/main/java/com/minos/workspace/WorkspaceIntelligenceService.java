package com.minos.workspace;

import com.minos.application.resolution.ProjectResolver;
import com.minos.domain.ProviderReference;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.registry.DegradedEntry;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.registry.UnreadableRegistryException;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.minos.domain.Preconditions.requireText;

/** M12 multi-repository intelligence over workspaces and active knowledge snapshots. */
public final class WorkspaceIntelligenceService {
    private static final int MAX_RELATIONSHIPS = 10_000;
    private final ProjectRegistry registry;
    private final CodeKnowledgeSnapshotStore snapshots;
    private final ProjectResolver projectResolver;

    public WorkspaceIntelligenceService(ProjectRegistry registry, CodeKnowledgeSnapshotStore snapshots) {
        this(registry, snapshots, new ProjectResolver(registry));
    }

    public WorkspaceIntelligenceService(ProjectRegistry registry, CodeKnowledgeSnapshotStore snapshots, ProjectResolver projectResolver) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
    }

    public WorkspaceView createWorkspace(String name) throws IOException {
        requireText(name, "name");
        return workspace(registry.createWorkspaceWithResult(name).workspace());
    }

    public WorkspaceView assignProject(String projectIdentifier, String workspaceIdentifier) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        RegisteredWorkspace workspace = resolveWorkspace(workspaceIdentifier);
        registry.assignProjectToWorkspace(project.id(), workspace.id());
        return workspace(registry.findWorkspace(workspace.id()).orElseThrow());
    }

    public WorkspaceView getWorkspace(String workspaceIdentifier) throws IOException { return workspace(resolveWorkspace(workspaceIdentifier)); }
    public List<WorkspaceView> listWorkspaces() throws IOException { return registry.listWorkspaces().stream().map(WorkspaceIntelligenceService::workspace).toList(); }

    /**
     * The workspaces as an inventory (Q26): the ones that could be established, and the registry entries that could
     * not be read, carried as the registry's own {@link DegradedEntry}. {@link #listWorkspaces()} stays strict. A registry
     * whose directories cannot be listed still fails: that is an outage, not an inventory.
     */
    public WorkspaceListing listWorkspacesTolerantly() throws IOException {
        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();
        return new WorkspaceListing(inventory.workspaces().stream().map(WorkspaceIntelligenceService::workspace).toList(),
                inventory.unreadableWorkspaces(), inventory.unreadableProjects());
    }

    /**
     * Resolves one workspace, tolerant in its answer and not in its verdict (Q26). Found among the readable entries: the
     * workspace, with the unreadable entries that qualify the answer. Not found: absent when nothing that could hold it was
     * unreadable, and otherwise neither found nor absent ({@link UnreadableRegistryException}).
     *
     * <p>What can hold a workspace is a <em>workspace</em> entry: a project entry cannot create or remove one, it only
     * may belong to one, so unreadable project entries qualify the membership of a found workspace
     * ({@link WorkspaceLookup#unreadableProjects()}) and never make an absent workspace undetermined. By identifier, only
     * the entry of that identifier matters; by name, every unreadable workspace entry does, since any of them may carry
     * this name.</p>
     */
    public WorkspaceLookup getWorkspaceTolerantly(String workspaceIdentifier) throws IOException {
        requireText(workspaceIdentifier, "workspaceIdentifier");
        ProjectRegistry.WorkspaceInventory inventory = registry.workspaceInventory();
        Optional<UUID> id = uuid(workspaceIdentifier);
        if (id.isPresent()) {
            String entry = id.orElseThrow().toString();
            Optional<RegisteredWorkspace> found = inventory.workspaces().stream()
                    .filter(candidate -> candidate.id().equals(id.orElseThrow())).findFirst();
            if (found.isPresent()) return new WorkspaceLookup(workspace(found.orElseThrow()), List.of(), inventory.unreadableProjects());
            List<DegradedEntry> own = inventory.unreadableWorkspaces().stream().filter(degraded -> entry.equals(degraded.entry())).toList();
            if (!own.isEmpty()) throw UnreadableRegistryException.of(own, "this workspace cannot be read");
            throw new IllegalArgumentException("Unknown workspace: " + workspaceIdentifier);
        }
        List<RegisteredWorkspace> matches = inventory.workspaces().stream()
                .filter(candidate -> candidate.name().equals(workspaceIdentifier)).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous workspace name: " + workspaceIdentifier);
        if (matches.size() == 1) {
            return new WorkspaceLookup(workspace(matches.getFirst()), inventory.unreadableWorkspaces(), inventory.unreadableProjects());
        }
        if (!inventory.unreadableWorkspaces().isEmpty()) {
            throw UnreadableRegistryException.of(inventory.unreadableWorkspaces(), "it cannot be told whether this workspace exists");
        }
        throw new IllegalArgumentException("Unknown workspace: " + workspaceIdentifier);
    }

    public WorkspaceReport analyze(String workspaceIdentifier, int maxRelationships) throws IOException {
        requireLimit(maxRelationships);
        RegisteredWorkspace workspace = resolveWorkspace(workspaceIdentifier);
        List<ProjectKnowledge> projects = new ArrayList<>();
        for (UUID projectId : workspace.projectIds()) {
            RegisteredProject project = registry.findProject(projectId)
                    .orElseThrow(() -> new IllegalStateException("Workspace references unknown project: " + projectId));
            Optional<CodeKnowledgeSnapshot> snapshot = snapshots.loadActiveKnowledge(projectId);
            projects.add(new ProjectKnowledge(project, snapshot.orElse(null)));
        }

        Map<ProviderKey, List<TargetSymbol>> targets = targetIndex(projects);
        List<CrossRepositoryRelationship> resolved = new ArrayList<>();
        int unresolvedCount = 0; int ambiguousCount = 0; int exactResolutionCount = 0; boolean truncated = false;
        for (ProjectKnowledge project : projects) {
            if (project.snapshot() == null) continue;
            for (Relationship relationship : project.snapshot().relationships()) {
                if (relationship.target() != null || relationship.unresolvedTarget() == null) continue;
                ProviderKey key = new ProviderKey(relationship.origin().providerId(), relationship.unresolvedTarget());
                List<TargetSymbol> candidates = targets.getOrDefault(key, List.of()).stream()
                        .filter(candidate -> !candidate.projectId().equals(project.project().id())).toList();
                if (candidates.size() == 1) {
                    exactResolutionCount++;
                    if (resolved.size() < maxRelationships) {
                        TargetSymbol target = candidates.getFirst();
                        resolved.add(new CrossRepositoryRelationship(project.project().id().toString(), relationship.id(),
                                relationship.source().id(), target.projectId().toString(), target.symbol().id(),
                                target.symbol().qualifiedName(), relationship.kind().name(), key.providerId(), key.externalId(),
                                "EXACT_PROVIDER_REFERENCE", 1.0));
                    } else truncated = true;
                } else if (candidates.size() > 1) ambiguousCount++; else unresolvedCount++;
            }
        }

        List<String> limitations = new ArrayList<>();
        if (projects.stream().anyMatch(project -> project.snapshot() == null)) limitations.add("PROJECT_WITHOUT_ACTIVE_SNAPSHOT");
        if (ambiguousCount > 0) limitations.add("AMBIGUOUS_PROVIDER_IDENTITY");
        if (unresolvedCount > 0) limitations.add("UNRESOLVED_CROSS_REPOSITORY_TARGETS");
        if (truncated) limitations.add("RELATIONSHIPS_TRUNCATED");
        List<ProjectSnapshotView> projectViews = projects.stream().map(ProjectKnowledge::view)
                .sorted(Comparator.comparing(ProjectSnapshotView::projectId)).toList();
        return new WorkspaceReport(workspace(workspace), projectViews, exactResolutionCount, ambiguousCount,
                unresolvedCount, truncated, resolved, limitations);
    }

    private Map<ProviderKey, List<TargetSymbol>> targetIndex(List<ProjectKnowledge> projects) {
        Map<ProviderKey, List<TargetSymbol>> index = new HashMap<>();
        for (ProjectKnowledge project : projects) {
            if (project.snapshot() == null) continue;
            for (Symbol symbol : project.snapshot().symbols()) {
                if (symbol.external()) continue;
                for (ProviderReference reference : symbol.providerReferences()) {
                    ProviderKey key = new ProviderKey(reference.providerId(), reference.externalId());
                    index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new TargetSymbol(project.project().id(), symbol));
                }
            }
        }
        return index;
    }

    private RegisteredWorkspace resolveWorkspace(String identifier) throws IOException {
        requireText(identifier, "workspaceIdentifier");
        Optional<UUID> uuid = uuid(identifier);
        if (uuid.isPresent()) return registry.findWorkspace(uuid.orElseThrow())
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + identifier));
        List<RegisteredWorkspace> matches = registry.listWorkspaces().stream().filter(workspace -> workspace.name().equals(identifier)).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Unknown workspace: " + identifier);
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous workspace name: " + identifier);
        return matches.getFirst();
    }

    private static Optional<UUID> uuid(String value) {
        try { return Optional.of(UUID.fromString(value)); } catch (IllegalArgumentException exception) { return Optional.empty(); }
    }

    private static WorkspaceView workspace(RegisteredWorkspace workspace) {
        return new WorkspaceView(workspace.id().toString(), workspace.name(), workspace.projectIds().stream().map(UUID::toString).toList(),
                workspace.createdAt().toString(), workspace.updatedAt().toString());
    }

    private static void requireLimit(int value) { if (value < 1 || value > MAX_RELATIONSHIPS) throw new IllegalArgumentException("maxRelationships must be between 1 and " + MAX_RELATIONSHIPS); }

    public record WorkspaceView(String id, String name, List<String> projectIds, String createdAt, String updatedAt) {
        public WorkspaceView { projectIds = projectIds == null ? List.of() : List.copyOf(projectIds); }
    }
    /** The workspaces that could be established, and what could not be read (see {@link #listWorkspacesTolerantly()}). */
    public record WorkspaceListing(List<WorkspaceView> workspaces, List<DegradedEntry> unreadableWorkspaces,
                                   List<DegradedEntry> unreadableProjects) {
        public WorkspaceListing {
            workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
            unreadableWorkspaces = List.copyOf(Objects.requireNonNull(unreadableWorkspaces, "unreadableWorkspaces"));
            unreadableProjects = List.copyOf(Objects.requireNonNull(unreadableProjects, "unreadableProjects"));
        }
    }
    /** One workspace and the unreadable entries that qualify the answer (see {@link #getWorkspaceTolerantly}). */
    public record WorkspaceLookup(WorkspaceView workspace, List<DegradedEntry> unreadableWorkspaces,
                                  List<DegradedEntry> unreadableProjects) {
        public WorkspaceLookup {
            Objects.requireNonNull(workspace, "workspace");
            unreadableWorkspaces = List.copyOf(Objects.requireNonNull(unreadableWorkspaces, "unreadableWorkspaces"));
            unreadableProjects = List.copyOf(Objects.requireNonNull(unreadableProjects, "unreadableProjects"));
        }
    }
    public record ProjectSnapshotView(String projectId, String projectName, String rootPath, boolean indexed, String snapshotId, int localSymbolCount, int unresolvedRelationshipCount) { }
    public record CrossRepositoryRelationship(String sourceProjectId, String sourceRelationshipId, String sourceSymbolId,
                                              String targetProjectId, String targetSymbolId, String targetQualifiedName,
                                              String kind, String providerId, String providerExternalId,
                                              String resolutionBasis, double confidence) { }
    public record WorkspaceReport(WorkspaceView workspace, List<ProjectSnapshotView> projects, int exactResolutionCount,
                                  int ambiguousTargetCount, int unresolvedTargetCount, boolean relationshipsTruncated,
                                  List<CrossRepositoryRelationship> crossRepositoryRelationships, List<String> limitations) {
        public WorkspaceReport {
            projects = projects == null ? List.of() : List.copyOf(projects);
            crossRepositoryRelationships = crossRepositoryRelationships == null ? List.of() : List.copyOf(crossRepositoryRelationships);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }
    private record ProviderKey(String providerId, String externalId) {
        private ProviderKey { requireText(providerId, "providerId"); requireText(externalId, "externalId"); }
    }
    private record TargetSymbol(UUID projectId, Symbol symbol) { }
    private record ProjectKnowledge(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
        private ProjectSnapshotView view() {
            if (snapshot == null) return new ProjectSnapshotView(project.id().toString(), project.displayName(), project.rootPath().toString(), false, null, 0, 0);
            int localSymbols = (int) snapshot.symbols().stream().filter(symbol -> !symbol.external()).count();
            int unresolved = (int) snapshot.relationships().stream().filter(relation -> relation.target() == null).count();
            return new ProjectSnapshotView(project.id().toString(), project.displayName(), project.rootPath().toString(), true,
                    snapshot.snapshotId(), localSymbols, unresolved);
        }
    }
}

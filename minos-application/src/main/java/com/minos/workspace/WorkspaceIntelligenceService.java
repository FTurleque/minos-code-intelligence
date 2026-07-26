package com.minos.workspace;

import com.minos.domain.ProviderReference;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M12 multi-repository intelligence over M1 workspaces and active knowledge snapshots.
 *
 * <p>Cross-repository relationships are resolved only when a relationship's unresolved
 * provider identity has exactly one matching local symbol in another project of the same
 * workspace. Name-only matching is deliberately excluded.</p>
 */
public final class WorkspaceIntelligenceService {

    private static final int MAX_RELATIONSHIPS = 10_000;

    private final LocalProjectRegistry registry;
    private final FileSymbolSnapshotStore snapshots;

    public WorkspaceIntelligenceService(LocalProjectRegistry registry, FileSymbolSnapshotStore snapshots) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public WorkspaceView createWorkspace(String name) throws IOException {
        requireText(name, "name");
        List<RegisteredWorkspace> matches = registry.listWorkspaces().stream()
                .filter(workspace -> workspace.name().equals(name))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous workspace name: " + name);
        }
        RegisteredWorkspace workspace = matches.isEmpty() ? registry.createWorkspace(name) : matches.getFirst();
        return workspace(workspace);
    }

    public WorkspaceView assignProject(String projectIdentifier, String workspaceIdentifier) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        RegisteredWorkspace workspace = resolveWorkspace(workspaceIdentifier);
        registry.assignProjectToWorkspace(project.id(), workspace.id());
        return workspace(registry.findWorkspace(workspace.id()).orElseThrow());
    }

    public WorkspaceView getWorkspace(String workspaceIdentifier) throws IOException {
        return workspace(resolveWorkspace(workspaceIdentifier));
    }

    public List<WorkspaceView> listWorkspaces() throws IOException {
        return registry.listWorkspaces().stream().map(WorkspaceIntelligenceService::workspace).toList();
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
        int unresolvedCount = 0;
        int ambiguousCount = 0;
        int exactResolutionCount = 0;
        boolean truncated = false;

        for (ProjectKnowledge project : projects) {
            if (project.snapshot() == null) {
                continue;
            }
            for (Relationship relationship : project.snapshot().relationships()) {
                if (relationship.target() != null || relationship.unresolvedTarget() == null) {
                    continue;
                }
                ProviderKey key = new ProviderKey(relationship.origin().providerId(), relationship.unresolvedTarget());
                List<TargetSymbol> candidates = targets.getOrDefault(key, List.of()).stream()
                        .filter(candidate -> !candidate.projectId().equals(project.project().id()))
                        .toList();
                if (candidates.size() == 1) {
                    exactResolutionCount++;
                    if (resolved.size() < maxRelationships) {
                        TargetSymbol target = candidates.getFirst();
                        resolved.add(new CrossRepositoryRelationship(
                                project.project().id().toString(),
                                relationship.id(),
                                relationship.source().id(),
                                target.projectId().toString(),
                                target.symbol().id(),
                                target.symbol().qualifiedName(),
                                relationship.kind().name(),
                                key.providerId(),
                                key.externalId(),
                                "EXACT_PROVIDER_REFERENCE",
                                1.0
                        ));
                    } else {
                        truncated = true;
                    }
                } else if (candidates.size() > 1) {
                    ambiguousCount++;
                } else {
                    unresolvedCount++;
                }
            }
        }

        List<String> limitations = new ArrayList<>();
        if (projects.stream().anyMatch(project -> project.snapshot() == null)) {
            limitations.add("PROJECT_WITHOUT_ACTIVE_SNAPSHOT");
        }
        if (ambiguousCount > 0) {
            limitations.add("AMBIGUOUS_PROVIDER_IDENTITY");
        }
        if (unresolvedCount > 0) {
            limitations.add("UNRESOLVED_CROSS_REPOSITORY_TARGETS");
        }
        if (truncated) {
            limitations.add("RELATIONSHIPS_TRUNCATED");
        }

        List<ProjectSnapshotView> projectViews = projects.stream()
                .map(ProjectKnowledge::view)
                .sorted(Comparator.comparing(ProjectSnapshotView::projectId))
                .toList();
        return new WorkspaceReport(
                workspace(workspace),
                projectViews,
                exactResolutionCount,
                ambiguousCount,
                unresolvedCount,
                truncated,
                resolved,
                limitations
        );
    }

    private Map<ProviderKey, List<TargetSymbol>> targetIndex(List<ProjectKnowledge> projects) {
        Map<ProviderKey, List<TargetSymbol>> index = new HashMap<>();
        for (ProjectKnowledge project : projects) {
            if (project.snapshot() == null) {
                continue;
            }
            for (Symbol symbol : project.snapshot().symbols()) {
                if (symbol.external()) {
                    continue;
                }
                for (ProviderReference reference : symbol.providerReferences()) {
                    ProviderKey key = new ProviderKey(reference.providerId(), reference.externalId());
                    index.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new TargetSymbol(project.project().id(), symbol));
                }
            }
        }
        return index;
    }

    private RegisteredProject resolveProject(String identifier) throws IOException {
        requireText(identifier, "projectIdentifier");
        Optional<UUID> uuid = uuid(identifier);
        if (uuid.isPresent()) {
            return registry.findProject(uuid.orElseThrow())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + identifier));
        }
        List<RegisteredProject> matches = registry.listProjects().stream()
                .filter(project -> project.displayName().equals(identifier))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown project: " + identifier);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous project name: " + identifier);
        }
        return matches.getFirst();
    }

    private RegisteredWorkspace resolveWorkspace(String identifier) throws IOException {
        requireText(identifier, "workspaceIdentifier");
        Optional<UUID> uuid = uuid(identifier);
        if (uuid.isPresent()) {
            return registry.findWorkspace(uuid.orElseThrow())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + identifier));
        }
        List<RegisteredWorkspace> matches = registry.listWorkspaces().stream()
                .filter(workspace -> workspace.name().equals(identifier))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown workspace: " + identifier);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous workspace name: " + identifier);
        }
        return matches.getFirst();
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static WorkspaceView workspace(RegisteredWorkspace workspace) {
        return new WorkspaceView(
                workspace.id().toString(),
                workspace.name(),
                workspace.projectIds().stream().map(UUID::toString).toList(),
                workspace.createdAt().toString(),
                workspace.updatedAt().toString()
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireLimit(int value) {
        if (value < 1 || value > MAX_RELATIONSHIPS) {
            throw new IllegalArgumentException("maxRelationships must be between 1 and " + MAX_RELATIONSHIPS);
        }
    }

    public record WorkspaceView(
            String id,
            String name,
            List<String> projectIds,
            String createdAt,
            String updatedAt
    ) {
        public WorkspaceView {
            projectIds = projectIds == null ? List.of() : List.copyOf(projectIds);
        }
    }

    public record ProjectSnapshotView(
            String projectId,
            String projectName,
            String rootPath,
            boolean indexed,
            String snapshotId,
            int localSymbolCount,
            int unresolvedRelationshipCount
    ) {
    }

    public record CrossRepositoryRelationship(
            String sourceProjectId,
            String sourceRelationshipId,
            String sourceSymbolId,
            String targetProjectId,
            String targetSymbolId,
            String targetQualifiedName,
            String kind,
            String providerId,
            String providerExternalId,
            String resolutionBasis,
            double confidence
    ) {
    }

    public record WorkspaceReport(
            WorkspaceView workspace,
            List<ProjectSnapshotView> projects,
            int exactResolutionCount,
            int ambiguousTargetCount,
            int unresolvedTargetCount,
            boolean relationshipsTruncated,
            List<CrossRepositoryRelationship> crossRepositoryRelationships,
            List<String> limitations
    ) {
        public WorkspaceReport {
            projects = projects == null ? List.of() : List.copyOf(projects);
            crossRepositoryRelationships = crossRepositoryRelationships == null
                    ? List.of()
                    : List.copyOf(crossRepositoryRelationships);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    private record ProviderKey(String providerId, String externalId) {
        private ProviderKey {
            requireText(providerId, "providerId");
            requireText(externalId, "externalId");
        }
    }

    private record TargetSymbol(UUID projectId, Symbol symbol) {
    }

    private record ProjectKnowledge(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
        private ProjectSnapshotView view() {
            if (snapshot == null) {
                return new ProjectSnapshotView(
                        project.id().toString(), project.displayName(), project.rootPath().toString(),
                        false, null, 0, 0
                );
            }
            int localSymbols = (int) snapshot.symbols().stream().filter(symbol -> !symbol.external()).count();
            int unresolved = (int) snapshot.relationships().stream().filter(relation -> relation.target() == null).count();
            return new ProjectSnapshotView(
                    project.id().toString(), project.displayName(), project.rootPath().toString(),
                    true, snapshot.snapshotId(), localSymbols, unresolved
            );
        }
    }
}

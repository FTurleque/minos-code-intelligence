package com.minos.workspace;

import com.minos.diagnostics.UsageException;
import com.minos.domain.registry.DegradedEntry;
import com.minos.domain.registry.UnreadableRegistryException;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Resolving a workspace: an unknown or ambiguous identifier is a usage error, an absence that an unreadable entry
 * leaves undetermined is not, and a limit outside its bounds is a usage error.
 */
class WorkspaceLookupUsageTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theStrictLookupFindsByIdOrNameAndRefusesUnknownOrAmbiguousIdentifiers() throws Exception {
        RegisteredWorkspace platform = workspace("platform");
        FakeRegistry registry = new FakeRegistry(List.of(platform, workspace("twin"), workspace("twin")), List.of());
        WorkspaceIntelligenceService service = service(registry);

        assertEquals("platform", service.getWorkspace(platform.id().toString()).name());
        assertEquals("platform", service.getWorkspace("platform").name());
        assertThrows(UsageException.class, () -> service.getWorkspace(UUID.randomUUID().toString()));
        assertThrows(UsageException.class, () -> service.getWorkspace("absent"));
        assertThrows(UsageException.class, () -> service.getWorkspace("twin"));
    }

    @Test
    void theTolerantLookupTellsAnAbsenceFromAnUndeterminedOne() throws Exception {
        RegisteredWorkspace platform = workspace("platform");
        UUID unreadableId = UUID.randomUUID();
        DegradedEntry unreadable = DegradedEntry.of(unreadableId.toString(), "not a regular file");
        FakeRegistry clean = new FakeRegistry(List.of(platform, workspace("twin"), workspace("twin")), List.of());
        FakeRegistry damaged = new FakeRegistry(List.of(platform), List.of(unreadable));

        assertEquals("platform", service(clean).getWorkspaceTolerantly(platform.id().toString()).workspace().name());
        assertEquals("platform", service(clean).getWorkspaceTolerantly("platform").workspace().name());
        assertThrows(UsageException.class, () -> service(clean).getWorkspaceTolerantly(UUID.randomUUID().toString()));
        assertThrows(UsageException.class, () -> service(clean).getWorkspaceTolerantly("absent"));
        assertThrows(UsageException.class, () -> service(clean).getWorkspaceTolerantly("twin"));

        assertThrows(UnreadableRegistryException.class,
                () -> service(damaged).getWorkspaceTolerantly(unreadableId.toString()));
        assertThrows(UsageException.class, () -> service(damaged).getWorkspaceTolerantly(UUID.randomUUID().toString()));
        assertThrows(UnreadableRegistryException.class, () -> service(damaged).getWorkspaceTolerantly("absent"));
    }

    @Test
    void aRelationshipLimitOutOfBoundsIsAUsageError() {
        WorkspaceIntelligenceService service = service(new FakeRegistry(List.of(workspace("platform")), List.of()));

        assertThrows(UsageException.class, () -> service.analyze("platform", 0));
        assertThrows(UsageException.class, () -> service.analyze("platform", 10_001));
    }

    private static WorkspaceIntelligenceService service(ProjectRegistry registry) {
        CodeKnowledgeSnapshotStore snapshots = (CodeKnowledgeSnapshotStore) Proxy.newProxyInstance(
                CodeKnowledgeSnapshotStore.class.getClassLoader(), new Class<?>[]{CodeKnowledgeSnapshotStore.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        return new WorkspaceIntelligenceService(registry, snapshots);
    }

    private static RegisteredWorkspace workspace(String name) {
        return new RegisteredWorkspace(UUID.randomUUID(), name, List.of(), AT, AT);
    }

    /** A registry holding workspaces in memory, with the workspace entries it reports as unreadable. */
    private static final class FakeRegistry implements ProjectRegistry {
        private final List<RegisteredWorkspace> workspaces;
        private final List<DegradedEntry> unreadable;

        FakeRegistry(List<RegisteredWorkspace> workspaces, List<DegradedEntry> unreadable) {
            this.workspaces = new ArrayList<>(workspaces);
            this.unreadable = List.copyOf(unreadable);
        }

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegisteredWorkspace createWorkspace(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RegisteredProject removeProjectFromWorkspace(UUID projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return Optional.empty();
        }

        @Override
        public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) {
            return workspaces.stream().filter(workspace -> workspace.id().equals(workspaceId)).findFirst();
        }

        @Override
        public List<RegisteredProject> listProjects() {
            return List.of();
        }

        @Override
        public List<RegisteredWorkspace> listWorkspaces() {
            return List.copyOf(workspaces);
        }

        @Override
        public WorkspaceInventory workspaceInventory() {
            return new WorkspaceInventory(workspaces, unreadable, List.of());
        }
    }
}

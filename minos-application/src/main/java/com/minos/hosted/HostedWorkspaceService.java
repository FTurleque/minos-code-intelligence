package com.minos.hosted;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped workspace lifecycle and project-binding operations. */
final class HostedWorkspaceService {

    private final HostedAuthorizationService authorization;
    private final HostedBindingVerifier bindingVerifier;
    private final HostedTenantMutationWriter writer;
    private final Clock clock;

    HostedWorkspaceService(
            HostedAuthorizationService authorization,
            HostedBindingVerifier bindingVerifier,
            HostedTenantMutationWriter writer,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.bindingVerifier = Objects.requireNonNull(bindingVerifier, "bindingVerifier");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    List<SharedWorkspace> list(String bearerToken) throws IOException {
        HostedTenantState state = authorization.authorizeRead(
                bearerToken, HostedPermission.WORKSPACE_READ).state();
        return state.workspaces().stream()
                .sorted(Comparator.comparing(SharedWorkspace::name).thenComparing(SharedWorkspace::workspaceId))
                .toList();
    }

    SharedWorkspace get(String bearerToken, UUID workspaceId) throws IOException {
        HostedTenantState state = authorization.authorizeRead(
                bearerToken, HostedPermission.WORKSPACE_READ).state();
        return requireWorkspace(state, workspaceId);
    }

    SharedWorkspace create(String bearerToken, String requestId, String name) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.WORKSPACE_WRITE,
                "WORKSPACE_CREATE", "WORKSPACE", "new");
        if (context.state().workspaces().size() >= HostedTenantState.MAX_WORKSPACES) {
            throw new IllegalStateException("hosted workspace capacity reached");
        }
        String safeName = HostedPrincipal.text(name, "workspace name", 256);
        if (context.state().workspaces().stream().anyMatch(value -> value.name().equalsIgnoreCase(safeName))) {
            throw new IllegalArgumentException("workspace name already exists in tenant");
        }
        var now = clock.instant();
        SharedWorkspace created = new SharedWorkspace(
                UUID.randomUUID(), context.state().tenantId(), safeName,
                SharedWorkspace.Status.ACTIVE, now, now, null, List.of());
        writer.saveAllowed(
                context,
                context.state().members(),
                appended(context.state().workspaces(), created),
                context.state().retentionPolicy(),
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "WORKSPACE_CREATE", "WORKSPACE", created.workspaceId().toString());
        return created;
    }

    SharedWorkspace archive(String bearerToken, String requestId, UUID workspaceId) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.WORKSPACE_WRITE,
                "WORKSPACE_ARCHIVE", "WORKSPACE", workspaceId.toString());
        SharedWorkspace existing = requireWorkspace(context.state(), workspaceId);
        if (existing.status() == SharedWorkspace.Status.ARCHIVED) {
            writer.saveAllowed(
                    context,
                    context.state().members(),
                    context.state().workspaces(),
                    context.state().retentionPolicy(),
                    context.state().keyId(),
                    context.state().auditAnchorHash(),
                    context.state().auditEvents(),
                    context.state().auditSequence(),
                    "WORKSPACE_ARCHIVE", "WORKSPACE", workspaceId.toString());
            return existing;
        }
        var now = clock.instant();
        SharedWorkspace archived = new SharedWorkspace(
                existing.workspaceId(), existing.tenantId(), existing.name(),
                SharedWorkspace.Status.ARCHIVED, existing.createdAt(), now, now, existing.bindings());
        writer.saveAllowed(
                context,
                context.state().members(),
                replace(context.state().workspaces(), archived),
                context.state().retentionPolicy(),
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "WORKSPACE_ARCHIVE", "WORKSPACE", workspaceId.toString());
        return archived;
    }

    HostedProjectBinding bind(
            String bearerToken,
            String requestId,
            UUID workspaceId,
            UUID projectId,
            String snapshotId
    ) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.BINDING_WRITE,
                "PROJECT_BIND", "WORKSPACE", workspaceId.toString());
        SharedWorkspace workspace = requireActiveWorkspace(context.state(), workspaceId);
        bindingVerifier.requireSnapshot(projectId, snapshotId);
        if (workspace.bindings().stream().anyMatch(value -> value.projectId().equals(projectId))) {
            throw new IllegalArgumentException("project is already bound to workspace");
        }
        HostedProjectBinding binding = new HostedProjectBinding(
                projectId, snapshotId, clock.instant(), context.claims().principalId());
        SharedWorkspace updated = new SharedWorkspace(
                workspace.workspaceId(), workspace.tenantId(), workspace.name(), workspace.status(),
                workspace.createdAt(), clock.instant(), null, appended(workspace.bindings(), binding));
        writer.saveAllowed(
                context,
                context.state().members(),
                replace(context.state().workspaces(), updated),
                context.state().retentionPolicy(),
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "PROJECT_BIND", "PROJECT", projectId.toString());
        return binding;
    }

    void unbind(
            String bearerToken,
            String requestId,
            UUID workspaceId,
            UUID projectId
    ) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.BINDING_WRITE,
                "PROJECT_UNBIND", "WORKSPACE", workspaceId.toString());
        SharedWorkspace workspace = requireActiveWorkspace(context.state(), workspaceId);
        List<HostedProjectBinding> bindings = workspace.bindings().stream()
                .filter(value -> !value.projectId().equals(projectId))
                .toList();
        if (bindings.size() == workspace.bindings().size()) {
            throw new IllegalArgumentException("project binding not found");
        }
        SharedWorkspace updated = new SharedWorkspace(
                workspace.workspaceId(), workspace.tenantId(), workspace.name(), workspace.status(),
                workspace.createdAt(), clock.instant(), null, bindings);
        writer.saveAllowed(
                context,
                context.state().members(),
                replace(context.state().workspaces(), updated),
                context.state().retentionPolicy(),
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "PROJECT_UNBIND", "PROJECT", projectId.toString());
    }

    private static SharedWorkspace requireWorkspace(HostedTenantState state, UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return state.workspaces().stream()
                .filter(value -> value.workspaceId().equals(workspaceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "workspace not found in authenticated tenant"));
    }

    private static SharedWorkspace requireActiveWorkspace(HostedTenantState state, UUID workspaceId) {
        SharedWorkspace workspace = requireWorkspace(state, workspaceId);
        if (workspace.status() != SharedWorkspace.Status.ACTIVE) {
            throw new IllegalStateException("archived workspace cannot be mutated");
        }
        return workspace;
    }

    private static List<SharedWorkspace> replace(
            List<SharedWorkspace> values,
            SharedWorkspace replacement
    ) {
        List<SharedWorkspace> updated = new ArrayList<>(values.size());
        for (SharedWorkspace value : values) {
            updated.add(value.workspaceId().equals(replacement.workspaceId()) ? replacement : value);
        }
        return List.copyOf(updated);
    }

    private static <T> List<T> appended(List<T> values, T value) {
        List<T> updated = new ArrayList<>(values.size() + 1);
        updated.addAll(values);
        updated.add(value);
        return List.copyOf(updated);
    }
}

package com.minos.hosted;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Tenant membership lifecycle, capacity and last-owner invariants. */
final class HostedMembershipService {

    private final HostedAuthorizationService authorization;
    private final HostedTenantMutationWriter writer;
    private final Clock clock;

    HostedMembershipService(
            HostedAuthorizationService authorization,
            HostedTenantMutationWriter writer,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    List<HostedPrincipal> list(String bearerToken) throws IOException {
        HostedTenantState state = authorization.authorizeRead(
                bearerToken, HostedPermission.MEMBER_READ).state();
        return state.members().stream()
                .sorted(Comparator.comparing(HostedPrincipal::principalId))
                .toList();
    }

    HostedPrincipal grant(
            String bearerToken,
            String requestId,
            String principalId,
            String displayName,
            HostedRole role
    ) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.MEMBER_WRITE,
                "MEMBER_GRANT", "PRINCIPAL", principalId);
        String safePrincipal = HostedPrincipal.safeId(principalId, "principalId");
        HostedPrincipal membership = new HostedPrincipal(
                safePrincipal, displayName, Objects.requireNonNull(role, "role"), clock.instant());
        List<HostedPrincipal> members = new ArrayList<>(context.state().members());
        int existing = indexOf(members, safePrincipal);
        if (existing >= 0) {
            members.set(existing, membership);
        } else {
            if (members.size() >= HostedTenantState.MAX_MEMBERS) {
                throw new IllegalStateException("hosted member capacity reached");
            }
            members.add(membership);
        }
        requireOwner(members);
        writer.saveAllowed(
                context,
                List.copyOf(members),
                context.state().workspaces(),
                context.state().retentionPolicy(),
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "MEMBER_GRANT", "PRINCIPAL", safePrincipal);
        return membership;
    }

    void revoke(String bearerToken, String requestId, String principalId) throws IOException {
        HostedAuthorizationService.MutationContext context = authorization.authorizeMutation(
                bearerToken, requestId, HostedPermission.MEMBER_WRITE,
                "MEMBER_REVOKE", "PRINCIPAL", principalId);
        String safePrincipal = HostedPrincipal.safeId(principalId, "principalId");
        List<HostedPrincipal> members = context.state().members().stream()
                .filter(value -> !value.principalId().equals(safePrincipal))
                .toList();
        if (members.size() == context.state().members().size()) {
            throw new IllegalArgumentException("tenant member not found");
        }
        requireOwner(members);
        writer.saveAllowed(
                context,
                members,
                context.state().workspaces(),
                context.state().retentionPolicy(),
                context.state().keyId(),
                context.state().auditAnchorHash(),
                context.state().auditEvents(),
                context.state().auditSequence(),
                "MEMBER_REVOKE", "PRINCIPAL", safePrincipal);
    }

    private static int indexOf(List<HostedPrincipal> values, String principalId) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).principalId().equals(principalId)) {
                return index;
            }
        }
        return -1;
    }

    private static void requireOwner(List<HostedPrincipal> members) {
        if (members.stream().noneMatch(value -> value.role() == HostedRole.OWNER)) {
            throw new IllegalArgumentException("cannot remove or demote the last tenant owner");
        }
    }
}

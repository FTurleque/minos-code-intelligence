package com.minos.cli;

import com.minos.hosted.HmacHostedIdentityProvider;
import com.minos.hosted.HostedAuditSink;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared fixtures of the team command tests: an in-memory hosted store that records every call. */
final class TeamFixtures {

    private TeamFixtures() { }

    /** In-memory store that records every persistence call: any entry proves the service was reached. */
    static final class SpyStore implements HostedControlPlaneStore {
        final Map<UUID, HostedTenantState> states = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        IOException failure;

        @Override
        public void create(HostedTenantState state) throws IOException {
            record("create");
            states.put(state.tenantId(), state);
        }

        @Override
        public Optional<HostedTenantState> find(UUID tenantId) throws IOException {
            record("find");
            return Optional.ofNullable(states.get(tenantId));
        }

        @Override
        public void save(HostedTenantState state, long expectedVersion) throws IOException {
            record("save");
            states.put(state.tenantId(), state);
        }

        private void record(String call) throws IOException {
            calls.add(call);
            if (failure != null) throw failure;
        }
    }

    static HostedControlPlaneService service(SpyStore store) {
        return new HostedControlPlaneService(store, new HmacHostedIdentityProvider(keys()), keys(),
                (project, snapshot) -> { }, HostedAuditSink.embeddedNoop(), Clock.systemUTC());
    }

    static HostedTenantKeyProvider keys() {
        return (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, (byte) Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
    }

    /** Bootstraps a tenant and returns the owner's bearer token. */
    static String bootstrap(TeamCommand command, UUID tenant, StringBuilder output, StringBuilder error)
            throws IOException {
        assertEquals(0, command.run(new String[]{"bootstrap", "--tenant", tenant.toString(), "--name", "Acme",
                "--key-id", "key-a", "--owner", "alice", "--owner-name", "Alice",
                "--request-id", "req-bootstrap"}, output, error), error.toString());
        return extract(output.toString(), "bearerToken");
    }

    static String extract(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + field + "\\\":\\\"([^\\\"]+)\\\"").matcher(json);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}

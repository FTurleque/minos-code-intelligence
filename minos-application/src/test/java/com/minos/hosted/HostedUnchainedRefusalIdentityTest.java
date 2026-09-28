package com.minos.hosted;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S13: a refusal kept off the chain is distinguishable in its serialized form, never reuses a
 * chain sequence and never authenticates as a chained event, while chained events keep the
 * persisted HMAC form of the existing history and exports.
 */
class HostedUnchainedRefusalIdentityTest {
    private static final int CHAINED_REFUSALS_PER_WINDOW = 10;

    @Test
    void unchainedRefusalCarriesAnExplicitMarkerInItsSerializedForm() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        HostedAuditEvent unchained = refuseBeyondTheBudget(harness).event();
        HostedAuditEvent chained = harness.state().auditEvents().getLast();

        Map<String, String> serializedUnchained = serialize(unchained);
        Map<String, String> serializedChained = serialize(chained);

        assertEquals("UNCHAINED", serializedUnchained.get("chaining"), serializedUnchained.toString());
        assertEquals("CHAINED", serializedChained.get("chaining"), serializedChained.toString());
        assertEquals("0", serializedUnchained.get("sequence"), "an unchained refusal has no chain sequence");
    }

    @Test
    void unchainedRefusalNeverCollidesWithAChainedSequence() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        Refused refused = refuseBeyondTheBudget(harness);
        HostedAuditEvent unchained = refused.event();
        String owner = refused.owner();

        harness.service().createWorkspace(owner, "allowed-after", "Shared");

        HostedAuditEvent next = harness.state().auditEvents().getLast();
        assertEquals("allowed-after", next.requestId());
        assertTrue(harness.state().auditEvents().stream()
                        .noneMatch(event -> event.tenantId().equals(unchained.tenantId())
                                && event.sequence() == unchained.sequence()),
                "(tenant, sequence) of the unchained refusal collides with a chained event");
        assertTrue(harness.sink().events.stream().noneMatch(event -> event.sequence() == unchained.sequence()));
    }

    @Test
    void unchainedRefusalReinjectedAsIsIsRejectedByTheChain() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        HostedAuditEvent unchained = refuseBeyondTheBudget(harness).event();
        HostedTenantState state = harness.state();

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> withAppended(state, unchained, unchained.sequence()));
        assertTrue(rejected.getMessage().contains("unchained"), rejected.getMessage());
    }

    @Test
    void unchainedRefusalRetaggedAsTheNextChainedEventFailsAuthentication() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        HostedAuditEvent unchained = refuseBeyondTheBudget(harness).event();
        HostedTenantState state = harness.state();
        long nextSequence = state.auditSequence() + 1;
        HostedAuditEvent forged = new HostedAuditEvent(
                nextSequence, unchained.tenantId(), unchained.occurredAt(), unchained.principalId(),
                unchained.action(), unchained.resourceType(), unchained.resourceId(), unchained.outcome(),
                unchained.requestId(), unchained.keyId(), state.auditEvents().getLast().hash(), unchained.hash());
        HostedTenantState exported = withAppended(state, forged, nextSequence);

        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());
        SecurityException failure = assertThrows(SecurityException.class, () -> chain.verify(exported));
        assertEquals("hosted audit event authentication failed", failure.getMessage());
    }

    @Test
    void unchainedRefusalAuthenticatesOnlyInItsOwnDomain() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        HostedAuditEvent unchained = refuseBeyondTheBudget(harness).event();
        HostedAuditChain chain = new HostedAuditChain(harness.keys(), harness.clock());

        chain.verifyUnchained(unchained);
        HostedAuditEvent tampered = new HostedAuditEvent(
                unchained.sequence(), unchained.tenantId(), unchained.occurredAt(), unchained.principalId(),
                "MEMBER_GRANT", unchained.resourceType(), unchained.resourceId(), unchained.outcome(),
                unchained.requestId(), unchained.keyId(), unchained.previousHash(), unchained.hash(),
                HostedAuditEvent.Chaining.UNCHAINED);
        assertThrows(SecurityException.class, () -> chain.verifyUnchained(tampered));
        assertThrows(SecurityException.class,
                () -> chain.verifyUnchained(harness.state().auditEvents().getLast()),
                "a chained event is not an unchained refusal");
    }

    @Test
    void chainedEventsKeepThePersistedHmacFormOfTheExistingHistory() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        refuseBeyondTheBudget(harness);
        HostedTenantState state = harness.state();
        assertFalse(state.auditEvents().isEmpty());

        for (HostedAuditEvent event : state.auditEvents()) {
            String canonical = event.sequence() + "\0" + event.tenantId() + "\0"
                    + event.occurredAt().getEpochSecond() + "\0" + event.occurredAt().getNano() + "\0"
                    + event.principalId() + "\0" + event.action() + "\0" + event.resourceType() + "\0"
                    + event.resourceId() + "\0" + event.outcome() + "\0" + event.requestId() + "\0"
                    + event.keyId() + "\0" + event.previousHash();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(harness.keys().resolve(
                    event.tenantId(), event.keyId(), HostedTenantKeyProvider.Purpose.AUDIT_CHAIN));
            assertEquals(HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))),
                    event.hash(), "chained HMAC form changed for sequence " + event.sequence());
        }
        new HostedAuditChain(harness.keys(), harness.clock()).verify(state);
    }

    /** Bootstraps owner + viewer, exhausts the viewer refusal budget and returns the first unchained refusal. */
    private static Refused refuseBeyondTheBudget(HostedControlPlaneTestSupport.Harness harness) throws Exception {
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        for (int index = 0; index <= CHAINED_REFUSALS_PER_WINDOW; index++) {
            String requestId = "denied-" + index;
            assertThrows(SecurityException.class,
                    () -> harness.service().createWorkspace(viewer, requestId, "Forbidden"));
        }
        assertEquals(1, harness.sink().unchained.size());
        return new Refused(owner, harness.sink().unchained.getFirst());
    }

    private record Refused(String owner, HostedAuditEvent event) { }

    private static HostedTenantState withAppended(HostedTenantState state, HostedAuditEvent event, long sequence) {
        List<HostedAuditEvent> events = new ArrayList<>(state.auditEvents());
        events.add(event);
        return new HostedTenantState(state.tenantId(), state.name(), state.keyId(), state.version(),
                state.createdAt(), state.updatedAt(), state.retentionPolicy(), state.members(),
                state.workspaces(), sequence, state.auditAnchorHash(), events);
    }

    /** Generic record serialization, as an external audit consumer would perform it. */
    private static Map<String, String> serialize(HostedAuditEvent event) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        for (RecordComponent component : HostedAuditEvent.class.getRecordComponents()) {
            fields.put(component.getName(), String.valueOf(component.getAccessor().invoke(event)));
        }
        return fields;
    }
}

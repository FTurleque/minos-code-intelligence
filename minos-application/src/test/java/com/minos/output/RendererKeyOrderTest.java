package com.minos.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q6: the order of the keys of a rendered object is the order written in the renderer, on every JVM.
 * {@code Map.of} with two or more entries iterates in an order drawn at random when the JVM starts, so
 * each expectation below fails on roughly half of the launches (a single two-entry map) up to almost
 * all of them (four entries) when a renderer is built on it.
 */
class RendererKeyOrderTest {

    private static final ObjectMapper PARSER = new ObjectMapper();

    @Test
    void hostedWorkspaceListKeepsItsDeclaredKeyOrder() {
        assertEquals("{\"isolation\":\"TENANT_SCOPED\",\"workspaces\":[]}",
                HostedControlPlaneRenderer.renderWorkspaces(List.of()));
    }

    @Test
    void hostedMemberListKeepsItsDeclaredKeyOrder() {
        assertEquals("{\"isolation\":\"TENANT_SCOPED\",\"members\":[]}",
                HostedControlPlaneRenderer.renderMembers(List.of()));
    }

    @Test
    void hostedAuditListKeepsItsDeclaredKeyOrder() {
        assertEquals("{\"integrity\":\"HMAC_SHA256_CHAINED\",\"events\":[]}",
                HostedControlPlaneRenderer.renderAudit(List.of()));
    }

    @Test
    void runtimeSessionListKeepsItsDeclaredKeyOrder() throws Exception {
        String json = RuntimeIntelligenceRenderer.renderSessions(List.of());

        assertEquals(List.of("nature", "exhaustive", "sessions", "limitations"), keys(json));
        JsonNode parsed = PARSER.readTree(json);
        assertEquals("OBSERVED_PARTIAL", parsed.get("nature").textValue());
        assertEquals(false, parsed.get("exhaustive").booleanValue());
        assertEquals(0, parsed.get("sessions").size());
        assertEquals(1, parsed.get("limitations").size());
    }

    private static List<String> keys(String json) throws Exception {
        List<String> names = new ArrayList<>();
        PARSER.readTree(json).fieldNames().forEachRemaining(names::add);
        return names;
    }
}

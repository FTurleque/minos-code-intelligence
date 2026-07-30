package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedCodeIntelligenceApiContractTest {

    @Test
    void exposesVersionedAdditiveProgramGraphWithoutChangingMinosApiV1(@TempDir Path temp) throws Exception {
        MinosApplication application = MinosApplication.open(temp.resolve("home"));
        Path root = Files.createDirectories(temp.resolve("project"));
        String fileId = "src/main/java/fixture/AdvancedFixture.java";
        Path source = root.resolve(fileId);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;
                final class AdvancedFixture {
                    int caller(int input) {
                        int copy = input;
                        return callee(copy);
                    }
                    int callee(int value) {
                        return value;
                    }
                }
                """, StandardCharsets.UTF_8);
        var project = application.projectRegistry().registerProject(root, "advanced-api");
        Origin origin = new Origin("fixture", "TEST", "1", "snapshot-api", OriginType.OTHER);
        Symbol caller = symbol(project.id().toString(), "caller", fileId, origin);
        Symbol callee = symbol(project.id().toString(), "callee", fileId, origin);
        Relationship call = new Relationship(
                "call-1", project.id().toString(),
                new CodeEntityRef(CodeEntityType.SYMBOL, caller.id()),
                new CodeEntityRef(CodeEntityType.SYMBOL, callee.id()),
                null, RelationshipKind.CALLS, null, ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL, null, origin, List.of());
        application.snapshotStore().publish(
                project.id(), "snapshot-api", List.of(caller, callee), List.of(), List.of(call));
        application.fingerprintStore().publish(
                project.id(), "snapshot-api", application.fingerprintService().capture(root));
        application.fingerprintStore().promote(project.id(), "snapshot-api");

        AdvancedCodeIntelligenceApi api = new LocalAdvancedCodeIntelligenceApi(application);
        var graph = api.getProgramGraph(
                project.id().toString(), AdvancedCodeIntelligenceApi.ProgramGraphQuery.defaults());

        assertEquals("1", api.contractVersion());
        assertEquals("1", AdvancedCodeIntelligenceApi.CONTRACT_VERSION);
        assertEquals("1", MinosApi.CONTRACT_VERSION);
        assertTrue(graph.capabilities().contains("CALL_GRAPH"));
        assertTrue(graph.capabilities().contains("CONTROL_FLOW"));
        assertTrue(graph.capabilities().contains("LOCAL_DATA_FLOW"));
        assertTrue(graph.capabilities().contains("CPG"));
        assertEquals(1, graph.edges().stream().filter(edge -> "CALL".equals(edge.kind())).count());
        assertTrue(graph.edges().stream().anyMatch(edge ->
                "minos-java-source-v1".equals(edge.origin().providerId())));
        assertThrows(UnsupportedOperationException.class, () -> graph.limitations().add("mutate"));
    }

    @Test
    void publicQueriesEnforceSafetyBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancedCodeIntelligenceApi.ProgramGraphQuery(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancedCodeIntelligenceApi.AdvancedImpactQuery("symbol", 33, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancedCodeIntelligenceApi.SecurityQuery(null, 1, 1001));
    }

    private static Symbol symbol(String projectId, String id, String fileId, Origin origin) {
        SymbolLocation location = new SymbolLocation(
                fileId, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS);
        return new Symbol(
                id, "fixture:" + id, SymbolIdentityQuality.CANONICAL, projectId,
                "module", fileId, null, SymbolKind.METHOD, id, "fixture.AdvancedFixture." + id,
                "(int)", "java", location, ResolutionStatus.RESOLVED, origin, false, false, Set.of());
    }
}

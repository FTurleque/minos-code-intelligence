package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        var project = application.projectRegistry().registerProject(root, "advanced-api");
        Origin origin = new Origin("fixture", "TEST", "1", "run", OriginType.OTHER);
        Symbol caller = symbol(project.id().toString(), "caller", origin);
        Symbol callee = symbol(project.id().toString(), "callee", origin);
        Relationship call = new Relationship(
                "call-1", project.id().toString(),
                new CodeEntityRef(CodeEntityType.SYMBOL, caller.id()),
                new CodeEntityRef(CodeEntityType.SYMBOL, callee.id()),
                null, RelationshipKind.CALLS, null, ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL, null, origin, List.of());
        application.snapshotStore().publish(
                project.id(), "snapshot-api", List.of(caller, callee), List.of(), List.of(call));

        AdvancedCodeIntelligenceApi api = new LocalAdvancedCodeIntelligenceApi(application);
        var graph = api.getProgramGraph(project.id().toString(), AdvancedCodeIntelligenceApi.ProgramGraphQuery.defaults());

        assertEquals("1", api.contractVersion());
        assertEquals("1", AdvancedCodeIntelligenceApi.CONTRACT_VERSION);
        assertEquals("1", MinosApi.CONTRACT_VERSION);
        assertTrue(graph.capabilities().contains("CALL_GRAPH"));
        assertTrue(graph.capabilities().contains("CPG"));
        assertEquals(1, graph.edges().stream().filter(edge -> "CALL".equals(edge.kind())).count());
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

    private static Symbol symbol(String projectId, String id, Origin origin) {
        return new Symbol(
                id, "fixture:" + id, SymbolIdentityQuality.CANONICAL, projectId,
                "module", "src/Fixture.java", null, SymbolKind.METHOD, id, "fixture." + id,
                "()", "java", null, ResolutionStatus.RESOLVED, origin, false, false, Set.of());
    }
}

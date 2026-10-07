package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-F01 sur MCP : les outils d'appelants, d'appelés et de dépendances déclarent ce que le snapshot ne leur
 * permet pas de voir ; l'outil d'implémentations garde la réponse historique. Branchement réel, snapshot réel.
 */
class MinosMcpRelationshipLimitationsTest {
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @TempDir
    Path home;

    @Test
    void callersCalleesAndDependentsDeclareTheirLimitationWhereImplementationsStayUnchanged() throws Exception {
        try (MinosApplication application = MinosApplication.open(home.resolve("app"))) {
            RegisteredProject project = application.projectRegistry().registerProject(
                    Files.createDirectories(home.resolve("project")), "f01");
            application.snapshotStore().publish(project.id(), "snapshot-f01",
                    List.of(symbol(project, "a"), symbol(project, "b")),
                    List.of(new SymbolOccurrence("occurrence-1", project.id().toString(), new ResolvedSymbolReference("a"),
                            new SymbolLocation("src/b.java", 3, 0, 3, 4, PositionEncoding.UTF16_CODE_UNITS),
                            Set.of(OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED, ORIGIN, Set.of())),
                    List.of(new Relationship("relationship-1", project.id().toString(), ref("b"), ref("a"), null,
                            RelationshipKind.IMPLEMENTS, null, ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null,
                            ORIGIN, List.of())));
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));
            Map<String, Object> arguments = Map.of("project", "f01", "symbolId", "a");

            for (String tool : new String[]{"minos_find_callers", "minos_find_callees"}) {
                String text = McpToolCalls.text(McpToolCalls.call(tools, tool, arguments));
                assertTrue(text.endsWith(",\"limitations\":[\"CALL_RELATIONS_NOT_PRODUCED\"]}"), tool + " -> " + text);
                assertTrue(text.startsWith("{\"count\":0,\"relationships\":[]"), tool + " -> " + text);
            }
            for (String tool : new String[]{"minos_dependents", "minos_dependencies"}) {
                String text = McpToolCalls.text(McpToolCalls.call(tools, tool, arguments));
                assertTrue(text.endsWith(",\"limitations\":[\"OCCURRENCE_REFERENCES_NOT_PROJECTED\"]}"),
                        tool + " -> " + text);
            }
            String implementations = McpToolCalls.text(McpToolCalls.call(tools, "minos_find_implementations", arguments));
            assertFalse(implementations.contains("limitations"), implementations);
            assertTrue(implementations.startsWith("{\"count\":1,\"relationships\":["), implementations);
        }
    }

    private static CodeEntityRef ref(String symbolId) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbolId);
    }

    private static Symbol symbol(RegisteredProject project, String id) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.id().toString(), null,
                "src/" + id + ".java", null, SymbolKind.CLASS, id, "com.acme." + id, null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}

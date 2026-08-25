package com.minos.output;

import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicJsonBudgetTest {

    @Test
    void measuresUtf8BytesRatherThanUtf16Characters() {
        assertEquals("{\"value\":\"é\"}",
                DeterministicJson.render(java.util.Map.of("value", "é"), 14));
        assertThrows(DeterministicJson.OutputBudgetExceededException.class,
                () -> DeterministicJson.render(java.util.Map.of("value", "é"), 13));
    }

    @Test
    void programGraphRenderingFailsBeforeProducingOversizedJson() {
        Origin origin = new Origin("fixture", "TEST", "1", "run", OriginType.OTHER);
        ProgramGraphNode node = new ProgramGraphNode(
                "node-1", "project", "symbol-1", ProgramNodeKind.SYMBOL,
                "é".repeat(4096), null, InformationNature.FACTUAL, null, origin, List.of());
        ProgramGraph graph = new ProgramGraph(
                "project", "snapshot", Set.of(), List.of(node), List.of(), List.of());

        assertThrows(DeterministicJson.OutputBudgetExceededException.class,
                () -> AdvancedAnalysisResultRenderer.renderProgramGraph(graph, 1024));
    }

    @Test
    void normalBoundedJsonRemainsValidAndWithinTheExactBudget() {
        String rendered = DeterministicJson.render(java.util.Map.of("ok", true), 11);

        assertEquals("{\"ok\":true}", rendered);
        assertEquals(11, rendered.getBytes(StandardCharsets.UTF_8).length);
    }
}

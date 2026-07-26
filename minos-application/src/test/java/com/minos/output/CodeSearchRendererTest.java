package com.minos.output;

import com.minos.context.CodeContextResult;
import com.minos.context.CodeSearchResponse;
import com.minos.context.SourceExcerpt;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.query.SymbolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchRendererTest {

    @Test
    void rendersCompactMetricsAndEscapesSourceJson() {
        SourceExcerpt source = new SourceExcerpt(
                "src/Test.java", 1, 1, "class \"Test\" {\n// \uD800\n}",
                false, false, 7, 3, 100);
        CodeContextResult context = new CodeContextResult(
                symbol(), source, List.of(), List.of(), 80, false);
        CodeSearchResponse response = new CodeSearchResponse(
                "project-1", "Test", 1, 512, 104, 93, false, List.of(context));

        String json = CodeSearchRenderer.render(response, SymbolOutputFormat.JSON);

        assertTrue(json.startsWith("{\"projectId\":\"project-1\""));
        assertTrue(json.contains("\"estimatedTokensAvoided\":93"));
        assertTrue(json.contains("class \\\"Test\\\""));
        assertTrue(json.contains("\\ud800"));
        assertTrue(json.contains("\"relationships\":[]"));
    }

    @Test
    void rendersExplicitFullSource() {
        String json = CodeSearchRenderer.renderSource(new SourceExcerpt(
                "src/Test.java", 1, 2, "a\nb", true, false, 1, 2, 1),
                SymbolOutputFormat.JSON);

        assertTrue(json.contains("\"fullFile\":true"));
        assertTrue(json.contains("\"content\":\"a\\nb\""));
    }

    private static SymbolResult symbol() {
        return new SymbolResult(
                "symbol-test", "key-test", SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                "project-1", "main", "src/Test.java", SymbolKind.CLASS,
                "Test", "com.minos.Test", null, "java", null,
                ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run", OriginType.OTHER),
                false, false
        );
    }
}

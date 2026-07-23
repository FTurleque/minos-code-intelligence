package com.minos.output;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.query.SymbolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolResultRendererTest {

    @Test
    void rendersStableTextGoldenOutput() {
        String expected = """
                symbols: 1

                symbol:
                  id: "sym-1"
                  symbolKey: "key-1"
                  identityQuality: CANONICAL
                  projectId: "project-1"
                  moduleId: "module-main"
                  fileId: "file-1"
                  kind: CLASS
                  name: "Service"
                  qualifiedName: "com.example.Service"
                  signature: null
                  language: "java"
                  location:
                    fileId: "file-1"
                    startLine: 3
                    startColumn: 1
                    endLine: 9
                    endColumn: 2
                    positionEncoding: UTF16_CODE_UNITS
                  resolutionStatus: RESOLVED
                  origin:
                    providerId: "scip-java"
                    providerType: "SCIP_INDEXER"
                    providerVersion: "1.2.0"
                    indexRunId: "run-1"
                    sourceType: SCIP
                  external: false
                  generated: false""";

        assertEquals(expected, SymbolResultRenderer.render(List.of(localResult()), SymbolOutputFormat.TEXT));
    }

    @Test
    void rendersStableJsonGoldenOutput() {
        String expected = "{\"count\":1,\"symbols\":[{"
                + "\"id\":\"sym-1\","
                + "\"symbolKey\":\"key-1\","
                + "\"identityQuality\":\"CANONICAL\","
                + "\"projectId\":\"project-1\","
                + "\"moduleId\":\"module-main\","
                + "\"fileId\":\"file-1\","
                + "\"kind\":\"CLASS\","
                + "\"name\":\"Service\","
                + "\"qualifiedName\":\"com.example.Service\","
                + "\"signature\":null,"
                + "\"language\":\"java\","
                + "\"location\":{"
                + "\"fileId\":\"file-1\","
                + "\"startLine\":3,"
                + "\"startColumn\":1,"
                + "\"endLine\":9,"
                + "\"endColumn\":2,"
                + "\"positionEncoding\":\"UTF16_CODE_UNITS\"},"
                + "\"resolutionStatus\":\"RESOLVED\","
                + "\"origin\":{"
                + "\"providerId\":\"scip-java\","
                + "\"providerType\":\"SCIP_INDEXER\","
                + "\"providerVersion\":\"1.2.0\","
                + "\"indexRunId\":\"run-1\","
                + "\"sourceType\":\"SCIP\"},"
                + "\"external\":false,"
                + "\"generated\":false}]}";

        assertEquals(expected, SymbolResultRenderer.render(List.of(localResult()), SymbolOutputFormat.JSON));
    }

    @Test
    void rendersAbsentExternalFieldsExplicitly() {
        SymbolResult external = new SymbolResult(
                "sym-string",
                "provider-key",
                SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK,
                "project-1",
                null,
                null,
                SymbolKind.CLASS,
                "String",
                "java.lang.String",
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin("scip-java", "SCIP_INDEXER", null, null, OriginType.SCIP),
                true,
                false
        );

        String json = SymbolResultRenderer.render(List.of(external), SymbolOutputFormat.JSON);
        String text = SymbolResultRenderer.render(List.of(external), SymbolOutputFormat.TEXT);

        assertTrue(json.contains("\"moduleId\":null,\"fileId\":null"));
        assertTrue(json.contains("\"location\":null"));
        assertTrue(json.contains("\"providerVersion\":null,\"indexRunId\":null"));
        assertTrue(json.contains("\"external\":true"));
        assertTrue(text.contains("  moduleId: null\n  fileId: null"));
        assertTrue(text.contains("  location: null"));
    }

    @Test
    void escapesJsonAndTextWithoutDiscardingUnicode() {
        String specialSignature = "Résumé \"ok\" \\ next\nline\t\u0001\u2028" + (char) 0xD800;
        SymbolResult special = copyWithSignature(localResult(), specialSignature);

        String json = SymbolResultRenderer.render(List.of(special), SymbolOutputFormat.JSON);
        String text = SymbolResultRenderer.render(List.of(special), SymbolOutputFormat.TEXT);

        String escaped = "Résumé \\\"ok\\\" \\\\ next\\nline\\t\\u0001\\u2028\\ud800";
        assertTrue(json.contains("\"signature\":\"" + escaped + "\""));
        assertTrue(text.contains("  signature: \"" + escaped + "\""));
    }

    @Test
    void keepsInputOrderAndHandlesEmptyResultsDeterministically() {
        assertEquals("symbols: 0", SymbolResultRenderer.render(List.of(), SymbolOutputFormat.TEXT));
        assertEquals("{\"count\":0,\"symbols\":[]}",
                SymbolResultRenderer.render(List.of(), SymbolOutputFormat.JSON));

        SymbolResult second = copyWithId(localResult(), "sym-2");
        List<SymbolResult> results = List.of(second, localResult());
        String firstRendering = SymbolResultRenderer.render(results, SymbolOutputFormat.JSON);

        assertEquals(firstRendering, SymbolResultRenderer.render(results, SymbolOutputFormat.JSON));
        assertTrue(firstRendering.indexOf("sym-2") < firstRendering.indexOf("sym-1"));
    }

    @Test
    void parsesFormatsAndRejectsInvalidInputs() {
        assertEquals(SymbolOutputFormat.TEXT, SymbolOutputFormat.parse(" text "));
        assertEquals(SymbolOutputFormat.JSON, SymbolOutputFormat.parse("JSON"));
        assertThrows(IllegalArgumentException.class, () -> SymbolOutputFormat.parse("yaml"));
        assertThrows(IllegalArgumentException.class, () -> SymbolOutputFormat.parse(" "));
        assertThrows(NullPointerException.class,
                () -> SymbolResultRenderer.render(null, SymbolOutputFormat.JSON));
        assertThrows(NullPointerException.class,
                () -> SymbolResultRenderer.render(List.of(localResult()), null));
    }

    private static SymbolResult localResult() {
        return new SymbolResult(
                "sym-1",
                "key-1",
                SymbolIdentityQuality.CANONICAL,
                "project-1",
                "module-main",
                "file-1",
                SymbolKind.CLASS,
                "Service",
                "com.example.Service",
                null,
                "java",
                new SymbolLocation("file-1", 3, 1, 9, 2, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                new Origin("scip-java", "SCIP_INDEXER", "1.2.0", "run-1", OriginType.SCIP),
                false,
                false
        );
    }

    private static SymbolResult copyWithSignature(SymbolResult source, String signature) {
        return new SymbolResult(
                source.id(), source.symbolKey(), source.identityQuality(), source.projectId(),
                source.moduleId(), source.fileId(), source.kind(), source.name(), source.qualifiedName(),
                signature, source.language(), source.location(), source.resolutionStatus(), source.origin(),
                source.external(), source.generated()
        );
    }

    private static SymbolResult copyWithId(SymbolResult source, String id) {
        return new SymbolResult(
                id, source.symbolKey(), source.identityQuality(), source.projectId(), source.moduleId(),
                source.fileId(), source.kind(), source.name(), source.qualifiedName(), source.signature(),
                source.language(), source.location(), source.resolutionStatus(), source.origin(),
                source.external(), source.generated()
        );
    }
}

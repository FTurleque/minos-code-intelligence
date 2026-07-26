package com.minos.output;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolLocation;
import com.minos.query.RelationshipResult;
import com.minos.query.UsageResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIntelligenceResultRendererTest {

    @Test
    void rendersUsageRolesDeterministicallyInJson() {
        String json = CodeIntelligenceResultRenderer.renderUsages(
                List.of(new UsageResult(
                        "occ-1", "project-1", "symbol-1", location(),
                        Set.of(OccurrenceRole.READ, OccurrenceRole.CALL, OccurrenceRole.REFERENCE),
                        ResolutionStatus.RESOLVED, origin()
                )),
                SymbolOutputFormat.JSON
        );

        assertTrue(json.startsWith("{\"count\":1,\"usages\":[{"));
        assertTrue(json.contains("\"roles\":[\"REFERENCE\",\"CALL\",\"READ\"]"));
        assertTrue(json.contains("\"providerId\":\"fixture-provider\""));
    }

    @Test
    void rendersRelationshipEvidenceAndEscapesMalformedUtf16() {
        CodeEntityRef source = symbol("source");
        CodeEntityRef target = symbol("target");
        RelationshipResult relationship = new RelationshipResult(
                "rel-1", "project-1", source, target, null,
                RelationshipKind.DEPENDS_ON, location(), ResolutionStatus.RESOLVED,
                InformationNature.DERIVED, 0.8, origin(),
                List.of(new Evidence(
                        EvidenceType.DERIVATION_PATH,
                        "path-\uD800-proof",
                        source,
                        target,
                        location(),
                        0.8
                ))
        );

        String json = CodeIntelligenceResultRenderer.renderRelationships(
                List.of(relationship),
                SymbolOutputFormat.JSON
        );

        assertTrue(json.contains("\"kind\":\"DEPENDS_ON\""));
        assertTrue(json.contains("\"confidence\":0.8"));
        assertTrue(json.contains("\"type\":\"DERIVATION_PATH\""));
        assertTrue(json.contains("path-\\ud800-proof"));
        assertFalse(json.contains("opaque-provider-id"));

        String text = CodeIntelligenceResultRenderer.renderRelationships(
                List.of(relationship), SymbolOutputFormat.TEXT);
        assertTrue(text.contains("evidence: DERIVATION_PATH (weight=0.8)"));
        assertTrue(text.contains("path-"));
    }

    @Test
    void rendersStableEmptyTextCollections() {
        assertEquals("usages: 0", CodeIntelligenceResultRenderer.renderUsages(
                List.of(), SymbolOutputFormat.TEXT));
        assertEquals("relationships: 0", CodeIntelligenceResultRenderer.renderRelationships(
                List.of(), SymbolOutputFormat.TEXT));
    }

    private static CodeEntityRef symbol(String id) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, id);
    }

    private static SymbolLocation location() {
        return new SymbolLocation(
                "src/Test.java", 4, 2, 4, 10, PositionEncoding.UTF16_CODE_UNITS);
    }

    private static Origin origin() {
        return new Origin(
                "fixture-provider", "TEST", "1", "run-1", OriginType.OTHER);
    }
}

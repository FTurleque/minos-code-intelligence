package com.minos.impact;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AUD-PERF-11 : les rapports d'impact restent identiques, ordre compris, à la référence figée avant que l'index
 * des arêtes entrantes ne soit porté par la vue de requête.
 *
 * <p>{@code -Dminos.impactReference.write=<fichier>} écrit le rendu courant au lieu de le comparer.</p>
 */
class ImpactReferenceReportsTest {

    static final String REFERENCE = "/impact/reference-reports.txt";
    private static final String WRITE_PROPERTY = "minos.impactReference.write";

    @Test
    void snapshotPathRendersTheReference() throws Exception {
        CodeKnowledgeSnapshot snapshot = ImpactReferenceFixture.snapshot();
        ImpactAnalysisService service = new ImpactAnalysisService();
        StringBuilder rendered = new StringBuilder();
        for (ImpactAnalysisRequest request : ImpactReferenceFixture.requests()) {
            rendered.append(ImpactReferenceFixture.digestLine(service.analyze(snapshot, request)));
        }
        String target = System.getProperty(WRITE_PROPERTY);
        if (target != null && !target.isBlank()) {
            Files.writeString(Path.of(target), rendered, StandardCharsets.UTF_8);
            return;
        }
        assertEquals(ImpactReferenceFixture.reference(REFERENCE), rendered.toString());
    }
}

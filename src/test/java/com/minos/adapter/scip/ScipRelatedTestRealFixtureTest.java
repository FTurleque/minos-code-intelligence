package com.minos.adapter.scip;

import com.minos.store.InMemoryCodeKnowledgeStore;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipRelatedTestRealFixtureTest {

    @Test
    void replaysVersionedTypeScriptIndexes() throws Exception {
        Map<String, Boolean> fixtures = new LinkedHashMap<>();
        fixtures.put("typescript-simple", true);
        fixtures.put("typescript-inheritance", true);
        fixtures.put("typescript-modules", true);
        fixtures.put("typescript-unresolved", false);

        for (Map.Entry<String, Boolean> fixture : fixtures.entrySet()) {
            Path indexPath = Path.of(
                    "fixtures", "typescript", fixture.getKey(),
                    ".minos-m0", "scip-typescript", "index.scip");
            Index index = new ScipIndexReader().read(indexPath);
            Map<String, String> fileIds = index.getDocumentsList().stream()
                    .collect(Collectors.toMap(
                            Document::getRelativePath,
                            Document::getRelativePath,
                            (first, ignored) -> first,
                            LinkedHashMap::new
                    ));
            ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                    index,
                    new ScipIngestionRequest(
                            "real-" + fixture.getKey(),
                            "main",
                            "scip-typescript",
                            "0.4.0",
                            "m5-real-fixture",
                            fileIds
                    ),
                    new InMemoryCodeKnowledgeStore()
            );

            if (fixture.getValue()) {
                assertTrue(report.relatedTestRelationshipCount() > 0, fixture.getKey());
                assertTrue(
                        report.derivedRelationshipCount()
                                >= report.relatedTestRelationshipCount(),
                        fixture.getKey()
                );
            } else {
                assertEquals(0, report.relatedTestRelationshipCount(), fixture.getKey());
            }
        }
    }
}

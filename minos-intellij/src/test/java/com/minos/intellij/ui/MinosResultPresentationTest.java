package com.minos.intellij.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** client-intellij › La présentation des réponses du CLI par le plugin est testée hors des classes d'interface. */
class MinosResultPresentationTest {

    @Test
    void locationsOfANestedResponseAreCollectedInDocumentOrderWithTheirPath() {
        JsonObject result = json("""
                {"symbol": {"fileId": "src/A.java", "startLine": 3, "startColumn": 4,
                            "positionEncoding": "UTF16_CODE_UNITS"},
                 "usages": [
                   {"location": {"fileId": "src/B.java", "startLine": 10, "startColumn": 0,
                                 "positionEncoding": "UTF16_CODE_UNITS"}},
                   {"note": "no location here"},
                   {"location": {"fileId": "src/C.java", "startLine": 1, "startColumn": 2,
                                 "positionEncoding": "UTF8_CODE_UNITS"}}
                 ]}""");

        List<MinosResultPresentation.ResultLocation> locations = MinosResultPresentation.locations(result);

        assertEquals(List.of("$.symbol", "$.usages[0].location", "$.usages[2].location"),
                locations.stream().map(MinosResultPresentation.ResultLocation::sourcePath).toList());
        assertEquals("src/B.java:10:0 — $.usages[0].location", locations.get(1).toString());
    }

    @Test
    void aMalformedLocationIsSkippedWithoutHidingTheOthers() {
        JsonObject result = json("""
                {"bad": {"fileId": "", "startLine": 0, "startColumn": 0, "positionEncoding": "UTF16_CODE_UNITS"},
                 "good": {"fileId": "src/A.java", "startLine": 1, "startColumn": 0,
                          "positionEncoding": "UTF16_CODE_UNITS"}}""");

        assertEquals(List.of("$.good"), MinosResultPresentation.locations(result).stream()
                .map(MinosResultPresentation.ResultLocation::sourcePath).toList());
        assertTrue(MinosResultPresentation.locations(null).isEmpty());
    }

    @Test
    void theConnectionLineNamesTheProjectItsIndexStateAndProvider() {
        assertEquals("MINOS: connected — demo — READY — provider scip-java", MinosResultPresentation.connectionText(
                json("{\"project\": {\"name\": \"demo\"}, \"index\": {\"state\": \"READY\", \"providerId\": \"scip-java\"}}")));
        assertEquals("MINOS: connected — ? — UNKNOWN — provider -", MinosResultPresentation.connectionText(
                json("{\"project\": {}, \"index\": \"not an object\"}")));
        assertEquals("MINOS: connected — IntelliJ project is not registered",
                MinosResultPresentation.connectionText(json("{}")));
    }

    @Test
    void theArchitectureSummaryShowsItsCountsAndFallsBackOnMissingFields() {
        String summary = MinosResultPresentation.architectureSummary(json("""
                {"projectName": "demo", "snapshotId": "s-1", "nature": "FACT",
                 "moduleCount": 4, "relationshipCount": 9}"""), 3, 120);

        assertEquals(List.of("Project: demo", "Snapshot: s-1", "Nature: FACT", "Modules: 4",
                "Visible nodes: 3 (bound 120)", "Relationships: 9"), summary.lines().limit(6).toList());
        assertEquals(List.of("Project: ?", "Snapshot: ?", "Nature: ?", "Modules: 0"),
                MinosResultPresentation.architectureSummary(json("{\"projectName\": null}"), 0, 10)
                        .lines().limit(4).toList());
    }

    @Test
    void theModuleSummaryShowsItsFieldsAndFallsBackOnMissingOnes() {
        assertEquals(String.join("\n", "Module: core", "Id: m-1", "Path: core", "Build systems: [\"MAVEN\"]",
                        "Languages: [\"JAVA\"]", "Source roots: 2", "Symbols: 40", "Namespaces: 3"),
                MinosResultPresentation.moduleSummary(json("""
                        {"name": "core", "id": "m-1", "relativePath": "core", "buildSystems": ["MAVEN"],
                         "languages": ["JAVA"], "sourceRootCount": 2, "symbolCount": 40, "namespaceCount": 3}""")));
        assertEquals(String.join("\n", "Module: ?", "Id: ?", "Path: ", "Build systems: []", "Languages: []",
                "Source roots: 0", "Symbols: 0", "Namespaces: 0"), MinosResultPresentation.moduleSummary(json("{}")));
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}

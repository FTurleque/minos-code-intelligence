package com.minos.intellij.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosSymbolRankingTest {

    private static JsonObject symbol(String id, String fileId, Integer startLine, String resolution) {
        JsonObject symbol = new JsonObject();
        symbol.addProperty("id", id);
        if (fileId != null) symbol.addProperty("fileId", fileId);
        if (startLine != null) {
            JsonObject location = new JsonObject();
            location.addProperty("startLine", startLine);
            symbol.add("location", location);
        }
        if (resolution != null) symbol.addProperty("resolutionStatus", resolution);
        return symbol;
    }

    @Test
    void theSymbolInTheCaretFileAtTheCaretLineWins() {
        JsonArray symbols = new JsonArray();
        symbols.add(symbol("elsewhere", "src/Other.java", 10, "RESOLVED"));
        symbols.add(symbol("same-file-far", "src/Main.java", 80, null));
        symbols.add(symbol("here", "src/Main.java", 10, null));

        assertEquals("here", MinosSymbolRanking.best(symbols, "src/Main.java", 10).orElseThrow().get("id").getAsString());
    }

    @Test
    void scoresTheFileTheLineAndTheResolution() {
        assertEquals(1000, MinosSymbolRanking.score(symbol("a", null, null, null), "src/A.java", 3));
        assertEquals(300, MinosSymbolRanking.score(symbol("a", "src/A.java", null, null), "src/A.java", 3));
        // A file id may carry a prefix before the project-relative path, and Windows separators.
        assertEquals(300, MinosSymbolRanking.score(symbol("a", "module\\src\\A.java", null, null), "src/A.java", 3));
        assertEquals(800, MinosSymbolRanking.score(symbol("a", null, 3, null), "src/A.java", 3));
        assertEquals(1005, MinosSymbolRanking.score(symbol("a", null, 8, null), "src/A.java", 3));
        assertEquals(1200, MinosSymbolRanking.score(symbol("a", null, 900, null), "src/A.java", 3), "distance capped at 200");
        assertEquals(950, MinosSymbolRanking.score(symbol("a", null, null, "RESOLVED"), "src/A.java", 3));
        assertEquals(1000, MinosSymbolRanking.score(symbol("a", "src/B.java", null, "UNRESOLVED"), null, 3));
    }

    @Test
    void equalScoresKeepTheFirstSymbolReturnedByMinos() {
        JsonArray symbols = new JsonArray();
        symbols.add(symbol("first", "src/A.java", 5, null));
        symbols.add(symbol("second", "src/A.java", 5, null));

        assertEquals("first", MinosSymbolRanking.best(symbols, "src/A.java", 5).orElseThrow().get("id").getAsString());
    }

    @Test
    void noUsableCandidateIsAnEmptyResult() {
        JsonArray symbols = JsonParser.parseString("[1, \"text\", null]").getAsJsonArray();

        assertEquals(Optional.empty(), MinosSymbolRanking.best(symbols, "src/A.java", 1));
        assertEquals(Optional.empty(), MinosSymbolRanking.best(new JsonArray(), "src/A.java", 1));
    }

    @Test
    void aFileOutsideTheProjectHasNoRelativePath() {
        Path root = Path.of("project").toAbsolutePath();

        assertEquals("src/A.java", MinosSymbolRanking.relative(root, root.resolve("src").resolve("A.java")));
        assertNull(MinosSymbolRanking.relative(root, root.resolveSibling("other").resolve("A.java")));
        assertTrue(MinosSymbolRanking.relative(root, root.resolve("x/../B.java")).equals("B.java"));
    }
}

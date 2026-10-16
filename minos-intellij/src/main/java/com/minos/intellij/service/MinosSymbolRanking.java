package com.minos.intellij.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minos.intellij.protocol.MinosJsonFields;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Chooses, among the symbols MINOS returns for a name, the one the caret designates: same file first, then the closest
 * start line, then a resolved symbol. No IntelliJ type: tested in plain JUnit (AUD-TST-05).
 */
public final class MinosSymbolRanking {

    private MinosSymbolRanking() {
    }

    /** The best-ranked symbol object of {@code symbols}; on equal scores, the first returned by MINOS. */
    public static Optional<JsonObject> best(JsonArray symbols, String relativeFile, int caretLine) {
        return StreamSupport.stream(symbols.spliterator(), false)
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .min(Comparator.comparingInt(symbol -> score(symbol, relativeFile, caretLine)));
    }

    /** Lower is better: 1000, minus 700 in the caret file, plus the line distance (at most 200), minus 200 on it. */
    static int score(JsonObject symbol, String relativeFile, int caretLine) {
        int score = 1000;
        String fileId = MinosJsonFields.nullable(symbol, "fileId");
        if (relativeFile != null && fileId != null) {
            String normalizedFile = fileId.replace('\\', '/');
            String normalizedRelative = relativeFile.replace('\\', '/');
            if (normalizedFile.equals(normalizedRelative) || normalizedFile.endsWith("/" + normalizedRelative)) {
                score -= 700;
            }
        }
        JsonElement locationElement = symbol.get("location");
        if (locationElement != null && locationElement.isJsonObject()) {
            JsonObject location = locationElement.getAsJsonObject();
            int start = location.get("startLine").getAsInt();
            score += Math.min(200, Math.abs(start - caretLine));
            if (start == caretLine) {
                score -= 200;
            }
        }
        if ("RESOLVED".equals(MinosJsonFields.nullable(symbol, "resolutionStatus"))) {
            score -= 50;
        }
        return score;
    }

    /** The path of {@code file} relative to {@code root} with `/` separators, or null outside the project. */
    public static String relative(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) {
            return null;
        }
        return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
    }
}

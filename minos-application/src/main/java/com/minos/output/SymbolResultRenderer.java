package com.minos.output;

import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;
import com.minos.query.SymbolResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import static com.minos.output.json.DeterministicJson.object;
import static com.minos.output.json.DeterministicJson.quote;
import com.minos.output.json.DeterministicJson;

/**
 * Rend les résultats de symboles sous une forme déterministe et bornée.
 */
public final class SymbolResultRenderer {

    private SymbolResultRenderer() {
    }

    public static String render(List<SymbolResult> results, SymbolOutputFormat format) {
        List<SymbolResult> snapshot = List.copyOf(Objects.requireNonNull(results, "results"));
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case TEXT -> renderText(snapshot);
            case JSON -> renderJson(snapshot);
        };
    }

    private static String renderText(List<SymbolResult> results) {
        if (results.isEmpty()) {
            return "symbols: 0";
        }
        StringJoiner symbols = new StringJoiner("\n\n");
        results.stream().map(SymbolResultRenderer::renderTextSymbol).forEach(symbols::add);
        return "symbols: " + results.size() + "\n\n" + symbols;
    }

    private static String renderTextSymbol(SymbolResult result) {
        StringJoiner lines = new StringJoiner("\n");
        lines.add("symbol:");
        addTextString(lines, 2, "id", result.id());
        addTextString(lines, 2, "symbolKey", result.symbolKey());
        addTextValue(lines, 2, "identityQuality", result.identityQuality().name());
        addTextString(lines, 2, "projectId", result.projectId());
        addTextString(lines, 2, "moduleId", result.moduleId());
        addTextString(lines, 2, "fileId", result.fileId());
        addTextValue(lines, 2, "kind", result.kind().name());
        addTextString(lines, 2, "name", result.name());
        addTextString(lines, 2, "qualifiedName", result.qualifiedName());
        addTextString(lines, 2, "signature", result.signature());
        addTextString(lines, 2, "language", result.language());
        addTextLocation(lines, result.location());
        addTextValue(lines, 2, "resolutionStatus", result.resolutionStatus().name());
        addTextOrigin(lines, result.origin());
        addTextValue(lines, 2, "external", Boolean.toString(result.external()));
        addTextValue(lines, 2, "generated", Boolean.toString(result.generated()));
        return lines.toString();
    }

    private static void addTextLocation(StringJoiner lines, SymbolLocation location) {
        if (location == null) {
            addTextValue(lines, 2, "location", "null");
            return;
        }
        lines.add("  location:");
        addTextString(lines, 4, "fileId", location.fileId());
        addTextValue(lines, 4, "startLine", Integer.toString(location.startLine()));
        addTextValue(lines, 4, "startColumn", Integer.toString(location.startColumn()));
        addTextValue(lines, 4, "endLine", Integer.toString(location.endLine()));
        addTextValue(lines, 4, "endColumn", Integer.toString(location.endColumn()));
        addTextValue(lines, 4, "positionEncoding", location.positionEncoding().name());
    }

    private static void addTextOrigin(StringJoiner lines, Origin origin) {
        lines.add("  origin:");
        addTextString(lines, 4, "providerId", origin.providerId());
        addTextString(lines, 4, "providerType", origin.providerType());
        addTextString(lines, 4, "providerVersion", origin.providerVersion());
        addTextString(lines, 4, "indexRunId", origin.indexRunId());
        addTextValue(lines, 4, "sourceType", origin.sourceType().name());
    }

    private static void addTextString(StringJoiner lines, int indent, String name, String value) {
        addTextValue(lines, indent, name, value == null ? "null" : quote(value));
    }

    private static void addTextValue(StringJoiner lines, int indent, String name, String value) {
        lines.add(" ".repeat(indent) + name + ": " + value);
    }

    private static String renderJson(List<SymbolResult> results) {
        return DeterministicJson.render(object(
                "count", results.size(),
                "symbols", results.stream().map(SymbolResultRenderer::symbolMap).toList()));
    }

    private static Map<String, Object> symbolMap(SymbolResult result) {
        return object(
                "id", result.id(),
                "symbolKey", result.symbolKey(),
                "identityQuality", result.identityQuality().name(),
                "projectId", result.projectId(),
                "moduleId", result.moduleId(),
                "fileId", result.fileId(),
                "kind", result.kind().name(),
                "name", result.name(),
                "qualifiedName", result.qualifiedName(),
                "signature", result.signature(),
                "language", result.language(),
                "location", JsonShapes.location(result.location()),
                "resolutionStatus", result.resolutionStatus().name(),
                "origin", JsonShapes.origin(result.origin()),
                "external", result.external(),
                "generated", result.generated());
    }
}

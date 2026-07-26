package com.minos.output;

import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;
import com.minos.query.SymbolResult;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Rend les résultats de symboles sous une forme déterministe et bornée.
 */
public final class SymbolResultRenderer {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

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
        StringBuilder output = new StringBuilder();
        output.append("{\"count\":").append(results.size()).append(",\"symbols\":[");
        for (int index = 0; index < results.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            appendJsonSymbol(output, results.get(index));
        }
        return output.append("]}").toString();
    }

    private static void appendJsonSymbol(StringBuilder output, SymbolResult result) {
        output.append('{');
        appendJsonStringField(output, "id", result.id());
        appendJsonStringField(output, "symbolKey", result.symbolKey());
        appendJsonStringField(output, "identityQuality", result.identityQuality().name());
        appendJsonStringField(output, "projectId", result.projectId());
        appendJsonStringField(output, "moduleId", result.moduleId());
        appendJsonStringField(output, "fileId", result.fileId());
        appendJsonStringField(output, "kind", result.kind().name());
        appendJsonStringField(output, "name", result.name());
        appendJsonStringField(output, "qualifiedName", result.qualifiedName());
        appendJsonStringField(output, "signature", result.signature());
        appendJsonStringField(output, "language", result.language());
        appendJsonName(output, "location");
        appendJsonLocation(output, result.location());
        output.append(',');
        appendJsonStringField(output, "resolutionStatus", result.resolutionStatus().name());
        appendJsonName(output, "origin");
        appendJsonOrigin(output, result.origin());
        output.append(',');
        appendJsonBooleanField(output, "external", result.external());
        appendJsonBooleanField(output, "generated", result.generated());
        output.setLength(output.length() - 1);
        output.append('}');
    }

    private static void appendJsonLocation(StringBuilder output, SymbolLocation location) {
        if (location == null) {
            output.append("null");
            return;
        }
        output.append('{');
        appendJsonStringField(output, "fileId", location.fileId());
        appendJsonNumberField(output, "startLine", location.startLine());
        appendJsonNumberField(output, "startColumn", location.startColumn());
        appendJsonNumberField(output, "endLine", location.endLine());
        appendJsonNumberField(output, "endColumn", location.endColumn());
        appendJsonStringField(output, "positionEncoding", location.positionEncoding().name());
        output.setLength(output.length() - 1);
        output.append('}');
    }

    private static void appendJsonOrigin(StringBuilder output, Origin origin) {
        output.append('{');
        appendJsonStringField(output, "providerId", origin.providerId());
        appendJsonStringField(output, "providerType", origin.providerType());
        appendJsonStringField(output, "providerVersion", origin.providerVersion());
        appendJsonStringField(output, "indexRunId", origin.indexRunId());
        appendJsonStringField(output, "sourceType", origin.sourceType().name());
        output.setLength(output.length() - 1);
        output.append('}');
    }

    private static void appendJsonStringField(StringBuilder output, String name, String value) {
        appendJsonName(output, name);
        output.append(value == null ? "null" : quote(value)).append(',');
    }

    private static void appendJsonNumberField(StringBuilder output, String name, int value) {
        appendJsonName(output, name);
        output.append(value).append(',');
    }

    private static void appendJsonBooleanField(StringBuilder output, String name, boolean value) {
        appendJsonName(output, name);
        output.append(value).append(',');
    }

    private static void appendJsonName(StringBuilder output, String name) {
        output.append(quote(name)).append(':');
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isHighSurrogate(current)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        escaped.append(current).append(value.charAt(++index));
                    } else if (Character.isSurrogate(current)
                            || current < 0x20
                            || current == '\u2028'
                            || current == '\u2029') {
                        appendUnicodeEscape(escaped, current);
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void appendUnicodeEscape(StringBuilder output, char value) {
        output.append("\\u");
        output.append(HEX_DIGITS[value >>> 12 & 0xF]);
        output.append(HEX_DIGITS[value >>> 8 & 0xF]);
        output.append(HEX_DIGITS[value >>> 4 & 0xF]);
        output.append(HEX_DIGITS[value & 0xF]);
    }
}

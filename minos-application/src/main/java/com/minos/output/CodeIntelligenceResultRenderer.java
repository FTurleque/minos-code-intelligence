package com.minos.output;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;
import com.minos.query.RelationshipResult;
import com.minos.query.UsageResult;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Rendu déterministe TEXT/JSON des résultats d'occurrences et de relations M3.
 */
public final class CodeIntelligenceResultRenderer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CodeIntelligenceResultRenderer() {
    }

    public static String renderUsages(List<UsageResult> usages, SymbolOutputFormat format) {
        Objects.requireNonNull(usages, "usages");
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case TEXT -> renderUsageText(usages);
            case JSON -> renderUsageJson(usages);
        };
    }

    public static String renderRelationships(
            List<RelationshipResult> relationships,
            SymbolOutputFormat format
    ) {
        Objects.requireNonNull(relationships, "relationships");
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case TEXT -> renderRelationshipText(relationships);
            case JSON -> renderRelationshipJson(relationships);
        };
    }

    private static String renderUsageText(List<UsageResult> usages) {
        StringJoiner output = new StringJoiner("\n\n");
        usages.forEach(usage -> {
            StringJoiner lines = new StringJoiner("\n");
            lines.add("usage:");
            field(lines, 2, "id", quote(usage.id()));
            field(lines, 2, "projectId", quote(usage.projectId()));
            field(lines, 2, "symbolId", quote(usage.symbolId()));
            field(lines, 2, "roles", usage.roles().stream()
                    .sorted()
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]")));
            field(lines, 2, "resolutionStatus", usage.resolutionStatus().name());
            appendLocationText(lines, usage.location());
            appendOriginText(lines, usage.origin());
            output.add(lines.toString());
        });
        return usages.isEmpty() ? "usages: 0" : "usages: " + usages.size() + "\n\n" + output;
    }

    private static String renderRelationshipText(List<RelationshipResult> relationships) {
        StringJoiner output = new StringJoiner("\n\n");
        relationships.forEach(relationship -> {
            StringJoiner lines = new StringJoiner("\n");
            lines.add("relationship:");
            field(lines, 2, "id", quote(relationship.id()));
            field(lines, 2, "projectId", quote(relationship.projectId()));
            field(lines, 2, "source", entityText(relationship.source()));
            field(lines, 2, "target", relationship.target() == null
                    ? "null"
                    : entityText(relationship.target()));
            field(lines, 2, "unresolvedTarget", nullableText(relationship.unresolvedTarget()));
            field(lines, 2, "kind", relationship.kind().name());
            field(lines, 2, "resolutionStatus", relationship.resolutionStatus().name());
            field(lines, 2, "nature", relationship.nature().name());
            field(lines, 2, "confidence", relationship.confidence() == null
                    ? "null"
                    : Double.toString(relationship.confidence()));
            appendLocationText(lines, relationship.location());
            appendOriginText(lines, relationship.origin());
            field(lines, 2, "evidenceCount", Integer.toString(relationship.evidence().size()));
            for (Evidence evidence : relationship.evidence()) {
                field(lines, 4, "evidence", evidence.type().name()
                        + " (weight=" + evidence.weight() + "): "
                        + quote(evidence.description()));
            }
            output.add(lines.toString());
        });
        return relationships.isEmpty()
                ? "relationships: 0"
                : "relationships: " + relationships.size() + "\n\n" + output;
    }

    private static String renderUsageJson(List<UsageResult> usages) {
        StringBuilder output = new StringBuilder("{\"count\":")
                .append(usages.size())
                .append(",\"usages\":[");
        for (int index = 0; index < usages.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            UsageResult usage = usages.get(index);
            output.append('{');
            stringField(output, "id", usage.id());
            stringField(output, "projectId", usage.projectId());
            stringField(output, "symbolId", usage.symbolId());
            name(output, "location");
            appendLocationJson(output, usage.location());
            output.append(',');
            name(output, "roles");
            output.append('[');
            List<String> roles = usage.roles().stream().sorted().map(Enum::name).toList();
            for (int roleIndex = 0; roleIndex < roles.size(); roleIndex++) {
                if (roleIndex > 0) {
                    output.append(',');
                }
                output.append(quote(roles.get(roleIndex)));
            }
            output.append("],");
            stringField(output, "resolutionStatus", usage.resolutionStatus().name());
            name(output, "origin");
            appendOriginJson(output, usage.origin());
            output.append('}');
        }
        return output.append("]}").toString();
    }

    private static String renderRelationshipJson(List<RelationshipResult> relationships) {
        StringBuilder output = new StringBuilder("{\"count\":")
                .append(relationships.size())
                .append(",\"relationships\":[");
        for (int index = 0; index < relationships.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            RelationshipResult relationship = relationships.get(index);
            output.append('{');
            stringField(output, "id", relationship.id());
            stringField(output, "projectId", relationship.projectId());
            name(output, "source");
            appendEntityJson(output, relationship.source());
            output.append(',');
            name(output, "target");
            if (relationship.target() == null) {
                output.append("null");
            } else {
                appendEntityJson(output, relationship.target());
            }
            output.append(',');
            stringField(output, "unresolvedTarget", relationship.unresolvedTarget());
            stringField(output, "kind", relationship.kind().name());
            name(output, "location");
            appendLocationJson(output, relationship.location());
            output.append(',');
            stringField(output, "resolutionStatus", relationship.resolutionStatus().name());
            stringField(output, "nature", relationship.nature().name());
            name(output, "confidence");
            output.append(relationship.confidence() == null
                    ? "null"
                    : Double.toString(relationship.confidence())).append(',');
            name(output, "origin");
            appendOriginJson(output, relationship.origin());
            output.append(',');
            name(output, "evidence");
            appendEvidenceJson(output, relationship.evidence());
            output.append('}');
        }
        return output.append("]}").toString();
    }

    private static void appendEvidenceJson(StringBuilder output, List<Evidence> evidence) {
        output.append('[');
        for (int index = 0; index < evidence.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            Evidence item = evidence.get(index);
            output.append('{');
            stringField(output, "type", item.type().name());
            stringField(output, "description", item.description());
            name(output, "source");
            appendNullableEntityJson(output, item.source());
            output.append(',');
            name(output, "target");
            appendNullableEntityJson(output, item.target());
            output.append(',');
            name(output, "location");
            appendLocationJson(output, item.location());
            output.append(',');
            name(output, "weight");
            output.append(item.weight() == null ? "null" : Double.toString(item.weight()));
            output.append('}');
        }
        output.append(']');
    }

    private static void appendNullableEntityJson(StringBuilder output, CodeEntityRef reference) {
        if (reference == null) {
            output.append("null");
        } else {
            appendEntityJson(output, reference);
        }
    }

    private static void appendEntityJson(StringBuilder output, CodeEntityRef reference) {
        output.append('{');
        stringField(output, "type", reference.type().name());
        stringField(output, "id", reference.id());
        output.setLength(output.length() - 1);
        output.append('}');
    }

    private static void appendLocationJson(StringBuilder output, SymbolLocation location) {
        if (location == null) {
            output.append("null");
            return;
        }
        output.append('{');
        stringField(output, "fileId", location.fileId());
        numberField(output, "startLine", location.startLine());
        numberField(output, "startColumn", location.startColumn());
        numberField(output, "endLine", location.endLine());
        numberField(output, "endColumn", location.endColumn());
        stringField(output, "positionEncoding", location.positionEncoding().name());
        output.setLength(output.length() - 1);
        output.append('}');
    }

    private static void appendOriginJson(StringBuilder output, Origin origin) {
        output.append('{');
        stringField(output, "providerId", origin.providerId());
        stringField(output, "providerType", origin.providerType());
        stringField(output, "providerVersion", origin.providerVersion());
        stringField(output, "indexRunId", origin.indexRunId());
        stringField(output, "sourceType", origin.sourceType().name());
        output.setLength(output.length() - 1);
        output.append('}');
    }

    private static void appendLocationText(StringJoiner lines, SymbolLocation location) {
        if (location == null) {
            field(lines, 2, "location", "null");
            return;
        }
        field(lines, 2, "location", quote(location.fileId()) + ":"
                + location.startLine() + ":" + location.startColumn() + "-"
                + location.endLine() + ":" + location.endColumn());
    }

    private static void appendOriginText(StringJoiner lines, Origin origin) {
        field(lines, 2, "origin", quote(origin.providerId()) + "/" + origin.sourceType().name());
    }

    private static String entityText(CodeEntityRef reference) {
        return reference.type().name() + ":" + quote(reference.id());
    }

    private static String nullableText(String value) {
        return value == null ? "null" : quote(value);
    }

    private static void field(StringJoiner lines, int indent, String name, String value) {
        lines.add(" ".repeat(indent) + name + ": " + value);
    }

    private static void stringField(StringBuilder output, String name, String value) {
        name(output, name);
        output.append(value == null ? "null" : quote(value)).append(',');
    }

    private static void numberField(StringBuilder output, String name, int value) {
        name(output, name);
        output.append(value).append(',');
    }

    private static void name(StringBuilder output, String name) {
        output.append(quote(name)).append(':');
    }

    private static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (Character.isHighSurrogate(current)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        output.append(current).append(value.charAt(++index));
                    } else if (Character.isSurrogate(current) || current < 0x20
                            || current == '\u2028' || current == '\u2029') {
                        output.append("\\u")
                                .append(HEX[current >>> 12 & 0xF])
                                .append(HEX[current >>> 8 & 0xF])
                                .append(HEX[current >>> 4 & 0xF])
                                .append(HEX[current & 0xF]);
                    } else {
                        output.append(current);
                    }
                }
            }
        }
        return output.append('"').toString();
    }
}

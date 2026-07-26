package com.minos.output;

import com.minos.context.CodeContextResult;
import com.minos.context.CodeSearchResponse;
import com.minos.context.ContextRelationshipResult;
import com.minos.context.SourceExcerpt;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Rendu compact TEXT/JSON des recherches et sources M4.
 */
public final class CodeSearchRenderer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CodeSearchRenderer() {
    }

    public static String render(CodeSearchResponse response, SymbolOutputFormat format) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case TEXT -> renderText(response);
            case JSON -> renderJson(response);
        };
    }

    public static String renderSource(SourceExcerpt source, SymbolOutputFormat format) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        if (format == SymbolOutputFormat.JSON) {
            StringBuilder output = new StringBuilder("{\"source\":");
            appendSource(output, source);
            return output.append('}').toString();
        }
        StringJoiner lines = new StringJoiner("\n");
        lines.add("source:");
        field(lines, 2, "fileId", quote(source.fileId()));
        field(lines, 2, "startLine", source.startLine());
        field(lines, 2, "endLine", source.endLine());
        field(lines, 2, "fullFile", source.fullFile());
        field(lines, 2, "truncated", source.truncated());
        field(lines, 2, "estimatedTokens", source.estimatedTokens());
        field(lines, 2, "totalFileLines", source.totalFileLines());
        field(lines, 2, "totalFileTokens", source.totalFileTokens());
        field(lines, 2, "content", quote(source.content()));
        return lines.toString();
    }

    private static String renderText(CodeSearchResponse response) {
        StringJoiner output = new StringJoiner("\n");
        output.add("search:");
        field(output, 2, "projectId", quote(response.projectId()));
        field(output, 2, "query", nullable(response.query()));
        field(output, 2, "count", response.count());
        field(output, 2, "maxDepth", response.maxDepth());
        field(output, 2, "tokenBudget", response.tokenBudget());
        field(output, 2, "estimatedTokens", response.estimatedTokens());
        field(output, 2, "estimatedTokensAvoided", response.estimatedTokensAvoided());
        field(output, 2, "truncated", response.truncated());
        for (CodeContextResult context : response.contexts()) {
            SymbolResult symbol = context.symbol();
            output.add("");
            output.add("context:");
            field(output, 2, "symbolId", quote(symbol.id()));
            field(output, 2, "name", quote(symbol.name()));
            field(output, 2, "qualifiedName", nullable(symbol.qualifiedName()));
            field(output, 2, "signature", nullable(symbol.signature()));
            field(output, 2, "kind", symbol.kind().name());
            field(output, 2, "estimatedTokens", context.estimatedTokens());
            field(output, 2, "truncated", context.truncated());
            if (context.source() != null) {
                field(output, 2, "sourceRange",
                        quote(context.source().fileId() + ":"
                                + context.source().startLine() + "-"
                                + context.source().endLine()));
                field(output, 2, "source", quote(context.source().content()));
            }
            field(output, 2, "relationships", context.relationships().size());
            for (ContextRelationshipResult relationship : context.relationships()) {
                field(output, 4, "relationship",
                        relationship.depth() + ":" + relationship.direction().name()
                                + ":" + relationship.relationship().kind().name()
                                + ":" + relationship.relationship().id());
            }
            field(output, 2, "usages", context.usages().size());
            for (UsageResult usage : context.usages()) {
                field(output, 4, "usage",
                        quote(usage.location().fileId() + ":"
                                + usage.location().startLine() + ":"
                                + usage.location().startColumn()));
            }
        }
        return output.toString();
    }

    private static String renderJson(CodeSearchResponse response) {
        StringBuilder output = new StringBuilder("{");
        stringField(output, "projectId", response.projectId());
        stringField(output, "query", response.query());
        numberField(output, "count", response.count());
        numberField(output, "maxDepth", response.maxDepth());
        numberField(output, "tokenBudget", response.tokenBudget());
        numberField(output, "estimatedTokens", response.estimatedTokens());
        numberField(output, "estimatedTokensAvoided", response.estimatedTokensAvoided());
        booleanField(output, "truncated", response.truncated());
        name(output, "contexts");
        output.append('[');
        for (int index = 0; index < response.contexts().size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            appendContext(output, response.contexts().get(index));
        }
        return output.append("]}").toString();
    }

    private static void appendContext(StringBuilder output, CodeContextResult context) {
        output.append('{');
        name(output, "symbol");
        appendSymbol(output, context.symbol());
        output.append(',');
        name(output, "source");
        if (context.source() == null) {
            output.append("null");
        } else {
            appendSource(output, context.source());
        }
        output.append(',');
        name(output, "relationships");
        output.append('[');
        for (int index = 0; index < context.relationships().size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            appendRelationship(output, context.relationships().get(index));
        }
        output.append("],");
        name(output, "usages");
        output.append('[');
        for (int index = 0; index < context.usages().size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            appendUsage(output, context.usages().get(index));
        }
        output.append("],");
        numberField(output, "estimatedTokens", context.estimatedTokens());
        booleanField(output, "truncated", context.truncated());
        trimComma(output);
        output.append('}');
    }

    private static void appendSymbol(StringBuilder output, SymbolResult symbol) {
        output.append('{');
        stringField(output, "id", symbol.id());
        stringField(output, "name", symbol.name());
        stringField(output, "qualifiedName", symbol.qualifiedName());
        stringField(output, "signature", symbol.signature());
        stringField(output, "kind", symbol.kind().name());
        stringField(output, "language", symbol.language());
        stringField(output, "fileId", symbol.fileId());
        name(output, "location");
        appendLocation(output, symbol.location());
        output.append(',');
        stringField(output, "resolutionStatus", symbol.resolutionStatus().name());
        name(output, "origin");
        appendOrigin(output, symbol.origin());
        trimComma(output);
        output.append('}');
    }

    private static void appendSource(StringBuilder output, SourceExcerpt source) {
        output.append('{');
        stringField(output, "fileId", source.fileId());
        numberField(output, "startLine", source.startLine());
        numberField(output, "endLine", source.endLine());
        booleanField(output, "fullFile", source.fullFile());
        booleanField(output, "truncated", source.truncated());
        numberField(output, "estimatedTokens", source.estimatedTokens());
        numberField(output, "totalFileLines", source.totalFileLines());
        numberField(output, "totalFileTokens", source.totalFileTokens());
        stringField(output, "content", source.content());
        trimComma(output);
        output.append('}');
    }

    private static void appendRelationship(
            StringBuilder output,
            ContextRelationshipResult context
    ) {
        var relationship = context.relationship();
        output.append('{');
        numberField(output, "depth", context.depth());
        stringField(output, "direction", context.direction().name());
        stringField(output, "id", relationship.id());
        stringField(output, "kind", relationship.kind().name());
        name(output, "source");
        appendEntity(output, relationship.source());
        output.append(',');
        name(output, "target");
        if (relationship.target() == null) {
            output.append("null");
        } else {
            appendEntity(output, relationship.target());
        }
        output.append(',');
        stringField(output, "unresolvedTarget", relationship.unresolvedTarget());
        stringField(output, "resolutionStatus", relationship.resolutionStatus().name());
        stringField(output, "nature", relationship.nature().name());
        nullableNumberField(output, "confidence", relationship.confidence());
        name(output, "origin");
        appendOrigin(output, relationship.origin());
        output.append(',');
        name(output, "evidence");
        output.append('[');
        List<Evidence> evidence = relationship.evidence();
        for (int index = 0; index < evidence.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            Evidence item = evidence.get(index);
            output.append('{');
            stringField(output, "type", item.type().name());
            stringField(output, "description", item.description());
            nullableNumberField(output, "weight", item.weight());
            trimComma(output);
            output.append('}');
        }
        output.append(']');
        output.append('}');
    }

    private static void appendUsage(StringBuilder output, UsageResult usage) {
        output.append('{');
        stringField(output, "id", usage.id());
        stringField(output, "fileId", usage.location().fileId());
        numberField(output, "startLine", usage.location().startLine());
        numberField(output, "startColumn", usage.location().startColumn());
        name(output, "roles");
        output.append('[');
        List<String> roles = usage.roles().stream().sorted().map(Enum::name).toList();
        for (int index = 0; index < roles.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            output.append(quote(roles.get(index)));
        }
        output.append("],");
        stringField(output, "resolutionStatus", usage.resolutionStatus().name());
        trimComma(output);
        output.append('}');
    }

    private static void appendEntity(StringBuilder output, CodeEntityRef entity) {
        output.append('{');
        stringField(output, "type", entity.type().name());
        stringField(output, "id", entity.id());
        trimComma(output);
        output.append('}');
    }

    private static void appendLocation(StringBuilder output, SymbolLocation location) {
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
        trimComma(output);
        output.append('}');
    }

    private static void appendOrigin(StringBuilder output, Origin origin) {
        output.append('{');
        stringField(output, "providerId", origin.providerId());
        stringField(output, "providerType", origin.providerType());
        stringField(output, "providerVersion", origin.providerVersion());
        stringField(output, "indexRunId", origin.indexRunId());
        stringField(output, "sourceType", origin.sourceType().name());
        trimComma(output);
        output.append('}');
    }

    private static void field(StringJoiner output, int indent, String name, Object value) {
        output.add(" ".repeat(indent) + name + ": " + value);
    }

    private static String nullable(String value) {
        return value == null ? "null" : quote(value);
    }

    private static void stringField(StringBuilder output, String name, String value) {
        name(output, name);
        output.append(value == null ? "null" : quote(value)).append(',');
    }

    private static void numberField(StringBuilder output, String name, int value) {
        name(output, name);
        output.append(value).append(',');
    }

    private static void nullableNumberField(StringBuilder output, String name, Double value) {
        name(output, name);
        output.append(value == null ? "null" : Double.toString(value)).append(',');
    }

    private static void booleanField(StringBuilder output, String name, boolean value) {
        name(output, name);
        output.append(value).append(',');
    }

    private static void name(StringBuilder output, String value) {
        output.append(quote(value)).append(':');
    }

    private static void trimComma(StringBuilder output) {
        if (!output.isEmpty() && output.charAt(output.length() - 1) == ',') {
            output.setLength(output.length() - 1);
        }
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
                    if (Character.isHighSurrogate(current) && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        escaped.append(current).append(value.charAt(++index));
                    } else if (Character.isSurrogate(current) || current < 0x20
                            || current == '\u2028' || current == '\u2029') {
                        escaped.append("\\u")
                                .append(HEX[current >>> 12 & 0xF])
                                .append(HEX[current >>> 8 & 0xF])
                                .append(HEX[current >>> 4 & 0xF])
                                .append(HEX[current & 0xF]);
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}

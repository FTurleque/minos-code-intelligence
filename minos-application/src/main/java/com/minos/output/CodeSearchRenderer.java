package com.minos.output;

import com.minos.context.CodeContextResult;
import com.minos.context.CodeSearchResponse;
import com.minos.context.ContextRelationshipResult;
import com.minos.context.SourceExcerpt;
import com.minos.domain.Evidence;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import static com.minos.output.json.DeterministicJson.object;
import static com.minos.output.json.DeterministicJson.quote;
import com.minos.output.json.DeterministicJson;

/**
 * Rendu compact TEXT/JSON des recherches et sources M4.
 */
public final class CodeSearchRenderer {

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
            return DeterministicJson.render(object("source", sourceMap(source)));
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
        return DeterministicJson.render(object(
                "projectId", response.projectId(),
                "query", response.query(),
                "count", response.count(),
                "maxDepth", response.maxDepth(),
                "tokenBudget", response.tokenBudget(),
                "estimatedTokens", response.estimatedTokens(),
                "estimatedTokensAvoided", response.estimatedTokensAvoided(),
                "truncated", response.truncated(),
                "contexts", response.contexts().stream().map(CodeSearchRenderer::contextMap).toList()));
    }

    private static Map<String, Object> contextMap(CodeContextResult context) {
        return object(
                "symbol", symbolMap(context.symbol()),
                "source", context.source() == null ? null : sourceMap(context.source()),
                "relationships", context.relationships().stream().map(CodeSearchRenderer::relationshipMap).toList(),
                "usages", context.usages().stream().map(CodeSearchRenderer::usageMap).toList(),
                "estimatedTokens", context.estimatedTokens(),
                "truncated", context.truncated());
    }

    private static Map<String, Object> symbolMap(SymbolResult symbol) {
        return object(
                "id", symbol.id(),
                "name", symbol.name(),
                "qualifiedName", symbol.qualifiedName(),
                "signature", symbol.signature(),
                "kind", symbol.kind().name(),
                "language", symbol.language(),
                "fileId", symbol.fileId(),
                "location", JsonShapes.location(symbol.location()),
                "resolutionStatus", symbol.resolutionStatus().name(),
                "origin", JsonShapes.origin(symbol.origin()));
    }

    private static Map<String, Object> sourceMap(SourceExcerpt source) {
        return object(
                "fileId", source.fileId(),
                "startLine", source.startLine(),
                "endLine", source.endLine(),
                "fullFile", source.fullFile(),
                "truncated", source.truncated(),
                "estimatedTokens", source.estimatedTokens(),
                "totalFileLines", source.totalFileLines(),
                "totalFileTokens", source.totalFileTokens(),
                "content", source.content());
    }

    private static Map<String, Object> relationshipMap(ContextRelationshipResult context) {
        var relationship = context.relationship();
        List<Map<String, Object>> evidence = relationship.evidence().stream()
                .map(CodeSearchRenderer::evidenceMap)
                .toList();
        return object(
                "depth", context.depth(),
                "direction", context.direction().name(),
                "id", relationship.id(),
                "kind", relationship.kind().name(),
                "source", JsonShapes.entity(relationship.source()),
                "target", JsonShapes.entity(relationship.target()),
                "unresolvedTarget", relationship.unresolvedTarget(),
                "resolutionStatus", relationship.resolutionStatus().name(),
                "nature", relationship.nature().name(),
                "confidence", relationship.confidence(),
                "origin", JsonShapes.origin(relationship.origin()),
                "evidence", evidence);
    }

    private static Map<String, Object> evidenceMap(Evidence item) {
        return object(
                "type", item.type().name(),
                "description", item.description(),
                "weight", item.weight());
    }

    private static Map<String, Object> usageMap(UsageResult usage) {
        return object(
                "id", usage.id(),
                "fileId", usage.location().fileId(),
                "startLine", usage.location().startLine(),
                "startColumn", usage.location().startColumn(),
                "roles", JsonShapes.roles(usage.roles()),
                "resolutionStatus", usage.resolutionStatus().name());
    }

    private static void field(StringJoiner output, int indent, String name, Object value) {
        output.add(" ".repeat(indent) + name + ": " + value);
    }

    private static String nullable(String value) {
        return value == null ? "null" : quote(value);
    }
}

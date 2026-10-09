package com.minos.output;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.SymbolLocation;
import com.minos.query.RelationshipResult;
import com.minos.query.UsageResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import static com.minos.output.DeterministicJson.object;
import static com.minos.output.DeterministicJson.quote;

/**
 * Rendu déterministe TEXT/JSON des résultats d'occurrences et de relations M3.
 */
public final class CodeIntelligenceResultRenderer {

    private static final String FORMAT = "format";
    private static final String RELATIONSHIPS = "relationships";
    private static final String COUNT = "count";
    private static final String LOCATION = "location";
    private static final String ORIGIN = "origin";

    private CodeIntelligenceResultRenderer() {
    }

    public static String renderUsages(List<UsageResult> usages, SymbolOutputFormat format) {
        Objects.requireNonNull(usages, "usages");
        Objects.requireNonNull(format, FORMAT);
        return switch (format) {
            case TEXT -> renderUsageText(usages);
            case JSON -> renderUsageJson(usages);
        };
    }

    public static String renderRelationships(
            List<RelationshipResult> relationships,
            SymbolOutputFormat format
    ) {
        Objects.requireNonNull(relationships, RELATIONSHIPS);
        Objects.requireNonNull(format, FORMAT);
        return switch (format) {
            case TEXT -> renderRelationshipText(relationships);
            case JSON -> renderRelationshipJson(relationships);
        };
    }

    /**
     * {@link #renderRelationships(List, SymbolOutputFormat)} that also states what the answer cannot see (callers,
     * callees and dependencies on a snapshot whose references are occurrences). The limitations are added after the
     * existing keys and lines, and only when there is one: with none, the output is the historical one, byte for byte.
     */
    public static String renderRelationships(
            List<RelationshipResult> relationships,
            List<String> limitations,
            SymbolOutputFormat format
    ) {
        Objects.requireNonNull(relationships, RELATIONSHIPS);
        Objects.requireNonNull(limitations, "limitations");
        Objects.requireNonNull(format, FORMAT);
        if (limitations.isEmpty()) {
            return renderRelationships(relationships, format);
        }
        return switch (format) {
            case TEXT -> renderRelationshipText(relationships) + "\nlimitations: " + limitations;
            case JSON -> DeterministicJson.render(object(
                    COUNT, relationships.size(),
                    RELATIONSHIPS, relationships.stream()
                            .map(CodeIntelligenceResultRenderer::relationshipMap)
                            .toList(),
                    "limitations", List.copyOf(limitations)));
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
                    : DeterministicJson.number(relationship.confidence()));
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
        return DeterministicJson.render(object(
                COUNT, usages.size(),
                "usages", usages.stream().map(CodeIntelligenceResultRenderer::usageMap).toList()));
    }

    private static String renderRelationshipJson(List<RelationshipResult> relationships) {
        return DeterministicJson.render(object(
                COUNT, relationships.size(),
                RELATIONSHIPS, relationships.stream()
                        .map(CodeIntelligenceResultRenderer::relationshipMap)
                        .toList()));
    }

    private static Map<String, Object> usageMap(UsageResult usage) {
        return object(
                "id", usage.id(),
                "projectId", usage.projectId(),
                "symbolId", usage.symbolId(),
                LOCATION, JsonShapes.location(usage.location()),
                "roles", JsonShapes.roles(usage.roles()),
                "resolutionStatus", usage.resolutionStatus().name(),
                ORIGIN, JsonShapes.origin(usage.origin()));
    }

    private static Map<String, Object> relationshipMap(RelationshipResult relationship) {
        return object(
                "id", relationship.id(),
                "projectId", relationship.projectId(),
                "source", JsonShapes.entity(relationship.source()),
                "target", JsonShapes.entity(relationship.target()),
                "unresolvedTarget", relationship.unresolvedTarget(),
                "kind", relationship.kind().name(),
                LOCATION, JsonShapes.location(relationship.location()),
                "resolutionStatus", relationship.resolutionStatus().name(),
                "nature", relationship.nature().name(),
                "confidence", relationship.confidence(),
                ORIGIN, JsonShapes.origin(relationship.origin()),
                "evidence", relationship.evidence().stream()
                        .map(CodeIntelligenceResultRenderer::evidenceMap)
                        .toList());
    }

    private static Map<String, Object> evidenceMap(Evidence item) {
        return object(
                "type", item.type().name(),
                "description", item.description(),
                "source", JsonShapes.entity(item.source()),
                "target", JsonShapes.entity(item.target()),
                LOCATION, JsonShapes.location(item.location()),
                "weight", item.weight());
    }

    private static void appendLocationText(StringJoiner lines, SymbolLocation location) {
        if (location == null) {
            field(lines, 2, LOCATION, "null");
            return;
        }
        field(lines, 2, LOCATION, quote(location.fileId()) + ":"
                + location.startLine() + ":" + location.startColumn() + "-"
                + location.endLine() + ":" + location.endColumn());
    }

    private static void appendOriginText(StringJoiner lines, Origin origin) {
        field(lines, 2, ORIGIN, quote(origin.providerId()) + "/" + origin.sourceType().name());
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
}

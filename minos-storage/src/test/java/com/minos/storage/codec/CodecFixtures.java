package com.minos.storage.codec;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ProviderReference;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.UnresolvedSymbolReference;
import com.minos.store.CodeKnowledgeSnapshot;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A knowledge snapshot that reaches every branch of the binary codecs: optional fields present and absent. */
public final class CodecFixtures {

    private CodecFixtures() {
    }

    public static CodeKnowledgeSnapshot everyBranch(UUID projectId, String snapshotId) {
        String project = projectId.toString();
        CodeEntityRef caller = new CodeEntityRef(CodeEntityType.SYMBOL, "method-string");
        CodeEntityRef callee = new CodeEntityRef(CodeEntityType.SYMBOL, "method-int");
        CodeEntityRef file = new CodeEntityRef(CodeEntityType.SOURCE_FILE, "file-converter");
        List<SymbolOccurrence> occurrences = List.of(
                new SymbolOccurrence("occ-resolved", project, new ResolvedSymbolReference("method-int"),
                        location("file-caller", 20), Set.of(OccurrenceRole.REFERENCE, OccurrenceRole.CALL),
                        ResolutionStatus.RESOLVED, origin(),
                        Set.of(new ProviderReference("fixture-provider", "opaque-usage"),
                                new ProviderReference("other-provider", "opaque-usage-2"))),
                new SymbolOccurrence("occ-unresolved", project,
                        new UnresolvedSymbolReference("MissingClient", null, "java", "missing dependency",
                                Set.of(new ProviderReference("fixture-provider", "opaque-missing"))),
                        location("file-caller", 25), Set.of(), ResolutionStatus.UNRESOLVED,
                        new Origin("fixture-provider", null, null, null, OriginType.OTHER), Set.of()));
        List<Relationship> relationships = List.of(
                new Relationship("rel-calls", project, caller, callee, null, RelationshipKind.CALLS,
                        location("file-converter", 12), ResolutionStatus.RESOLVED, InformationNature.FACTUAL, null,
                        origin(), List.of(new Evidence(EvidenceType.DIRECT_CALL, "direct provider call", caller, callee,
                        location("file-converter", 12), 1.0))),
                new Relationship("rel-derived", project, file, callee, null, RelationshipKind.DEPENDS_ON, null,
                        ResolutionStatus.RESOLVED, InformationNature.DERIVED, 0.75, origin(),
                        List.of(new Evidence(EvidenceType.DERIVATION_PATH, "path without anchors", null, null, null, null))),
                new Relationship("rel-unresolved", project, caller, null, "com.minos.Missing", RelationshipKind.REFERENCES,
                        location("file-caller", 30), ResolutionStatus.UNRESOLVED, InformationNature.FACTUAL, null,
                        origin(), List.of()));
        return new CodeKnowledgeSnapshot(projectId, snapshotId, symbols(projectId),
                occurrences, relationships);
    }

    public static SymbolLocation location(String fileId, int line) {
        return new SymbolLocation(fileId, line, 1, line, 10, PositionEncoding.UTF16_CODE_UNITS);
    }

    public static Origin origin() {
        return new Origin("fixture-provider", "TEST", "1", "run-1", OriginType.OTHER);
    }

    public static List<Symbol> symbols(UUID projectId) {
        return List.of(
                symbol(
                        projectId,
                        "method-int",
                        SymbolKind.METHOD,
                        "convert",
                        "com.minos.Converter.convert",
                        "(int)",
                        8,
                        ResolutionStatus.RESOLVED,
                        false,
                        "opaque-method-int"
                ),
                symbol(
                        projectId,
                        "method-string",
                        SymbolKind.METHOD,
                        "convert",
                        "com.minos.Converter.convert",
                        "(java.lang.String)",
                        12,
                        ResolutionStatus.RESOLVED,
                        false,
                        "opaque-method-\uD800string"
                ),
                symbol(
                        projectId,
                        "external-clock",
                        SymbolKind.CLASS,
                        "Clock",
                        "java.time.Clock",
                        null,
                        0,
                        ResolutionStatus.RESOLVED,
                        true,
                        "opaque-external-clock"
                ),
                symbol(
                        projectId,
                        "unresolved-client",
                        SymbolKind.CLASS,
                        "MissingClient",
                        "com.minos.MissingClient",
                        null,
                        0,
                        ResolutionStatus.UNRESOLVED,
                        true,
                        "opaque-missing-client"
                )
        );
    }

    private static Symbol symbol(
            UUID projectId,
            String id,
            SymbolKind kind,
            String name,
            String qualifiedName,
            String signature,
            int line,
            ResolutionStatus resolutionStatus,
            boolean external,
            String externalId
    ) {
        SymbolLocation location = line == 0
                ? null
                : new SymbolLocation(
                        "file-converter",
                        line,
                        4,
                        line,
                        20,
                        PositionEncoding.UTF16_CODE_UNITS
                );
        return new Symbol(
                id,
                projectId + "|java|" + kind + "|" + qualifiedName + "|" + id,
                external
                        ? SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK
                        : SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                projectId.toString(),
                external ? null : "main",
                external ? null : "file-converter",
                null,
                kind,
                name,
                qualifiedName,
                signature,
                "java",
                location,
                resolutionStatus,
                new Origin(
                        "fixture-provider",
                        "TEST",
                        "1.0",
                        "run-1",
                        OriginType.OTHER
                ),
                external,
                false,
                Set.of(new ProviderReference("fixture-provider", externalId))
        );
    }
}

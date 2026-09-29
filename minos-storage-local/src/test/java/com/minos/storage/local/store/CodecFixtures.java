package com.minos.storage.local.store;

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
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.UnresolvedSymbolReference;
import com.minos.store.CodeKnowledgeSnapshot;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A knowledge snapshot that reaches every branch of the binary codecs: optional fields present and absent. */
final class CodecFixtures {

    private CodecFixtures() {
    }

    static CodeKnowledgeSnapshot everyBranch(UUID projectId, String snapshotId) {
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
        return new CodeKnowledgeSnapshot(projectId, snapshotId, FileSymbolSnapshotStoreTest.symbols(projectId),
                occurrences, relationships);
    }

    static SymbolLocation location(String fileId, int line) {
        return new SymbolLocation(fileId, line, 1, line, 10, PositionEncoding.UTF16_CODE_UNITS);
    }

    static Origin origin() {
        return new Origin("fixture-provider", "TEST", "1", "run-1", OriginType.OTHER);
    }
}

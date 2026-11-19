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
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolReference;
import com.minos.domain.UnresolvedSymbolReference;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Field layout of every entity of a snapshot body: symbol, occurrence, relationship and the values they carry
 * (symbol reference, evidence, entity reference, location, origin, provider references). The layout is the same in
 * every format; only the string encoding differs, and it is carried by the sink and the input.
 */
final class SnapshotEntityCodec {

    private static final int MAX_REFERENCES = 1_000_000;
    private static final int MAX_ROLES = 1_000;
    private static final int MAX_EVIDENCE = 1_000_000;

    private SnapshotEntityCodec() {
    }

    static void writeSymbol(SnapshotEncodingSink output, Symbol symbol) throws IOException {
        output.writeString(symbol.id());
        output.writeString(symbol.symbolKey());
        output.writeString(symbol.identityQuality().name());
        output.writeString(symbol.projectId());
        output.writeString(symbol.moduleId());
        output.writeString(symbol.fileId());
        output.writeString(symbol.parentSymbolId());
        output.writeString(symbol.kind().name());
        output.writeString(symbol.name());
        output.writeString(symbol.qualifiedName());
        output.writeString(symbol.signature());
        output.writeString(symbol.language());
        writeLocation(output, symbol.location());
        output.writeString(symbol.resolutionStatus().name());
        writeOrigin(output, symbol.origin());
        output.writeBoolean(symbol.external());
        output.writeBoolean(symbol.generated());
        List<ProviderReference> references = symbol.providerReferences().stream()
                .sorted(Comparator.comparing(ProviderReference::providerId)
                        .thenComparing(ProviderReference::externalId))
                .toList();
        output.writeInt(references.size());
        for (ProviderReference reference : references) {
            output.writeString(reference.providerId());
            output.writeString(reference.externalId());
        }
    }

    static Symbol readSymbol(SnapshotInput input) throws IOException {
        try {
            String id = input.readRequiredString("symbol.id");
            String symbolKey = input.readRequiredString("symbol.symbolKey");
            SymbolIdentityQuality identityQuality = input.readEnum(
                    SymbolIdentityQuality.class,
                    "symbol.identityQuality"
            );
            String projectId = input.readRequiredString("symbol.projectId");
            String moduleId = input.readString();
            String fileId = input.readString();
            String parentSymbolId = input.readString();
            SymbolKind kind = input.readEnum(SymbolKind.class, "symbol.kind");
            String name = input.readRequiredString("symbol.name");
            String qualifiedName = input.readString();
            String signature = input.readString();
            String language = input.readRequiredString("symbol.language");
            SymbolLocation location = readLocation(input);
            ResolutionStatus resolutionStatus = input.readEnum(
                    ResolutionStatus.class,
                    "symbol.resolutionStatus"
            );
            Origin origin = readOrigin(input);
            boolean external = input.readBoolean();
            boolean generated = input.readBoolean();
            int referenceCount = input.readCount(MAX_REFERENCES, "provider reference count");
            Set<ProviderReference> references = new HashSet<>();
            for (int index = 0; index < referenceCount; index++) {
                references.add(new ProviderReference(
                        input.readRequiredString("providerReference.providerId"),
                        input.readRequiredString("providerReference.externalId")
                ));
            }
            if (references.size() != referenceCount) {
                throw new IOException("duplicate provider reference in symbol snapshot");
            }
            return new Symbol(
                    id,
                    symbolKey,
                    identityQuality,
                    projectId,
                    moduleId,
                    fileId,
                    parentSymbolId,
                    kind,
                    name,
                    qualifiedName,
                    signature,
                    language,
                    location,
                    resolutionStatus,
                    origin,
                    external,
                    generated,
                    references
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid symbol in snapshot: " + exception.getMessage(), exception);
        }
    }

    static void writeOccurrence(SnapshotEncodingSink output, SymbolOccurrence occurrence) throws IOException {
        output.writeString(occurrence.id());
        output.writeString(occurrence.projectId());
        writeSymbolReference(output, occurrence.symbolRef());
        writeLocation(output, occurrence.location());
        List<OccurrenceRole> roles = occurrence.roles().stream().sorted().toList();
        output.writeInt(roles.size());
        for (OccurrenceRole role : roles) {
            output.writeString(role.name());
        }
        output.writeString(occurrence.resolutionStatus().name());
        writeOrigin(output, occurrence.origin());
        writeProviderReferences(output, occurrence.providerReferences());
    }

    static SymbolOccurrence readOccurrence(SnapshotInput input) throws IOException {
        try {
            String id = input.readRequiredString("occurrence.id");
            String projectId = input.readRequiredString("occurrence.projectId");
            SymbolReference symbolReference = readSymbolReference(input);
            SymbolLocation location = readLocation(input);
            if (location == null) {
                throw new IOException("occurrence.location is required");
            }
            int roleCount = input.readCount(MAX_ROLES, "occurrence role count");
            Set<OccurrenceRole> roles = new HashSet<>();
            for (int index = 0; index < roleCount; index++) {
                roles.add(input.readEnum(OccurrenceRole.class, "occurrence.role"));
            }
            if (roles.size() != roleCount) {
                throw new IOException("duplicate role in occurrence");
            }
            ResolutionStatus status = input.readEnum(
                    ResolutionStatus.class,
                    "occurrence.resolutionStatus"
            );
            Origin origin = readOrigin(input);
            Set<ProviderReference> references = readProviderReferences(input);
            return new SymbolOccurrence(
                    id,
                    projectId,
                    symbolReference,
                    location,
                    roles,
                    status,
                    origin,
                    references
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid occurrence in snapshot: " + exception.getMessage(), exception);
        }
    }

    static void writeRelationship(SnapshotEncodingSink output, Relationship relationship) throws IOException {
        output.writeString(relationship.id());
        output.writeString(relationship.projectId());
        writeCodeEntityReference(output, relationship.source());
        writeOptionalCodeEntityReference(output, relationship.target());
        output.writeString(relationship.unresolvedTarget());
        output.writeString(relationship.kind().name());
        writeLocation(output, relationship.location());
        output.writeString(relationship.resolutionStatus().name());
        output.writeString(relationship.nature().name());
        writeOptionalDouble(output, relationship.confidence());
        writeOrigin(output, relationship.origin());
        List<Evidence> orderedEvidence = relationship.evidence().stream()
                .sorted(evidenceComparator())
                .toList();
        output.writeInt(orderedEvidence.size());
        for (Evidence evidence : orderedEvidence) {
            writeEvidence(output, evidence);
        }
    }

    static Relationship readRelationship(SnapshotInput input) throws IOException {
        try {
            String id = input.readRequiredString("relationship.id");
            String projectId = input.readRequiredString("relationship.projectId");
            CodeEntityRef source = readCodeEntityReference(input);
            CodeEntityRef target = readOptionalCodeEntityReference(input);
            String unresolvedTarget = input.readString();
            RelationshipKind kind = input.readEnum(RelationshipKind.class, "relationship.kind");
            SymbolLocation location = readLocation(input);
            ResolutionStatus status = input.readEnum(
                    ResolutionStatus.class,
                    "relationship.resolutionStatus"
            );
            InformationNature nature = input.readEnum(
                    InformationNature.class,
                    "relationship.nature"
            );
            Double confidence = readOptionalDouble(input);
            Origin origin = readOrigin(input);
            int evidenceCount = input.readCount(MAX_EVIDENCE, "relationship evidence count");
            List<Evidence> evidence = new ArrayList<>(evidenceCount);
            for (int index = 0; index < evidenceCount; index++) {
                evidence.add(readEvidence(input));
            }
            return new Relationship(
                    id,
                    projectId,
                    source,
                    target,
                    unresolvedTarget,
                    kind,
                    location,
                    status,
                    nature,
                    confidence,
                    origin,
                    evidence
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid relationship in snapshot: " + exception.getMessage(), exception);
        }
    }

    private static void writeSymbolReference(SnapshotEncodingSink output, SymbolReference reference)
            throws IOException {
        if (reference instanceof ResolvedSymbolReference(String symbolId)) {
            output.writeByte(1);
            output.writeString(symbolId);
            return;
        }
        if (reference instanceof UnresolvedSymbolReference(String displayName, String qualifiedNameCandidate,
                                                           String language, String reason,
                                                           Set<ProviderReference> providerReferences)) {
            output.writeByte(2);
            output.writeString(displayName);
            output.writeString(qualifiedNameCandidate);
            output.writeString(language);
            output.writeString(reason);
            writeProviderReferences(output, providerReferences);
            return;
        }
        throw new IOException("unsupported symbol reference type: " + reference.getClass().getName());
    }

    private static SymbolReference readSymbolReference(SnapshotInput input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> new ResolvedSymbolReference(
                    input.readRequiredString("resolvedSymbolReference.symbolId")
            );
            case 2 -> new UnresolvedSymbolReference(
                    input.readString(),
                    input.readString(),
                    input.readString(),
                    input.readString(),
                    readProviderReferences(input)
            );
            default -> throw new IOException("unsupported symbol reference discriminator");
        };
    }

    private static void writeEvidence(SnapshotEncodingSink output, Evidence evidence) throws IOException {
        output.writeString(evidence.type().name());
        output.writeString(evidence.description());
        writeOptionalCodeEntityReference(output, evidence.source());
        writeOptionalCodeEntityReference(output, evidence.target());
        writeLocation(output, evidence.location());
        writeOptionalDouble(output, evidence.weight());
    }

    private static Evidence readEvidence(SnapshotInput input) throws IOException {
        return new Evidence(
                input.readEnum(EvidenceType.class, "evidence.type"),
                input.readRequiredString("evidence.description"),
                readOptionalCodeEntityReference(input),
                readOptionalCodeEntityReference(input),
                readLocation(input),
                readOptionalDouble(input)
        );
    }

    private static Comparator<Evidence> evidenceComparator() {
        Comparator<CodeEntityRef> entityComparator = Comparator
                .comparing(CodeEntityRef::type)
                .thenComparing(CodeEntityRef::id);
        return Comparator.comparing(Evidence::type)
                .thenComparing(Evidence::description)
                .thenComparing(Evidence::source, Comparator.nullsLast(entityComparator))
                .thenComparing(Evidence::target, Comparator.nullsLast(entityComparator))
                .thenComparing(
                        evidence -> evidence.location() == null
                                ? null
                                : evidence.location().fileId(),
                        Comparator.nullsLast(String::compareTo)
                )
                .thenComparing(
                        evidence -> evidence.location() == null
                                ? Integer.MAX_VALUE
                                : evidence.location().startLine()
                )
                .thenComparing(Evidence::weight, Comparator.nullsLast(Double::compareTo));
    }

    private static void writeOptionalCodeEntityReference(SnapshotEncodingSink output, CodeEntityRef reference)
            throws IOException {
        output.writeBoolean(reference != null);
        if (reference != null) {
            writeCodeEntityReference(output, reference);
        }
    }

    private static CodeEntityRef readOptionalCodeEntityReference(SnapshotInput input) throws IOException {
        return input.readBoolean() ? readCodeEntityReference(input) : null;
    }

    private static void writeCodeEntityReference(SnapshotEncodingSink output, CodeEntityRef reference)
            throws IOException {
        output.writeString(reference.type().name());
        output.writeString(reference.id());
    }

    private static CodeEntityRef readCodeEntityReference(SnapshotInput input) throws IOException {
        return new CodeEntityRef(
                input.readEnum(CodeEntityType.class, "codeEntityRef.type"),
                input.readRequiredString("codeEntityRef.id")
        );
    }

    private static void writeOptionalDouble(SnapshotEncodingSink output, Double value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeDouble(value);
        }
    }

    private static Double readOptionalDouble(SnapshotInput input) throws IOException {
        return input.readBoolean() ? input.readDouble() : null;
    }

    private static void writeProviderReferences(SnapshotEncodingSink output, Set<ProviderReference> providerReferences)
            throws IOException {
        List<ProviderReference> ordered = providerReferences.stream()
                .sorted(Comparator.comparing(ProviderReference::providerId)
                        .thenComparing(ProviderReference::externalId))
                .toList();
        output.writeInt(ordered.size());
        for (ProviderReference reference : ordered) {
            output.writeString(reference.providerId());
            output.writeString(reference.externalId());
        }
    }

    private static Set<ProviderReference> readProviderReferences(SnapshotInput input) throws IOException {
        int referenceCount = input.readCount(MAX_REFERENCES, "provider reference count");
        Set<ProviderReference> references = new HashSet<>();
        for (int index = 0; index < referenceCount; index++) {
            references.add(new ProviderReference(
                    input.readRequiredString("providerReference.providerId"),
                    input.readRequiredString("providerReference.externalId")
            ));
        }
        if (references.size() != referenceCount) {
            throw new IOException("duplicate provider reference in snapshot");
        }
        return references;
    }

    private static void writeLocation(SnapshotEncodingSink output, SymbolLocation location) throws IOException {
        output.writeBoolean(location != null);
        if (location == null) {
            return;
        }
        output.writeString(location.fileId());
        output.writeInt(location.startLine());
        output.writeInt(location.startColumn());
        output.writeInt(location.endLine());
        output.writeInt(location.endColumn());
        output.writeString(location.positionEncoding().name());
    }

    private static SymbolLocation readLocation(SnapshotInput input) throws IOException {
        if (!input.readBoolean()) {
            return null;
        }
        return new SymbolLocation(
                input.readRequiredString("location.fileId"),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readEnum(PositionEncoding.class, "location.positionEncoding")
        );
    }

    private static void writeOrigin(SnapshotEncodingSink output, Origin origin) throws IOException {
        output.writeString(origin.providerId());
        output.writeString(origin.providerType());
        output.writeString(origin.providerVersion());
        output.writeString(origin.indexRunId());
        output.writeString(origin.sourceType().name());
    }

    private static Origin readOrigin(SnapshotInput input) throws IOException {
        return new Origin(
                input.readRequiredString("origin.providerId"),
                input.readString(),
                input.readString(),
                input.readString(),
                input.readEnum(OriginType.class, "origin.sourceType")
        );
    }
}

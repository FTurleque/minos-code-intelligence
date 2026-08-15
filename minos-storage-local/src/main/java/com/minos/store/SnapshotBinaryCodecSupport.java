package com.minos.store;

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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Shared binary primitives used by the explicit v1/v2 snapshot codecs.
 *
 * <p>The framing helpers ({@code readHeaderVersion}, {@code writeString}, {@code readString},
 * {@code readRequiredString}, {@code readCount}) are package-private rather than private because
 * {@link ActiveSnapshotRepository} writes the pointer file with the same framing. They must stay a
 * single implementation: a divergence between the two would silently produce pointer files this
 * package can no longer read back.</p>
 */
final class SnapshotBinaryCodecSupport {

    static final int FORMAT_VERSION_V1 = 1;
    static final int FORMAT_VERSION_V2 = 2;

    private static final int SNAPSHOT_MAGIC = 0x4D4E5359;
    private static final int MAX_SYMBOLS = 10_000_000;
    private static final int MAX_OCCURRENCES = 100_000_000;
    private static final int MAX_RELATIONSHIPS = 100_000_000;
    private static final int MAX_REFERENCES = 1_000_000;
    private static final int MAX_ROLES = 1_000;
    private static final int MAX_EVIDENCE = 1_000_000;
    private static final int MAX_STRING_CHARS = 8 * 1024 * 1024;
    static final long MAX_PERSISTED_SNAPSHOT_BYTES = 256L * 1024L * 1024L;
    private static final HexFormat HEX = HexFormat.of();

    private SnapshotBinaryCodecSupport() {
    }

    private static int initialCapacity(int declaredCount) {
        // Counts describe protocol limits, not a trusted heap-allocation request. Grow incrementally.
        return Math.min(Math.max(0, declaredCount), 16_384);
    }


    static String writeSymbolSnapshotV1(Path file, SymbolSnapshot snapshot) throws IOException {
        MessageDigest digest = SnapshotIntegrityService.sha256Digest();
        try (OutputStream fileOutput = Files.newOutputStream(file);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(digestOutput))) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(FORMAT_VERSION_V1);
            output.writeLong(snapshot.projectId().getMostSignificantBits());
            output.writeLong(snapshot.projectId().getLeastSignificantBits());
            writeString(output, snapshot.snapshotId());
            output.writeInt(snapshot.symbols().size());
            for (Symbol symbol : snapshot.symbols()) {
                writeSymbol(output, symbol);
            }
        }
        requireSnapshotFileSize(file);
        return HEX.formatHex(digest.digest());
    }

    static SymbolSnapshot readSymbolSnapshotV1(Path file) throws IOException {
        requireSnapshotFileSize(file);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file)
        ))) {
            requireHeader(input, SNAPSHOT_MAGIC, FORMAT_VERSION_V1, "symbol snapshot");
            UUID projectId = new UUID(input.readLong(), input.readLong());
            String snapshotId = readRequiredString(input, "snapshotId");
            int symbolCount = readCount(input, MAX_SYMBOLS, "symbol count");
            List<Symbol> symbols = new ArrayList<>(initialCapacity(symbolCount));
            for (int index = 0; index < symbolCount; index++) {
                symbols.add(readSymbol(input));
            }
            if (input.read() != -1) {
                throw new IOException("unexpected trailing data in symbol snapshot");
            }
            try {
                return new SymbolSnapshot(projectId, snapshotId, symbols);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid symbol snapshot: " + exception.getMessage(), exception);
            }
        } catch (EOFException exception) {
            throw new IOException("truncated symbol snapshot", exception);
        }
    }

    static CodeKnowledgeSnapshot fromLegacy(SymbolSnapshot snapshot) {
        return new CodeKnowledgeSnapshot(
                snapshot.projectId(),
                snapshot.snapshotId(),
                snapshot.symbols(),
                List.of(),
                List.of()
        );
    }

    static String writeKnowledgeSnapshotV2(Path file, CodeKnowledgeSnapshot snapshot) throws IOException {
        MessageDigest digest = SnapshotIntegrityService.sha256Digest();
        try (OutputStream fileOutput = Files.newOutputStream(file);
             DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(digestOutput))) {
            writeKnowledgeSnapshotV2Body(output, snapshot);
        }
        requireSnapshotFileSize(file);
        return HEX.formatHex(digest.digest());
    }

    static byte[] writeKnowledgeSnapshotV2ToBytes(CodeKnowledgeSnapshot snapshot) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(baos))) {
            writeKnowledgeSnapshotV2Body(output, snapshot);
        }
        byte[] payload = baos.toByteArray();
        if (payload.length > MAX_PERSISTED_SNAPSHOT_BYTES) {
            throw new IOException("knowledge snapshot payload exceeds persisted byte limit: " + payload.length);
        }
        return payload;
    }

    private static void writeKnowledgeSnapshotV2Body(DataOutputStream output, CodeKnowledgeSnapshot snapshot) throws IOException {
        output.writeInt(SNAPSHOT_MAGIC);
        output.writeInt(FORMAT_VERSION_V2);
        output.writeLong(snapshot.projectId().getMostSignificantBits());
        output.writeLong(snapshot.projectId().getLeastSignificantBits());
        writeString(output, snapshot.snapshotId());
        output.writeInt(snapshot.symbols().size());
        for (Symbol symbol : snapshot.symbols()) {
            writeSymbol(output, symbol);
        }
        output.writeInt(snapshot.occurrences().size());
        for (SymbolOccurrence occurrence : snapshot.occurrences()) {
            writeOccurrence(output, occurrence);
        }
        output.writeInt(snapshot.relationships().size());
        for (Relationship relationship : snapshot.relationships()) {
            writeRelationship(output, relationship);
        }
    }

    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2(Path file) throws IOException {
        requireSnapshotFileSize(file);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file)
        ))) {
            return readKnowledgeSnapshotV2Body(input);
        } catch (EOFException exception) {
            throw new IOException("truncated knowledge snapshot", exception);
        }
    }

    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2FromBytes(byte[] payload) throws IOException {
        if (payload.length > MAX_PERSISTED_SNAPSHOT_BYTES) {
            throw new IOException("knowledge snapshot payload exceeds persisted byte limit: " + payload.length);
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new ByteArrayInputStream(payload)
        ))) {
            return readKnowledgeSnapshotV2Body(input);
        } catch (EOFException exception) {
            throw new IOException("truncated knowledge snapshot", exception);
        }
    }

    private static CodeKnowledgeSnapshot readKnowledgeSnapshotV2Body(DataInputStream input) throws IOException {
        requireHeader(input, SNAPSHOT_MAGIC, FORMAT_VERSION_V2, "knowledge snapshot");
        UUID projectId = new UUID(input.readLong(), input.readLong());
        String snapshotId = readRequiredString(input, "snapshotId");
        int symbolCount = readCount(input, MAX_SYMBOLS, "symbol count");
        List<Symbol> symbols = new ArrayList<>(initialCapacity(symbolCount));
        for (int index = 0; index < symbolCount; index++) {
            symbols.add(readSymbol(input));
        }
        int occurrenceCount = readCount(input, MAX_OCCURRENCES, "occurrence count");
        List<SymbolOccurrence> occurrences = new ArrayList<>(initialCapacity(occurrenceCount));
        for (int index = 0; index < occurrenceCount; index++) {
            occurrences.add(readOccurrence(input));
        }
        int relationshipCount = readCount(input, MAX_RELATIONSHIPS, "relationship count");
        List<Relationship> relationships = new ArrayList<>(initialCapacity(relationshipCount));
        for (int index = 0; index < relationshipCount; index++) {
            relationships.add(readRelationship(input));
        }
        if (input.read() != -1) {
            throw new IOException("unexpected trailing data in knowledge snapshot");
        }
        try {
            return new CodeKnowledgeSnapshot(
                    projectId,
                    snapshotId,
                    symbols,
                    occurrences,
                    relationships
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid knowledge snapshot: " + exception.getMessage(), exception);
        }
    }

    private static void requireSnapshotFileSize(Path file) throws IOException {
        long size = Files.size(file);
        if (size < 1L || size > MAX_PERSISTED_SNAPSHOT_BYTES) {
            throw new IOException("snapshot payload exceeds persisted byte limit: " + size
                    + "/" + MAX_PERSISTED_SNAPSHOT_BYTES);
        }
    }

    private static void writeSymbol(DataOutputStream output, Symbol symbol) throws IOException {
        writeString(output, symbol.id());
        writeString(output, symbol.symbolKey());
        writeString(output, symbol.identityQuality().name());
        writeString(output, symbol.projectId());
        writeString(output, symbol.moduleId());
        writeString(output, symbol.fileId());
        writeString(output, symbol.parentSymbolId());
        writeString(output, symbol.kind().name());
        writeString(output, symbol.name());
        writeString(output, symbol.qualifiedName());
        writeString(output, symbol.signature());
        writeString(output, symbol.language());
        writeLocation(output, symbol.location());
        writeString(output, symbol.resolutionStatus().name());
        writeOrigin(output, symbol.origin());
        output.writeBoolean(symbol.external());
        output.writeBoolean(symbol.generated());
        List<ProviderReference> references = symbol.providerReferences().stream()
                .sorted(Comparator.comparing(ProviderReference::providerId)
                        .thenComparing(ProviderReference::externalId))
                .toList();
        output.writeInt(references.size());
        for (ProviderReference reference : references) {
            writeString(output, reference.providerId());
            writeString(output, reference.externalId());
        }
    }

    private static Symbol readSymbol(DataInputStream input) throws IOException {
        try {
            String id = readRequiredString(input, "symbol.id");
            String symbolKey = readRequiredString(input, "symbol.symbolKey");
            SymbolIdentityQuality identityQuality = readEnum(
                    input,
                    SymbolIdentityQuality.class,
                    "symbol.identityQuality"
            );
            String projectId = readRequiredString(input, "symbol.projectId");
            String moduleId = readString(input);
            String fileId = readString(input);
            String parentSymbolId = readString(input);
            SymbolKind kind = readEnum(input, SymbolKind.class, "symbol.kind");
            String name = readRequiredString(input, "symbol.name");
            String qualifiedName = readString(input);
            String signature = readString(input);
            String language = readRequiredString(input, "symbol.language");
            SymbolLocation location = readLocation(input);
            ResolutionStatus resolutionStatus = readEnum(
                    input,
                    ResolutionStatus.class,
                    "symbol.resolutionStatus"
            );
            Origin origin = readOrigin(input);
            boolean external = input.readBoolean();
            boolean generated = input.readBoolean();
            int referenceCount = readCount(input, MAX_REFERENCES, "provider reference count");
            Set<ProviderReference> references = new HashSet<>();
            for (int index = 0; index < referenceCount; index++) {
                references.add(new ProviderReference(
                        readRequiredString(input, "providerReference.providerId"),
                        readRequiredString(input, "providerReference.externalId")
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

    private static void writeOccurrence(
            DataOutputStream output,
            SymbolOccurrence occurrence
    ) throws IOException {
        writeString(output, occurrence.id());
        writeString(output, occurrence.projectId());
        writeSymbolReference(output, occurrence.symbolRef());
        writeLocation(output, occurrence.location());
        List<OccurrenceRole> roles = occurrence.roles().stream().sorted().toList();
        output.writeInt(roles.size());
        for (OccurrenceRole role : roles) {
            writeString(output, role.name());
        }
        writeString(output, occurrence.resolutionStatus().name());
        writeOrigin(output, occurrence.origin());
        writeProviderReferences(output, occurrence.providerReferences());
    }

    private static SymbolOccurrence readOccurrence(DataInputStream input) throws IOException {
        try {
            String id = readRequiredString(input, "occurrence.id");
            String projectId = readRequiredString(input, "occurrence.projectId");
            SymbolReference symbolReference = readSymbolReference(input);
            SymbolLocation location = readLocation(input);
            if (location == null) {
                throw new IOException("occurrence.location is required");
            }
            int roleCount = readCount(input, MAX_ROLES, "occurrence role count");
            Set<OccurrenceRole> roles = new HashSet<>();
            for (int index = 0; index < roleCount; index++) {
                roles.add(readEnum(input, OccurrenceRole.class, "occurrence.role"));
            }
            if (roles.size() != roleCount) {
                throw new IOException("duplicate role in occurrence");
            }
            ResolutionStatus status = readEnum(
                    input,
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

    private static void writeSymbolReference(
            DataOutputStream output,
            SymbolReference reference
    ) throws IOException {
        if (reference instanceof ResolvedSymbolReference resolved) {
            output.writeByte(1);
            writeString(output, resolved.symbolId());
            return;
        }
        if (reference instanceof UnresolvedSymbolReference unresolved) {
            output.writeByte(2);
            writeString(output, unresolved.displayName());
            writeString(output, unresolved.qualifiedNameCandidate());
            writeString(output, unresolved.language());
            writeString(output, unresolved.reason());
            writeProviderReferences(output, unresolved.providerReferences());
            return;
        }
        throw new IOException("unsupported symbol reference type: " + reference.getClass().getName());
    }

    private static SymbolReference readSymbolReference(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 1 -> new ResolvedSymbolReference(
                    readRequiredString(input, "resolvedSymbolReference.symbolId")
            );
            case 2 -> new UnresolvedSymbolReference(
                    readString(input),
                    readString(input),
                    readString(input),
                    readString(input),
                    readProviderReferences(input)
            );
            default -> throw new IOException("unsupported symbol reference discriminator");
        };
    }

    private static void writeRelationship(
            DataOutputStream output,
            Relationship relationship
    ) throws IOException {
        writeString(output, relationship.id());
        writeString(output, relationship.projectId());
        writeCodeEntityReference(output, relationship.source());
        writeOptionalCodeEntityReference(output, relationship.target());
        writeString(output, relationship.unresolvedTarget());
        writeString(output, relationship.kind().name());
        writeLocation(output, relationship.location());
        writeString(output, relationship.resolutionStatus().name());
        writeString(output, relationship.nature().name());
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

    private static Relationship readRelationship(DataInputStream input) throws IOException {
        try {
            String id = readRequiredString(input, "relationship.id");
            String projectId = readRequiredString(input, "relationship.projectId");
            CodeEntityRef source = readCodeEntityReference(input);
            CodeEntityRef target = readOptionalCodeEntityReference(input);
            String unresolvedTarget = readString(input);
            RelationshipKind kind = readEnum(input, RelationshipKind.class, "relationship.kind");
            SymbolLocation location = readLocation(input);
            ResolutionStatus status = readEnum(
                    input,
                    ResolutionStatus.class,
                    "relationship.resolutionStatus"
            );
            InformationNature nature = readEnum(
                    input,
                    InformationNature.class,
                    "relationship.nature"
            );
            Double confidence = readOptionalDouble(input);
            Origin origin = readOrigin(input);
            int evidenceCount = readCount(input, MAX_EVIDENCE, "relationship evidence count");
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

    private static void writeEvidence(DataOutputStream output, Evidence evidence)
            throws IOException {
        writeString(output, evidence.type().name());
        writeString(output, evidence.description());
        writeOptionalCodeEntityReference(output, evidence.source());
        writeOptionalCodeEntityReference(output, evidence.target());
        writeLocation(output, evidence.location());
        writeOptionalDouble(output, evidence.weight());
    }

    private static Evidence readEvidence(DataInputStream input) throws IOException {
        return new Evidence(
                readEnum(input, EvidenceType.class, "evidence.type"),
                readRequiredString(input, "evidence.description"),
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

    private static void writeOptionalCodeEntityReference(
            DataOutputStream output,
            CodeEntityRef reference
    ) throws IOException {
        output.writeBoolean(reference != null);
        if (reference != null) {
            writeCodeEntityReference(output, reference);
        }
    }

    private static CodeEntityRef readOptionalCodeEntityReference(DataInputStream input)
            throws IOException {
        return input.readBoolean() ? readCodeEntityReference(input) : null;
    }

    private static void writeCodeEntityReference(
            DataOutputStream output,
            CodeEntityRef reference
    ) throws IOException {
        writeString(output, reference.type().name());
        writeString(output, reference.id());
    }

    private static CodeEntityRef readCodeEntityReference(DataInputStream input) throws IOException {
        return new CodeEntityRef(
                readEnum(input, CodeEntityType.class, "codeEntityRef.type"),
                readRequiredString(input, "codeEntityRef.id")
        );
    }

    private static void writeOptionalDouble(DataOutputStream output, Double value)
            throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeDouble(value);
        }
    }

    private static Double readOptionalDouble(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readDouble() : null;
    }

    private static void writeProviderReferences(
            DataOutputStream output,
            Set<ProviderReference> providerReferences
    ) throws IOException {
        List<ProviderReference> ordered = providerReferences.stream()
                .sorted(Comparator.comparing(ProviderReference::providerId)
                        .thenComparing(ProviderReference::externalId))
                .toList();
        output.writeInt(ordered.size());
        for (ProviderReference reference : ordered) {
            writeString(output, reference.providerId());
            writeString(output, reference.externalId());
        }
    }

    private static Set<ProviderReference> readProviderReferences(DataInputStream input)
            throws IOException {
        int referenceCount = readCount(input, MAX_REFERENCES, "provider reference count");
        Set<ProviderReference> references = new HashSet<>();
        for (int index = 0; index < referenceCount; index++) {
            references.add(new ProviderReference(
                    readRequiredString(input, "providerReference.providerId"),
                    readRequiredString(input, "providerReference.externalId")
            ));
        }
        if (references.size() != referenceCount) {
            throw new IOException("duplicate provider reference in snapshot");
        }
        return references;
    }

    private static void writeLocation(DataOutputStream output, SymbolLocation location) throws IOException {
        output.writeBoolean(location != null);
        if (location == null) {
            return;
        }
        writeString(output, location.fileId());
        output.writeInt(location.startLine());
        output.writeInt(location.startColumn());
        output.writeInt(location.endLine());
        output.writeInt(location.endColumn());
        writeString(output, location.positionEncoding().name());
    }

    private static SymbolLocation readLocation(DataInputStream input) throws IOException {
        if (!input.readBoolean()) {
            return null;
        }
        return new SymbolLocation(
                readRequiredString(input, "location.fileId"),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                readEnum(input, PositionEncoding.class, "location.positionEncoding")
        );
    }

    private static void writeOrigin(DataOutputStream output, Origin origin) throws IOException {
        writeString(output, origin.providerId());
        writeString(output, origin.providerType());
        writeString(output, origin.providerVersion());
        writeString(output, origin.indexRunId());
        writeString(output, origin.sourceType().name());
    }

    private static Origin readOrigin(DataInputStream input) throws IOException {
        return new Origin(
                readRequiredString(input, "origin.providerId"),
                readString(input),
                readString(input),
                readString(input),
                readEnum(input, OriginType.class, "origin.sourceType")
        );
    }

    private static void requireHeader(
            DataInputStream input,
            int expectedMagic,
            int expectedVersion,
            String name
    ) throws IOException {
        int version = readHeaderVersion(input, expectedMagic, name);
        if (version != expectedVersion) {
            throw new IOException("unsupported " + name + " version: " + version);
        }
    }

    static int readHeaderVersion(
            DataInputStream input,
            int expectedMagic,
            String name
    ) throws IOException {
        if (input.readInt() != expectedMagic) {
            throw new IOException("invalid " + name + " magic");
        }
        return input.readInt();
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        if (value.length() > MAX_STRING_CHARS) {
            throw new IOException("string exceeds snapshot limit");
        }
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            output.writeChar(value.charAt(index));
        }
    }

    static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > MAX_STRING_CHARS) {
            throw new IOException("invalid string length in symbol snapshot: " + length);
        }
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(input.readChar());
        }
        return value.toString();
    }

    static String readRequiredString(DataInputStream input, String fieldName) throws IOException {
        String value = readString(input);
        if (value == null || value.isBlank()) {
            throw new IOException(fieldName + " must not be blank");
        }
        return value;
    }

    static int readCount(DataInputStream input, int maximum, String name) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new IOException("invalid " + name + ": " + count);
        }
        return count;
    }

    private static <E extends Enum<E>> E readEnum(
            DataInputStream input,
            Class<E> enumType,
            String fieldName
    ) throws IOException {
        String value = readRequiredString(input, fieldName);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("unsupported " + fieldName + ": " + value, exception);
        }
    }
}

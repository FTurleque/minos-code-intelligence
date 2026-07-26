package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Compatibility facade for local versioned snapshot persistence.
 *
 * <p>M15-S6 keeps the historical public API and on-disk formats while delegating
 * binary encoding, file publication, active-pointer state, integrity checks and
 * retention to dedicated components.</p>
 */
public final class FileSymbolSnapshotStore {

    private final Path storageRoot;
    private final SnapshotRepository snapshotRepository;
    private final ActiveSnapshotRepository activeSnapshotRepository;
    private final SnapshotIntegrityService integrityService;
    private final SnapshotRetentionService retentionService;
    private final SnapshotCodec codecV1;
    private final SnapshotCodec codecV2;

    public FileSymbolSnapshotStore(Path storageRoot) throws IOException {
        this.snapshotRepository = new SnapshotRepository(storageRoot);
        this.storageRoot = snapshotRepository.storageRoot();
        this.activeSnapshotRepository = new ActiveSnapshotRepository(snapshotRepository);
        this.integrityService = new SnapshotIntegrityService();
        this.retentionService = new SnapshotRetentionService(snapshotRepository);
        this.codecV1 = new SnapshotCodecV1();
        this.codecV2 = new SnapshotCodecV2();
    }

    public SymbolSnapshot publish(
            UUID projectId,
            String snapshotId,
            Collection<Symbol> symbols
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        Objects.requireNonNull(symbols, "symbols");

        List<Symbol> orderedSymbols = orderedById(symbols, Symbol::id, "symbols");
        SymbolSnapshot legacy = new SymbolSnapshot(projectId, snapshotId, orderedSymbols);
        rejectDuplicateIds(orderedSymbols, Symbol::id, "symbol");
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                snapshotId,
                orderedSymbols,
                List.of(),
                List.of()
        );
        publishSnapshot(snapshot, codecV1);
        return legacy;
    }

    /** Publishes atomically a complete M3 snapshot using the historical v2 format. */
    public CodeKnowledgeSnapshot publish(
            UUID projectId,
            String snapshotId,
            Collection<Symbol> symbols,
            Collection<SymbolOccurrence> occurrences,
            Collection<Relationship> relationships
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(occurrences, "occurrences");
        Objects.requireNonNull(relationships, "relationships");

        List<Symbol> orderedSymbols = orderedById(symbols, Symbol::id, "symbols");
        List<SymbolOccurrence> orderedOccurrences = orderedById(
                occurrences,
                SymbolOccurrence::id,
                "occurrences"
        );
        List<Relationship> orderedRelationships = orderedById(
                relationships,
                Relationship::id,
                "relationships"
        );
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                snapshotId,
                orderedSymbols,
                orderedOccurrences,
                orderedRelationships
        );
        rejectDuplicateIds(orderedSymbols, Symbol::id, "symbol");
        rejectDuplicateIds(orderedOccurrences, SymbolOccurrence::id, "occurrence");
        rejectDuplicateIds(orderedRelationships, Relationship::id, "relationship");
        publishSnapshot(snapshot, codecV2);
        return snapshot;
    }

    public Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException {
        return loadActiveKnowledge(projectId).map(snapshot -> new SymbolSnapshot(
                snapshot.projectId(),
                snapshot.snapshotId(),
                snapshot.symbols()
        ));
    }

    /** Loads a complete v2 snapshot or adapts a historical v1 snapshot with empty M3 collections. */
    public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Optional<SnapshotDescriptor> active = activeSnapshotRepository.read(projectId);
        if (active.isEmpty()) {
            return Optional.empty();
        }

        SnapshotDescriptor descriptor = active.orElseThrow();
        Path snapshotFile = snapshotRepository.resolveSnapshotFile(projectId, descriptor.fileName());
        if (!Files.isRegularFile(snapshotFile)) {
            throw new IOException("active symbol snapshot file is missing: " + snapshotFile);
        }
        integrityService.verifyChecksum(snapshotFile, descriptor.sha256());

        CodeKnowledgeSnapshot snapshot = codecFor(descriptor.formatVersion()).read(snapshotFile);
        integrityService.verifyMetadata(snapshot, projectId, descriptor);
        return Optional.of(snapshot);
    }

    public Path storageRoot() {
        return storageRoot;
    }

    /** Explicit retention mechanism; automatic retention policy remains deferred to M16 measurements. */
    public SnapshotRetentionService retentionService() {
        return retentionService;
    }

    private void publishSnapshot(CodeKnowledgeSnapshot snapshot, SnapshotCodec codec) throws IOException {
        Path temporarySnapshot = snapshotRepository.createTemporarySnapshot(snapshot.projectId());
        try {
            SnapshotCodec.SnapshotEncoding encoding = codec.write(temporarySnapshot, snapshot);
            String fileName = "snapshot-"
                    + integrityService.logicalIdHash(snapshot.snapshotId())
                    + "-"
                    + encoding.sha256()
                    + codec.fileExtension();
            snapshotRepository.publishSnapshot(snapshot.projectId(), fileName, temporarySnapshot);
            activeSnapshotRepository.promote(
                    snapshot.projectId(),
                    new SnapshotDescriptor(
                            codec.formatVersion(),
                            snapshot.snapshotId(),
                            fileName,
                            encoding.sha256(),
                            encoding.symbolCount(),
                            encoding.occurrenceCount(),
                            encoding.relationshipCount()
                    )
            );
        } finally {
            Files.deleteIfExists(temporarySnapshot);
        }
    }

    private SnapshotCodec codecFor(int version) throws IOException {
        return switch (version) {
            case ActiveSnapshotRepository.FORMAT_VERSION_V1 -> codecV1;
            case ActiveSnapshotRepository.FORMAT_VERSION_V2 -> codecV2;
            default -> throw new IOException("unsupported active snapshot pointer version: " + version);
        };
    }

    private static <T> List<T> orderedById(
            Collection<T> values,
            Function<T, String> id,
            String name
    ) {
        return values.stream()
                .map(value -> Objects.requireNonNull(value, name + " must not contain null"))
                .sorted(Comparator.comparing(id))
                .toList();
    }

    private static <T> void rejectDuplicateIds(
            List<T> values,
            Function<T, String> id,
            String name
    ) {
        Set<String> ids = new HashSet<>();
        for (T value : values) {
            String currentId = id.apply(value);
            if (!ids.add(currentId)) {
                throw new IllegalArgumentException("duplicate " + name + " id in snapshot: " + currentId);
            }
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}

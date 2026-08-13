package com.minos.adapter.scip;

import com.minos.domain.Relationship;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.io.BoundedInputStream;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.CodeKnowledgeStore;
import org.scip_code.scip.Index;

import java.io.DigestInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Pont explicite entre un artefact SCIP et le snapshot persistant MINOS. */
public final class ScipSymbolSnapshotImporter {

    private static final Pattern HASH_DERIVED_SNAPSHOT_ID = Pattern.compile("scip-[0-9a-f]{24}");
    private final ScipIngestionLimits limits;

    public ScipSymbolSnapshotImporter() {
        this(ScipIngestionLimits.DEFAULT);
    }

    ScipSymbolSnapshotImporter(ScipIngestionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public ScipSymbolSnapshotReport importSnapshot(
            Path indexFile,
            ScipSymbolSnapshotRequest request,
            CodeKnowledgeSnapshotStore snapshotStore
    ) throws IOException {
        Objects.requireNonNull(indexFile, "indexFile");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(snapshotStore, "snapshotStore");

        try (FrozenArtifact frozen = freeze(indexFile)) {
            verifyHashDerivedSnapshotId(request.snapshotId(), frozen.sha256());
            Index index = new ScipIndexReader(limits).read(frozen.path());
            limits.validate(index);
            Map<String, String> fileIds = defaultFileIds(
                    index, request.fileIdsByRelativePath(), request.projectRelativeRoot());
            CapturingStore capture = new CapturingStore();
            ScipIngestionReport ingestion = new ScipIngestionAdapter().ingest(
                    index,
                    new ScipIngestionRequest(request.projectId().toString(), request.moduleId(), request.providerId(),
                            request.providerVersion(), request.indexRunId(), fileIds, request.projectRelativeRoot()),
                    capture);
            snapshotStore.publish(request.projectId(), request.snapshotId(),
                    capture.symbols(), capture.occurrences(), capture.relationships());

            return new ScipSymbolSnapshotReport(request.snapshotId(), ingestion.catalogSymbolCount(),
                    ingestion.normalizedSymbolCount(), ingestion.skippedSymbolCount(), ingestion.occurrenceCount(),
                    ingestion.resolvedOccurrenceCount(), ingestion.unresolvedOccurrenceCount(),
                    ingestion.skippedOccurrenceCount(), ingestion.providerRelationshipCount(),
                    ingestion.providerRelationshipFactCount(), ingestion.relationshipCount(),
                    ingestion.derivedRelationshipCount(), ingestion.relatedTestRelationshipCount(),
                    ingestion.resolvedRelationshipCount(), ingestion.unresolvedRelationshipCount(),
                    ingestion.skippedRelationshipFactCount(), ingestion.duplicateRelationshipCount());
        }
    }

    private FrozenArtifact freeze(Path indexFile) throws IOException {
        Path source = indexFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("SCIP index does not exist or is not a regular file: " + source);
        }
        Path frozen = Files.createTempFile("minos-scip-import-", ".scip");
        boolean success = false;
        try {
            long expectedBytes;
            String sha256;
            try (SeekableByteChannel sourceChannel = Files.newByteChannel(
                         source, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 OutputStream output = Files.newOutputStream(
                         frozen, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                expectedBytes = sourceChannel.size();
                if (expectedBytes < 1L || expectedBytes > limits.maxArtifactBytes()) {
                    throw new IOException("SCIP artifact size is outside configured byte limit: "
                            + expectedBytes + "/" + limits.maxArtifactBytes());
                }
                MessageDigest digest = sha256Digest();
                BoundedInputStream bounded = new BoundedInputStream(
                        Channels.newInputStream(sourceChannel), expectedBytes, "SCIP artifact snapshot");
                try (DigestInputStream input = new DigestInputStream(bounded, digest)) {
                    input.transferTo(output);
                }
                if (bounded.consumedBytes() != expectedBytes || sourceChannel.size() != expectedBytes) {
                    throw new IOException("SCIP artifact changed while being captured");
                }
                sha256 = HexFormat.of().formatHex(digest.digest());
            }
            if (Files.isSymbolicLink(frozen)
                    || !Files.isRegularFile(frozen, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(frozen) != expectedBytes) {
                throw new IOException("frozen SCIP artifact length mismatch");
            }
            success = true;
            return new FrozenArtifact(frozen, sha256);
        } finally {
            if (!success) Files.deleteIfExists(frozen);
        }
    }

    private static void verifyHashDerivedSnapshotId(String snapshotId, String sha256) throws IOException {
        if (!HASH_DERIVED_SNAPSHOT_ID.matcher(snapshotId).matches()) return;
        String expected = "scip-" + sha256.substring(0, 24);
        if (!expected.equals(snapshotId)) {
            throw new IOException("SCIP artifact changed after its hash-derived snapshot id was computed");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, String> defaultFileIds(
            Index index, Map<String, String> explicitFileIds, String projectRelativeRoot) {
        Map<String, String> fileIds = new LinkedHashMap<>(explicitFileIds);
        index.getDocumentsList().forEach(document -> {
            String relativePath = document.getRelativePath();
            String normalized = safeRelativePath(relativePath);
            if (normalized != null) {
                String projectRelative = projectRelativeRoot == null || projectRelativeRoot.isBlank()
                        ? normalized : projectRelativeRoot + "/" + normalized;
                fileIds.putIfAbsent(relativePath, projectRelative);
            }
        });
        return Map.copyOf(fileIds);
    }

    private static String safeRelativePath(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Path path = Path.of(value.replace('\\', '/')).normalize();
            if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")) return null;
            return path.toString().replace('\\', '/');
        } catch (RuntimeException exception) { return null; }
    }

    private static final class FrozenArtifact implements AutoCloseable {
        private final Path path;
        private final String sha256;
        private FrozenArtifact(Path path, String sha256) {
            this.path = path;
            this.sha256 = sha256;
        }
        private Path path() { return path; }
        private String sha256() { return sha256; }
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }

    /** Write-only capture used only to atomically publish one normalized snapshot. */
    private static final class CapturingStore implements CodeKnowledgeStore {
        private final Map<String, Symbol> symbolsById = new LinkedHashMap<>();
        private final Map<String, SymbolOccurrence> occurrencesById = new LinkedHashMap<>();
        private final Map<String, Relationship> relationshipsById = new LinkedHashMap<>();

        @Override public void putSymbols(Collection<Symbol> symbols) {
            if (symbols != null) symbols.forEach(symbol -> symbolsById.put(symbol.id(), symbol));
        }
        @Override public void putOccurrences(Collection<SymbolOccurrence> occurrences) {
            if (occurrences != null) occurrences.forEach(occurrence -> occurrencesById.put(occurrence.id(), occurrence));
        }
        @Override public void putRelationships(Collection<Relationship> relationships) {
            if (relationships != null) relationships.forEach(relationship -> relationshipsById.put(relationship.id(), relationship));
        }
        @Override public Optional<Symbol> findSymbolById(String projectId, String symbolId) {
            return Optional.ofNullable(symbolsById.get(symbolId));
        }
        @Override public List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria) { throw unsupported(); }
        @Override public List<Symbol> findFileSymbols(String projectId, String fileId, int limit) { throw unsupported(); }
        @Override public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) { throw unsupported(); }
        @Override public List<Relationship> findRelationships(String projectId, RelationshipSearchCriteria criteria) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("SCIP snapshot capture store is write-only");
        }
        private List<Symbol> symbols() { return List.copyOf(symbolsById.values()); }
        private List<SymbolOccurrence> occurrences() { return List.copyOf(occurrencesById.values()); }
        private List<Relationship> relationships() { return List.copyOf(relationshipsById.values()); }
    }
}

package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Staging et promotion projet pour les artefacts SCIP exécutés par M14.
 *
 * <p>Chaque provider est normalisé dans un store temporaire distinct. Les faits
 * sont ensuite assemblés dans un snapshot projet de staging. Le store actif
 * n'est touché qu'au moment de {@link #promote(UUID, UUID, String)}.</p>
 */
public final class ScipProjectSnapshotLifecycle implements SnapshotStager, SnapshotPromoter {

    private final Path stagingRoot;
    private final FileSymbolSnapshotStore activeStore;
    private final Map<String, IndexerDescriptor> descriptors;

    public ScipProjectSnapshotLifecycle(Path minosHome) throws IOException {
        this(
                minosHome,
                new FileSymbolSnapshotStore(normalizedHome(minosHome).resolve("symbol-snapshots")),
                ScipIndexerCatalog.qualifiedM1Descriptors()
        );
    }

    /**
     * Composition constructor used by {@code MinosApplication} so staging/promotion
     * share the same active store and qualified provider catalogue as other surfaces.
     */
    public ScipProjectSnapshotLifecycle(
            Path minosHome,
            FileSymbolSnapshotStore activeStore,
            List<IndexerDescriptor> descriptors
    ) throws IOException {
        Path home = normalizedHome(minosHome);
        this.stagingRoot = home.resolve("staged-snapshots");
        this.activeStore = Objects.requireNonNull(activeStore, "activeStore");
        Objects.requireNonNull(descriptors, "descriptors");
        Map<String, IndexerDescriptor> values = new LinkedHashMap<>();
        for (IndexerDescriptor descriptor : descriptors) {
            IndexerDescriptor value = Objects.requireNonNull(descriptor, "descriptor");
            if (values.putIfAbsent(value.id(), value) != null) {
                throw new IllegalArgumentException("duplicate SCIP descriptor: " + value.id());
            }
        }
        this.descriptors = Map.copyOf(values);
        Files.createDirectories(stagingRoot);
    }

    @Override
    public String stage(IndexSnapshotStageRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        Path runRoot = runRoot(request.runId());
        deleteRecursively(runRoot);
        Files.createDirectories(runRoot);

        Map<String, Symbol> symbols = new LinkedHashMap<>();
        Map<String, SymbolOccurrence> occurrences = new LinkedHashMap<>();
        Map<String, Relationship> relationships = new LinkedHashMap<>();

        for (IndexingArtifact artifact : request.artifacts()) {
            IndexerDescriptor descriptor = descriptor(artifact.indexerId());
            String scope = scopeKey(artifact.projectRelativeRoot());
            String portableRoot = portable(artifact.projectRelativeRoot());
            Path providerRoot = runRoot.resolve("providers").resolve(artifact.indexerId()).resolve(scope);
            FileSymbolSnapshotStore providerStore = new FileSymbolSnapshotStore(providerRoot);
            String providerSnapshotId = "provider-" + request.runId() + "-" + artifact.indexerId() + "-" + scope;
            new ScipSymbolSnapshotImporter().importSnapshot(
                    artifact.finalArtifact(),
                    new ScipSymbolSnapshotRequest(
                            request.projectId(),
                            providerSnapshotId,
                            null,
                            descriptor.id(),
                            descriptor.version(),
                            request.runId() + ":" + descriptor.id(),
                            Map.of(),
                            portableRoot
                    ),
                    providerStore
            );
            CodeKnowledgeSnapshot normalized = providerStore.loadActiveKnowledge(request.projectId())
                    .orElseThrow(() -> new IllegalStateException(
                            "provider normalization did not publish its temporary snapshot: "
                                    + descriptor.id() + " scope=" + portableRoot));
            normalized.symbols().forEach(symbol -> putUnique(symbols, symbol.id(), symbol, "symbol"));
            normalized.occurrences().forEach(occurrence ->
                    putUnique(occurrences, occurrence.id(), occurrence, "occurrence"));
            normalized.relationships().forEach(relationship ->
                    putUnique(relationships, relationship.id(), relationship, "relationship"));
        }

        String stagedSnapshotId = "run-" + request.runId();
        FileSymbolSnapshotStore stagedStore = new FileSymbolSnapshotStore(runRoot.resolve("project"));
        stagedStore.publish(
                request.projectId(),
                stagedSnapshotId,
                symbols.values(),
                occurrences.values(),
                relationships.values()
        );
        return stagedSnapshotId;
    }

    @Override
    public void promote(UUID projectId, UUID runId, String stagedSnapshotId) throws Exception {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(runId, "runId");
        if (stagedSnapshotId == null || stagedSnapshotId.isBlank()) {
            throw new IllegalArgumentException("stagedSnapshotId must not be blank");
        }
        FileSymbolSnapshotStore stagedStore = new FileSymbolSnapshotStore(runRoot(runId).resolve("project"));
        CodeKnowledgeSnapshot staged = stagedStore.loadActiveKnowledge(projectId)
                .orElseThrow(() -> new IllegalStateException("staged project snapshot is missing for run " + runId));
        if (!stagedSnapshotId.equals(staged.snapshotId())) {
            throw new IllegalStateException("staged snapshot id mismatch: expected " + stagedSnapshotId
                    + ", found " + staged.snapshotId());
        }
        activeStore.publish(
                projectId,
                staged.snapshotId(),
                staged.symbols(),
                staged.occurrences(),
                staged.relationships()
        );
    }

    private Path runRoot(UUID runId) {
        return stagingRoot.resolve(runId.toString());
    }

    private IndexerDescriptor descriptor(String providerId) {
        IndexerDescriptor descriptor = descriptors.get(providerId);
        if (descriptor == null) {
            throw new IllegalArgumentException("no SCIP descriptor is registered for provider: " + providerId);
        }
        return descriptor;
    }

    private static String scopeKey(Path relativeRoot) {
        String portable = portable(relativeRoot);
        if (portable.isBlank()) {
            return "root";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(portable.getBytes(StandardCharsets.UTF_8)));
            return "module-" + hash.substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String portable(Path path) {
        return path == null ? "" : path.normalize().toString().replace('\\', '/');
    }

    private static Path normalizedHome(Path minosHome) {
        return Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
    }

    private static <T> void putUnique(Map<String, T> values, String id, T value, String type) {
        T existing = values.putIfAbsent(id, value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalStateException("provider snapshot collision on " + type + " id: " + id);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path current : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }
}

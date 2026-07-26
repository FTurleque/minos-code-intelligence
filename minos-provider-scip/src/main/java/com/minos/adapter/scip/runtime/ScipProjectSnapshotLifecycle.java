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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.stagingRoot = home.resolve("staged-snapshots");
        this.activeStore = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        Map<String, IndexerDescriptor> values = new LinkedHashMap<>();
        for (IndexerDescriptor descriptor : ScipIndexerCatalog.qualifiedM1Descriptors()) {
            values.put(descriptor.id(), descriptor);
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
            Path providerRoot = runRoot.resolve("providers").resolve(artifact.indexerId());
            FileSymbolSnapshotStore providerStore = new FileSymbolSnapshotStore(providerRoot);
            String providerSnapshotId = "provider-" + request.runId() + "-" + artifact.indexerId();
            new ScipSymbolSnapshotImporter().importSnapshot(
                    artifact.finalArtifact(),
                    new ScipSymbolSnapshotRequest(
                            request.projectId(),
                            providerSnapshotId,
                            null,
                            descriptor.id(),
                            descriptor.version(),
                            request.runId() + ":" + descriptor.id(),
                            Map.of()
                    ),
                    providerStore
            );
            CodeKnowledgeSnapshot normalized = providerStore.loadActiveKnowledge(request.projectId())
                    .orElseThrow(() -> new IllegalStateException(
                            "provider normalization did not publish its temporary snapshot: " + descriptor.id()));
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

    private static <T> void putUnique(Map<String, T> values, String id, T value, String type) {
        if (values.putIfAbsent(id, value) != null) {
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

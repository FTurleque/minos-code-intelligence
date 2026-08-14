package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.io.FileTreeOperations;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
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
import java.util.Optional;
import java.util.UUID;

/** Staging et promotion projet pour les artefacts SCIP exécutés par M14. */
public final class ScipProjectSnapshotLifecycle implements SnapshotStager, SnapshotPromoter {

    private final Path stagingRoot;
    private final Path runsRoot;
    private final CodeKnowledgeSnapshotStore activeStore;
    private final Map<String, IndexerDescriptor> descriptors;

    public ScipProjectSnapshotLifecycle(Path minosHome) throws IOException {
        this(minosHome, new FileSymbolSnapshotStore(normalizedHome(minosHome).resolve("symbol-snapshots")),
                ScipIndexerCatalog.qualifiedM1Descriptors());
    }

    public ScipProjectSnapshotLifecycle(
            Path minosHome,
            CodeKnowledgeSnapshotStore activeStore,
            List<IndexerDescriptor> descriptors
    ) throws IOException {
        Path home = normalizedHome(minosHome);
        this.stagingRoot = home.resolve("staged-snapshots");
        this.runsRoot = home.resolve("runs");
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
        Files.createDirectories(runsRoot);
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
            normalized.occurrences().forEach(occurrence -> putUnique(occurrences, occurrence.id(), occurrence, "occurrence"));
            normalized.relationships().forEach(relationship ->
                    putUnique(relationships, relationship.id(), relationship, "relationship"));
        }

        cleanupProviderWorkspaces(request.runId(), request.artifacts());

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
        Path stagedRunRoot = runRoot(runId);
        FileSymbolSnapshotStore stagedStore = new FileSymbolSnapshotStore(stagedRunRoot.resolve("project"));
        CodeKnowledgeSnapshot staged = stagedStore.loadActiveKnowledge(projectId)
                .orElseThrow(() -> new IllegalStateException("staged project snapshot is missing for run " + runId));
        if (!stagedSnapshotId.equals(staged.snapshotId())) {
            throw new IllegalStateException("staged snapshot id mismatch: expected "
                    + stagedSnapshotId + ", found " + staged.snapshotId());
        }

        // publish() is the promotion commit point. Once it returns, the active store is authoritative.
        // Cleanup is intentionally non-fatal after that point: reporting promotion failure after a
        // committed publication would make callers persist metadata for the previous snapshot.
        activeStore.publish(
                projectId,
                staged.snapshotId(),
                staged.symbols(),
                staged.occurrences(),
                staged.relationships()
        );
        try {
            deleteRecursively(stagedRunRoot);
        } catch (IOException ignored) {
            // Retention/recovery may reclaim this disposable staging tree later. Never roll the
            // logical promotion result back merely because post-commit cleanup failed.
        }
    }

    @Override
    public Optional<String> activeSnapshotId(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        return activeStore.loadActiveKnowledge(projectId).map(CodeKnowledgeSnapshot::snapshotId);
    }

    private void cleanupProviderWorkspaces(UUID runId, List<IndexingArtifact> artifacts) throws IOException {
        Path expectedRunRoot = runsRoot.resolve(runId.toString()).toAbsolutePath().normalize();
        for (IndexingArtifact artifact : artifacts) {
            Path parent = artifact.finalArtifact().toAbsolutePath().normalize().getParent();
            if (parent == null || !parent.startsWith(expectedRunRoot)) continue;
            Path workspace = parent.resolve("workspace").normalize();
            if (workspace.startsWith(expectedRunRoot) && Files.exists(workspace)) {
                deleteRecursively(workspace);
            }
        }
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
        if (portable.isBlank()) return "root";
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
        FileTreeOperations.deleteRecursively(path);
    }
}

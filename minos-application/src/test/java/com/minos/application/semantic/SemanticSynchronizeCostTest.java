package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one synchronization costs: an unchanged ready index is neither rebuilt nor rewritten, and an
 * incremental synchronization embeds only the documents whose content changed, without loading the
 * vectors of the previous index.
 */
class SemanticSynchronizeCostTest {

    private CountingSnapshotStore snapshots;
    private CountingVectorStore vectors;
    private ProjectResolver resolver;
    private RegisteredProject registered;

    @BeforeEach
    void wire(@TempDir Path root) throws Exception {
        for (String name : List.of("alpha", "beta", "gamma")) {
            Path source = root.resolve("src").resolve(name + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class " + name + " { void " + name + "() {} }\n", StandardCharsets.UTF_8);
        }
        InMemoryProjectRegistry registry = new InMemoryProjectRegistry();
        registered = registry.registerProject(root, "synchronize-cost");
        resolver = new ProjectResolver(registry);
        snapshots = new CountingSnapshotStore();
        vectors = new CountingVectorStore();
        snapshots.active = snapshot("snapshot-1", "core");
    }

    @Test
    void anUnchangedReadyIndexIsNeitherRebuiltNorRewritten() throws Exception {
        CountingProvider provider = new CountingProvider("model-a");
        SemanticIndexService index = service(provider);
        index.synchronize(registered.id());
        snapshots.loads = 0;
        provider.embeddings = 0;
        vectors.writes = 0;

        SemanticIndexService.UpdateReport report = index.synchronize(registered.id());

        assertEquals(0, snapshots.loads, "active snapshot loads (documents built from it)");
        assertEquals(0, provider.embeddings, "embeddings requested");
        assertEquals(0, vectors.writes, "store writes");
        assertEquals(SemanticIndexService.State.READY, report.state());
        assertEquals(0, report.embeddedCount());
        assertEquals(report.documentCount(), report.reused());
        assertEquals(vectors.stored.documents().size(), report.documentCount());
    }

    @Test
    void anotherModelTakesTheFullPath() throws Exception {
        service(new CountingProvider("model-a")).synchronize(registered.id());
        int documents = vectors.stored.documents().size();
        CountingProvider other = new CountingProvider("model-b");

        SemanticIndexService.UpdateReport report = service(other).synchronize(registered.id());

        assertEquals(documents, other.embeddings, "every document is embedded again");
        assertEquals(2, vectors.writes, "one more write");
        assertEquals("model-b", vectors.stored.modelId());
        assertEquals(documents, report.embeddedCount());
    }

    @Test
    void oneChangedDocumentIsTheOnlyOneEmbeddedAndThePreviousVectorsAreNotLoaded() throws Exception {
        CountingProvider provider = new CountingProvider("model-a");
        SemanticIndexService index = service(provider);
        index.synchronize(registered.id());
        SemanticVectorStore.IndexSnapshot before = vectors.stored;
        provider.embeddings = 0;
        vectors.loads = 0;
        // The module only appears in the symbol document of beta: its chunk is read from the source
        // and the file summary does not carry the module.
        snapshots.active = snapshot("snapshot-2", "core", "beta", "other-module");

        SemanticIndexService.UpdateReport report = index.synchronize(registered.id());

        assertEquals(1, provider.embeddings, "embeddings requested");
        assertEquals(1, report.embeddedChanged());
        assertEquals(before.documents().size() - 1, report.reused());
        assertEquals(0, vectors.loads, "full loads of the previous index");
        assertEquals("snapshot-2", vectors.stored.snapshotId());
        assertEquals(before.documents().size(), vectors.stored.documents().size());
        for (SemanticVectorStore.IndexedDocument document : vectors.stored.documents()) {
            assertEquals("snapshot-2", document.document().snapshotId());
            assertTrue(document.vector() != null && document.vector().dimensions() == 2,
                    "every stored document keeps a vector: " + document.document().stableKey());
        }
    }

    private SemanticIndexService service(CountingProvider provider) {
        return new SemanticIndexService(resolver, snapshots, vectors, Optional.of(provider));
    }

    private CodeKnowledgeSnapshot snapshot(String snapshotId, String module) {
        return snapshot(snapshotId, module, null, null);
    }

    private CodeKnowledgeSnapshot snapshot(String snapshotId, String module, String changed, String changedModule) {
        String projectId = registered.id().toString();
        List<Symbol> symbols = List.of("alpha", "beta", "gamma").stream()
                .map(name -> symbol(projectId, name, name.equals(changed) ? changedModule : module))
                .toList();
        return new CodeKnowledgeSnapshot(registered.id(), snapshotId, symbols, List.of(), List.of());
    }

    private static Symbol symbol(String projectId, String name, String module) {
        return new Symbol("sym-" + name, "key-" + name, SymbolIdentityQuality.CANONICAL, projectId, module,
                "src/" + name + ".java", null, SymbolKind.METHOD, name, "pkg." + name, "void " + name + "()", "java",
                new SymbolLocation("src/" + name + ".java", 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }

    /** Active snapshot whose full loads are counted apart from reads of its identifier alone. */
    private static final class CountingSnapshotStore implements CodeKnowledgeSnapshotStore {
        private CodeKnowledgeSnapshot active;
        private int loads;

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            loads++;
            return Optional.of(active);
        }

        @Override
        public Optional<String> activeSnapshotId(UUID projectId) {
            return Optional.of(active.snapshotId());
        }

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            throw new UnsupportedOperationException("legacy symbol snapshots are not used by this test");
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            throw new UnsupportedOperationException("query views are not used by this test");
        }
    }

    /** In-memory index that counts full loads and writes. */
    private static final class CountingVectorStore implements SemanticVectorStore {
        private IndexSnapshot stored;
        private int loads;
        private int writes;

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            loads++;
            return Optional.ofNullable(stored);
        }

        @Override
        public Optional<IndexMetadata> metadata(String projectId) {
            return Optional.ofNullable(stored).map(index -> new IndexMetadata(index.projectId(), index.snapshotId(),
                    index.providerId(), index.modelId(), index.dimensions(), index.builtAtEpochMilli(),
                    index.documents().size()));
        }

        @Override
        public Optional<IndexChecksums> checksums(String projectId) {
            return metadata(projectId).map(metadata -> {
                Map<String, String> checksums = new HashMap<>();
                for (IndexedDocument value : stored.documents()) {
                    checksums.put(value.document().stableKey(), value.document().checksum());
                }
                return new IndexChecksums(metadata, checksums);
            });
        }

        @Override
        public long sizeBytes(String projectId) {
            return stored == null ? 0L : 1_024L;
        }

        @Override
        public void replace(IndexSnapshot snapshot) throws IOException {
            writes++;
            stored = snapshot.withStoredVectors(Optional.ofNullable(stored));
        }

        @Override
        public void delete(String projectId) {
            writes++;
            stored = null;
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) throws IOException {
            replace(next);
        }
    }

    private static final class CountingProvider implements EmbeddingProvider {
        private final String model;
        private int embeddings;

        CountingProvider(String model) {
            this.model = model;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String modelId() {
            return model;
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public SemanticVector embed(String stableKey, String text) {
            embeddings++;
            return SemanticVector.fromArray(stableKey, new double[] {1.0, text.length()});
        }
    }

    private static final class InMemoryProjectRegistry implements ProjectRegistry {
        private final Map<UUID, RegisteredProject> projects = new HashMap<>();

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            Instant now = Instant.parse("2026-10-14T08:00:00Z");
            RegisteredProject project = new RegisteredProject(
                    UUID.randomUUID(), rootPath, displayName, Optional.empty(), now, now);
            projects.put(project.id(), project);
            return project;
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public List<RegisteredProject> listProjects() {
            return List.copyOf(projects.values());
        }

        @Override
        public RegisteredWorkspace createWorkspace(String name) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public RegisteredProject removeProjectFromWorkspace(UUID projectId) {
            throw new UnsupportedOperationException("workspaces are not used by this test");
        }

        @Override
        public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredWorkspace> listWorkspaces() {
            return List.of();
        }
    }
}

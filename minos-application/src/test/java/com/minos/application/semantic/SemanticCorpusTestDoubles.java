package com.minos.application.semantic;

import com.minos.domain.Relationship;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeReader;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SnapshotReadView;
import com.minos.store.SymbolSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test doubles of the semantic corpus consultation: provider, vector store, snapshot store and registry. */
final class SemanticCorpusTestDoubles {
    static final int DIMENSIONS = 32;

    private SemanticCorpusTestDoubles() {
    }

    /** A provider whose vectors are never asked for by a consultation: any call is counted, then refused. */
    static final class RefusingProvider implements EmbeddingProvider {
        boolean refuseEveryCall;
        int calls;

        @Override
        public String id() {
            return "fixture-provider";
        }

        @Override
        public String modelId() {
            return "fixture-model";
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        @Override
        public List<String> limitations() {
            return List.of("REFUSING_PROVIDER_LIMITATION");
        }

        @Override
        public SemanticVector embed(String stableKey, String text) throws IOException {
            calls++;
            if (refuseEveryCall) throw new IOException("the embedding provider is unreachable");
            return new LocalHashEmbeddingProvider(DIMENSIONS).embed(stableKey, text);
        }
    }

    /** One index per project, read through the defaults of the port; it can be replaced right after a search. */
    static final class InMemoryVectorStore implements SemanticVectorStore {
        final Map<String, IndexSnapshot> indexes = new HashMap<>();
        IndexSnapshot replacementAfterNextSearch;

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            return Optional.ofNullable(indexes.get(projectId));
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            indexes.put(snapshot.projectId(), snapshot);
        }

        @Override
        public void delete(String projectId) {
            indexes.remove(projectId);
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) {
            throw new UnsupportedOperationException("conditional replacement is not used by this test");
        }

        @Override
        public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore)
                throws IOException {
            List<VectorHit> hits = SemanticVectorStore.super.search(projectId, query, limit, minimumScore);
            if (replacementAfterNextSearch != null) {
                indexes.put(projectId, replacementAfterNextSearch);
                replacementAfterNextSearch = null;
            }
            return hits;
        }
    }

    /**
     * Serves one read view per active snapshot and counts the calls, like a store that caches it; {@link #evict()}
     * drops the view as the memory budget would.
     */
    static final class FixedSnapshotStore implements CodeKnowledgeSnapshotStore {
        CodeKnowledgeSnapshot active;
        SnapshotReadView view;
        int readViewCalls;
        int viewBuilds;

        FixedSnapshotStore(CodeKnowledgeSnapshot active) {
            this.active = active;
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            return Optional.ofNullable(active);
        }

        @Override
        public Optional<SnapshotReadView> loadActiveReadView(UUID projectId) {
            readViewCalls++;
            if (active == null) return Optional.empty();
            if (view == null || view.snapshot() != active) {
                viewBuilds++;
                view = SnapshotReadView.indexed(new SnapshotDescriptor(3, active.snapshotId(), "fixture",
                        "f".repeat(64), active.symbols().size(), active.occurrences().size(),
                        active.relationships().size()), active);
            }
            return Optional.of(view);
        }

        @Override
        public Optional<String> activeSnapshotId(UUID projectId) {
            return Optional.ofNullable(active).map(CodeKnowledgeSnapshot::snapshotId);
        }

        /** Drops the view and replaces the active snapshot by an equal one made of new symbol objects. */
        void evictAndForgetSymbols() {
            view = null;
            List<Symbol> reloaded = active.symbols().stream()
                    .map(symbol -> new Symbol(symbol.id(), symbol.symbolKey(), symbol.identityQuality(),
                            symbol.projectId(), symbol.moduleId(), symbol.fileId(), symbol.parentSymbolId(),
                            symbol.kind(), symbol.name(), symbol.qualifiedName(), symbol.signature(),
                            symbol.language(), symbol.location(), symbol.resolutionStatus(), symbol.origin(),
                            symbol.external(), symbol.generated(), symbol.providerReferences()))
                    .toList();
            active = new CodeKnowledgeSnapshot(active.projectId(), active.snapshotId(), reloaded,
                    active.occurrences(), active.relationships());
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

    /**
     * A reader that delegates every read to the reader of a view and counts the reads that describe documents:
     * the symbols of a file (calls, symbols returned, time spent) and the symbols read by identifier.
     */
    static final class CountingReader implements CodeKnowledgeReader {
        private final CodeKnowledgeReader delegate;
        int fileSymbolsCalls;
        long fileSymbolsReturned;
        long fileSymbolsNanos;
        int symbolByIdCalls;

        CountingReader(CodeKnowledgeReader delegate) {
            this.delegate = delegate;
        }

        /** The view {@code indexed} with this counting reader in place of its own, same snapshot and build time. */
        static SnapshotReadView wrap(SnapshotReadView indexed, CountingReader reader) {
            return new SnapshotReadView(indexed.descriptor(), indexed.snapshot(), reader, indexed.buildNanos());
        }

        void reset() {
            fileSymbolsCalls = 0;
            fileSymbolsReturned = 0L;
            fileSymbolsNanos = 0L;
            symbolByIdCalls = 0;
        }

        @Override
        public Optional<Symbol> findSymbolById(String projectId, String symbolId) {
            symbolByIdCalls++;
            return delegate.findSymbolById(projectId, symbolId);
        }

        @Override
        public List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria) {
            return delegate.findSymbols(projectId, criteria);
        }

        @Override
        public boolean lacksModuleAttribution(String projectId) {
            return delegate.lacksModuleAttribution(projectId);
        }

        @Override
        public List<Symbol> findFileSymbols(String projectId, String fileId, int limit) {
            long started = System.nanoTime();
            List<Symbol> symbols = delegate.findFileSymbols(projectId, fileId, limit);
            fileSymbolsNanos += System.nanoTime() - started;
            fileSymbolsCalls++;
            fileSymbolsReturned += symbols.size();
            return symbols;
        }

        @Override
        public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) {
            return delegate.findUsages(projectId, symbolId, limit);
        }

        @Override
        public List<Relationship> findRelationships(String projectId, RelationshipSearchCriteria criteria) {
            return delegate.findRelationships(projectId, criteria);
        }
    }

    static final class InMemoryProjectRegistry implements ProjectRegistry {
        final Map<UUID, RegisteredProject> projects = new HashMap<>();

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            Instant now = Instant.parse("2026-11-13T08:00:00Z");
            RegisteredProject registered = new RegisteredProject(
                    UUID.randomUUID(), rootPath, displayName, Optional.empty(), now, now);
            projects.put(registered.id(), registered);
            return registered;
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

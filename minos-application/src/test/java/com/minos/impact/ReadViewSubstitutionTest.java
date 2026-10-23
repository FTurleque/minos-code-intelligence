package com.minos.impact;

import com.minos.application.ProjectQueryService;
import com.minos.application.dynamic.RuntimeIntelligenceService;
import com.minos.context.CodeSearchCriteria;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeReader;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SnapshotReadView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-07 (ADR 0056) : les cas d'usage de lecture lisent la vue neutre du snapshot actif. Un store dont la vue
 * porte un lecteur qui n'est pas l'index mémoire, et qui refuse l'ancien contrat, leur suffit ; leurs réponses sont
 * celles de l'index mémoire sur le même snapshot.
 */
@SuppressWarnings("deprecation")
class ReadViewSubstitutionTest {

    private static final String PROJECT = ImpactReferenceFixture.PROJECT_ID.toString();

    /** Racine du projet enregistré : la recherche de code lit les sources sous une racine qui existe. */
    @TempDir
    static Path projectRoot;

    @Test
    void queriesThroughANonMemoryReaderAnswerAsTheMemoryIndex() throws Exception {
        CodeKnowledgeSnapshot snapshot = ImpactReferenceFixture.snapshot();
        ReaderOnlyStore substitute = new ReaderOnlyStore(snapshot);
        LegacyStore memory = new LegacyStore(snapshot);
        ProjectQueryService bySubstitute = new ProjectQueryService(new Registry(), substitute);
        ProjectQueryService byMemory = new ProjectQueryService(new Registry(), memory);
        String symbol = ImpactReferenceFixture.symbolId(3);
        SymbolSearchCriteria symbols = SymbolSearchCriteria.lexical("s0", 20);
        RelationshipSearchCriteria relationships = RelationshipSearchCriteria.incoming(
                new CodeEntityRef(CodeEntityType.SYMBOL, symbol), Set.of(), 50);
        CodeSearchCriteria search = new CodeSearchCriteria(SymbolSearchCriteria.lexical("s01", 5), 1, 5, 5, 0, 1024, false);

        assertEquals(byMemory.findSymbols(PROJECT, symbols), bySubstitute.findSymbols(PROJECT, symbols));
        assertEquals(byMemory.getFileSymbols(PROJECT, "file-1", 20), bySubstitute.getFileSymbols(PROJECT, "file-1", 20));
        assertEquals(byMemory.findUsages(PROJECT, symbol, 20), bySubstitute.findUsages(PROJECT, symbol, 20));
        assertEquals(byMemory.findRelationships(PROJECT, relationships),
                bySubstitute.findRelationships(PROJECT, relationships));
        assertFalse(bySubstitute.findRelationships(PROJECT, relationships).isEmpty(), "the fixture has relationships");
        assertEquals(byMemory.searchCode(PROJECT, search), bySubstitute.searchCode(PROJECT, search));
        assertTrue(substitute.reader.calls.containsAll(List.of("findSymbols", "findFileSymbols", "findUsages",
                "findRelationships")), "every read went through the substitute reader: " + substitute.reader.calls);
    }

    @Test
    void impactThroughANonMemoryReaderRendersTheReference() throws Exception {
        ReaderOnlyStore substitute = new ReaderOnlyStore(ImpactReferenceFixture.snapshot());
        LocalProjectImpactQuery query = new LocalProjectImpactQuery(new Registry(), substitute);

        StringBuilder rendered = new StringBuilder();
        for (ImpactAnalysisRequest request : ImpactReferenceFixture.requests()) {
            rendered.append(ImpactReferenceFixture.digestLine(query.analyzeImpact(PROJECT, request)));
        }

        assertEquals(ImpactReferenceFixture.reference(ImpactReferenceReportsTest.REFERENCE), rendered.toString());
        assertTrue(substitute.reader.calls.contains("findSymbolById"));
    }

    @Test
    void theRuntimeReportOfASymbolReadsTheSubstituteReader() throws Exception {
        CodeKnowledgeSnapshot snapshot = ImpactReferenceFixture.snapshot();
        ReaderOnlyStore substitute = new ReaderOnlyStore(snapshot);
        RuntimeIntelligenceService runtime = new RuntimeIntelligenceService(new Registry(), substitute, new NoSessions());
        RuntimeIntelligenceService byMemory = new RuntimeIntelligenceService(new Registry(), new LegacyStore(snapshot),
                new NoSessions());
        String symbol = ImpactReferenceFixture.symbolId(0);

        assertEquals(byMemory.symbolReport(PROJECT, symbol, null, 5), runtime.symbolReport(PROJECT, symbol, null, 5));
        assertEquals(List.of("findSymbolById"), substitute.reader.calls);
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> runtime.symbolReport(PROJECT, "absent", null, 5));
        assertEquals("unknown symbol in active snapshot: absent", unknown.getMessage());
    }

    @Test
    void withoutAnActiveSnapshotTheReadsFailAsBefore() {
        ReaderOnlyStore empty = new ReaderOnlyStore(null);
        ProjectQueryService queries = new ProjectQueryService(new Registry(), empty);
        LocalProjectImpactQuery impact = new LocalProjectImpactQuery(new Registry(), empty);

        IllegalStateException search = assertThrows(IllegalStateException.class,
                () -> queries.findSymbols(PROJECT, SymbolSearchCriteria.lexical("s0", 5)));
        IllegalStateException analysis = assertThrows(IllegalStateException.class,
                () -> impact.analyzeImpact(PROJECT, new ImpactAnalysisRequest("s00", 2, 10)));

        assertEquals("project has no active symbol snapshot: " + PROJECT, search.getMessage());
        assertEquals("project has no active code knowledge snapshot: " + PROJECT, analysis.getMessage());
    }

    private static SnapshotDescriptor descriptor(CodeKnowledgeSnapshot snapshot) {
        return new SnapshotDescriptor(3, snapshot.snapshotId(), snapshot.snapshotId() + ".bin", "0".repeat(64),
                snapshot.symbols().size(), snapshot.occurrences().size(), snapshot.relationships().size());
    }

    /** Lecteur de test qui n'est pas l'index mémoire : il relaie chaque lecture et la note. */
    private static final class RecordingReader implements CodeKnowledgeReader {
        private final CodeKnowledgeReader delegate;
        private final List<String> calls = new ArrayList<>();

        private RecordingReader(CodeKnowledgeReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Symbol> findSymbolById(String projectId, String symbolId) {
            calls.add("findSymbolById");
            return delegate.findSymbolById(projectId, symbolId);
        }

        @Override
        public List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria) {
            calls.add("findSymbols");
            return delegate.findSymbols(projectId, criteria);
        }

        @Override
        public boolean lacksModuleAttribution(String projectId) {
            calls.add("lacksModuleAttribution");
            return delegate.lacksModuleAttribution(projectId);
        }

        @Override
        public List<Symbol> findFileSymbols(String projectId, String fileId, int limit) {
            calls.add("findFileSymbols");
            return delegate.findFileSymbols(projectId, fileId, limit);
        }

        @Override
        public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) {
            calls.add("findUsages");
            return delegate.findUsages(projectId, symbolId, limit);
        }

        @Override
        public List<Relationship> findRelationships(String projectId, RelationshipSearchCriteria criteria) {
            calls.add("findRelationships");
            return delegate.findRelationships(projectId, criteria);
        }
    }

    /** Sert la vue neutre avec le lecteur de test ; l'ancien contrat et le snapshot brut sont refusés. */
    private static final class ReaderOnlyStore extends ReadOnlyStore {
        private final SnapshotReadView view;
        private final RecordingReader reader;

        private ReaderOnlyStore(CodeKnowledgeSnapshot snapshot) {
            this.reader = snapshot == null ? null : new RecordingReader(new InMemoryCodeKnowledgeStore(snapshot));
            this.view = snapshot == null ? null : new SnapshotReadView(descriptor(snapshot), snapshot, reader, 0L);
        }

        @Override
        public Optional<SnapshotReadView> loadActiveReadView(UUID projectId) {
            return Optional.ofNullable(view);
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            throw new IllegalStateException("the legacy view is not served by this store");
        }
    }

    /** Store de l'ancien contrat, sur l'index mémoire : la référence des réponses. */
    private static final class LegacyStore extends ReadOnlyStore {
        private final SnapshotQueryView view;

        private LegacyStore(CodeKnowledgeSnapshot snapshot) {
            this.view = new SnapshotQueryView(descriptor(snapshot), snapshot, new InMemoryCodeKnowledgeStore(snapshot), 0L);
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return Optional.of(view);
        }
    }

    private abstract static class ReadOnlyStore implements CodeKnowledgeSnapshotStore {
        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("read-only test store");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("read-only test store");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            throw new UnsupportedOperationException("raw snapshots are not read by the query use cases");
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            throw new UnsupportedOperationException("raw snapshots are not read by the query use cases");
        }
    }

    /** Aucune session d'observation : le rapport d'un symbole ne lit que le snapshot. */
    private static final class NoSessions implements RuntimeObservationStore {
        @Override
        public SaveResult save(CorrelatedRuntimeSession session) {
            throw new UnsupportedOperationException("read-only test store");
        }

        @Override
        public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public List<CorrelatedRuntimeSession> list(UUID projectId) throws IOException {
            return List.of();
        }
    }

    /** Registre réduit au projet de la référence. */
    private static final class Registry implements ProjectRegistry {
        private final RegisteredProject project = new RegisteredProject(ImpactReferenceFixture.PROJECT_ID,
                projectRoot, "read-view-substitution", Optional.empty(),
                Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z"));

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException("read-only test registry");
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return project.id().equals(projectId) ? Optional.of(project) : Optional.empty();
        }

        @Override
        public List<RegisteredProject> listProjects() {
            return List.of(project);
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

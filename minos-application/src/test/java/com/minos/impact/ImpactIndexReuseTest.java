package com.minos.impact;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SnapshotReadView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-11 : l'analyse d'impact lit l'index des arêtes entrantes de la vue de requête, construit une fois par
 * vue, et rend exactement les rapports de la référence figée avant ce changement.
 */
class ImpactIndexReuseTest {

    private static final String PROJECT = ImpactReferenceFixture.PROJECT_ID.toString();

    @Test
    void twoAnalysesOnTheSameViewBuildTheIncomingIndexOnce() throws Exception {
        Store store = new Store(view());
        LocalProjectImpactQuery query = new LocalProjectImpactQuery(new Registry(), store);

        ImpactAnalysisReport first = query.analyzeImpact(PROJECT, new ImpactAnalysisRequest("s00", 8, 200));
        ImpactAnalysisReport second = query.analyzeImpact(PROJECT, new ImpactAnalysisRequest("s01", 8, 200));

        assertEquals(1L, memoryIndex(store.view.readView()).symbolEdgeIndexBuilds());
        assertTrue(referenceLines().contains(ImpactReferenceFixture.digestLine(first)));
        assertTrue(referenceLines().contains(ImpactReferenceFixture.digestLine(second)));
    }

    @Test
    void impactQueryReadsTheQueryViewAndNeverTheRawSnapshot() throws Exception {
        Store store = new Store(view());
        store.rawKnowledgeFails = true;
        LocalProjectImpactQuery query = new LocalProjectImpactQuery(new Registry(), store);

        StringBuilder rendered = new StringBuilder();
        for (ImpactAnalysisRequest request : ImpactReferenceFixture.requests()) {
            rendered.append(ImpactReferenceFixture.digestLine(query.analyzeImpact(PROJECT, request)));
        }

        assertEquals(ImpactReferenceFixture.reference(ImpactReferenceReportsTest.REFERENCE), rendered.toString());
        assertEquals(1L, memoryIndex(store.view.readView()).symbolEdgeIndexBuilds());
    }

    @Test
    void serviceOnAReadViewRendersTheReference() throws Exception {
        SnapshotReadView view = view().readView();
        ImpactAnalysisService service = new ImpactAnalysisService();

        StringBuilder rendered = new StringBuilder();
        for (ImpactAnalysisRequest request : ImpactReferenceFixture.requests()) {
            rendered.append(ImpactReferenceFixture.digestLine(service.analyze(view, request)));
        }

        assertEquals(ImpactReferenceFixture.reference(ImpactReferenceReportsTest.REFERENCE), rendered.toString());
    }

    /** L'ancien contrat reste servi : même rapport, et l'index de la vue n'est construit qu'une fois. */
    @Test
    @SuppressWarnings("deprecation")
    void serviceOnTheLegacyViewRendersTheReferenceWithTheSameIndex() throws Exception {
        SnapshotQueryView view = view();
        ImpactAnalysisService service = new ImpactAnalysisService();

        StringBuilder rendered = new StringBuilder();
        for (ImpactAnalysisRequest request : ImpactReferenceFixture.requests()) {
            rendered.append(ImpactReferenceFixture.digestLine(service.analyze(view, request)));
        }

        assertEquals(ImpactReferenceFixture.reference(ImpactReferenceReportsTest.REFERENCE), rendered.toString());
        assertEquals(1L, view.queryStore().symbolEdgeIndexBuilds());
    }

    @Test
    void simultaneousFirstAnalysesAllRenderTheExpectedReport() throws Exception {
        SnapshotReadView view = view().readView();
        ImpactAnalysisService service = new ImpactAnalysisService();
        List<ImpactAnalysisRequest> requests = ImpactReferenceFixture.requests().subList(0, 12);
        int threads = requests.size();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (ImpactAnalysisRequest request : requests) {
                Callable<String> task = () -> {
                    start.await();
                    return ImpactReferenceFixture.digestLine(service.analyze(view, request));
                };
                results.add(executor.submit(task));
            }
            start.countDown();
            List<String> expected = referenceLines().subList(0, threads);
            for (int index = 0; index < threads; index++) {
                assertEquals(expected.get(index), results.get(index).get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, memoryIndex(view).symbolEdgeIndexBuilds());
    }

    /** Compteur de constructions de l'index mémoire que porte la vue de lecture. */
    private static InMemoryCodeKnowledgeStore memoryIndex(SnapshotReadView view) {
        return (InMemoryCodeKnowledgeStore) view.reader();
    }

    private static List<String> referenceLines() throws Exception {
        return ImpactReferenceFixture.reference(ImpactReferenceReportsTest.REFERENCE).lines()
                .map(line -> line + '\n').toList();
    }

    @SuppressWarnings("deprecation")
    private static SnapshotQueryView view() {
        CodeKnowledgeSnapshot snapshot = ImpactReferenceFixture.snapshot();
        return new SnapshotQueryView(
                new SnapshotDescriptor(3, snapshot.snapshotId(), snapshot.snapshotId() + ".bin", "0".repeat(64),
                        snapshot.symbols().size(), snapshot.occurrences().size(), snapshot.relationships().size()),
                snapshot, new InMemoryCodeKnowledgeStore(snapshot), 0L);
    }

    /** Snapshot actif unique ; la lecture du snapshot brut peut être interdite pour prouver le chemin par la vue. */
    private static final class Store implements CodeKnowledgeSnapshotStore {
        private final SnapshotQueryView view;
        private boolean rawKnowledgeFails;

        private Store(SnapshotQueryView view) {
            this.view = view;
        }

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
            throw new UnsupportedOperationException("not used by impact analysis");
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            if (rawKnowledgeFails) throw new IllegalStateException("raw snapshot load is forbidden in this test");
            return Optional.of(view.snapshot());
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return Optional.of(view);
        }
    }

    /** Registre réduit au projet de la référence. */
    private static final class Registry implements ProjectRegistry {
        private final RegisteredProject project = new RegisteredProject(ImpactReferenceFixture.PROJECT_ID,
                Path.of("impact-reference").toAbsolutePath(), "impact-reference", Optional.empty(),
                Instant.parse("2026-10-14T00:00:00Z"), Instant.parse("2026-10-14T00:00:00Z"));

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

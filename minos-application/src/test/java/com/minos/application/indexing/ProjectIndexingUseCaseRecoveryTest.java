package com.minos.application.indexing;

import com.minos.application.resolution.ProjectResolver;
import com.minos.application.semantic.LocalHashEmbeddingProvider;
import com.minos.application.semantic.SemanticIndexService;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-02: the recovery of a run whose snapshot was promoted although the run did not end as a success, and the
 * semantic refresh of the product, which never fails a structured index.
 */
class ProjectIndexingUseCaseRecoveryTest {

    @TempDir Path temp;

    @Test
    void aRunThatPromotedItsSnapshotBeforeFailingIsRecoveredAsASuccess() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.promotionFailure = new IllegalStateException("state persistence failed after promotion");

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals("SUCCEEDED", outcome.status());
        assertEquals(Optional.of("snapshot-1"), outcome.activeSnapshotId());
        IndexingRun run = outcome.run().orElseThrow();
        assertEquals(IndexingRun.Phase.COMPLETED, run.phase());
        assertEquals(Optional.of("snapshot-1"), run.activeSnapshotAfter());
        assertEquals(run, fixture.stateStore.findRun(run.id()).orElseThrow());
        ProjectIndexState state = fixture.stateStore.findProjectState(fixture.project.id()).orElseThrow();
        assertEquals(ProjectIndexState.Availability.READY, state.availability());
        assertEquals(Optional.of("snapshot-1"), state.activeSnapshotId());
        assertTrue(outcome.fingerprintPromoted());
    }

    @Test
    void withoutAnEmbeddingProviderTheSemanticRefreshIsSkipped() {
        IndexingFixture fixture = fixture();
        var refresh = ProjectIndexingUseCase.semanticRefresh(semanticService(fixture, Optional.empty(), null));

        assertNull(refresh.refresh(fixture.project.id()));
    }

    @Test
    void aSemanticRefreshThatFailsIsADiagnosticThatNamesTheFailure() {
        IndexingFixture fixture = fixture();
        var named = ProjectIndexingUseCase.semanticRefresh(semanticService(fixture,
                Optional.of(new LocalHashEmbeddingProvider()), new IOException("vector store unavailable")));
        var anonymous = ProjectIndexingUseCase.semanticRefresh(semanticService(fixture,
                Optional.of(new LocalHashEmbeddingProvider()), new IOException(" ")));

        assertEquals("semantic index refresh failed without invalidating structured snapshot: vector store unavailable",
                named.refresh(fixture.project.id()));
        assertEquals("semantic index refresh failed without invalidating structured snapshot: IOException",
                anonymous.refresh(fixture.project.id()));
    }

    @Test
    void theSemanticRefreshNeedsAService() {
        assertThrows(NullPointerException.class, () -> ProjectIndexingUseCase.semanticRefresh(null));
    }

    private IndexingFixture fixture() {
        try {
            return new IndexingFixture(temp);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static SemanticIndexService semanticService(IndexingFixture fixture,
                                                        Optional<com.minos.application.semantic.EmbeddingProvider> provider,
                                                        IOException storeFailure) {
        return new SemanticIndexService(new ProjectResolver(new IndexingFixture.Registry(fixture.project)),
                fixture.snapshots, new EmptyVectorStore(storeFailure), provider);
    }

    /** An empty vector store; with a failure, every read fails with it. */
    private record EmptyVectorStore(IOException failure) implements SemanticVectorStore {
        @Override
        public Optional<IndexSnapshot> load(String projectId) throws IOException {
            if (failure != null) throw failure;
            return Optional.empty();
        }

        @Override public void replace(IndexSnapshot snapshot) throws IOException { load(snapshot.projectId()); }
        @Override public void delete(String projectId) throws IOException { load(projectId); }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) throws IOException {
            load(next.projectId());
        }
    }
}

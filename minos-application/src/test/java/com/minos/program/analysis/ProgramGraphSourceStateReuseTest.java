package com.minos.program.analysis;

import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-07 : un graphe de programme servi par le cache ne relit pas les sources. Sans empreinte exacte du
 * snapshot, la clé obtenue en hachant les sources est réutilisée pendant un délai borné, et la réponse le déclare.
 */
class ProgramGraphSourceStateReuseTest {

    private static final int FILES = 6;
    private static final Duration WITHIN_DELAY = Duration.ofSeconds(ProgramGraphService.SOURCE_STATE_REUSE_SECONDS - 1L);
    private static final Duration PAST_DELAY = Duration.ofSeconds(2L);

    @Test
    void secondCallWithExactFingerprintReadsNoSource(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, FILES);
        fixture.publishExactFingerprint();
        ProgramGraphService service = fixture.productionService();
        String project = fixture.project.id().toString();

        ProgramGraph first = service.getGraph(project);
        ProgramGraph[] second = new ProgramGraph[1];
        int reread = ProgramGraphSourceFixture.javaSourcesRead(() -> second[0] = service.getGraph(project));

        assertEquals(0, reread, "a cache hit must not read any source when the snapshot has an exact fingerprint");
        assertSame(first, second[0]);
        assertTrue(first.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertFalse(first.limitations().contains(ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION),
                "an exact fingerprint proves the indexed state: nothing to declare");
    }

    @Test
    void secondCallWithoutExactFingerprintReadsNoSourceInsideTheDelay(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, FILES);
        ProgramGraphService service = fixture.productionService();
        String project = fixture.project.id().toString();

        ProgramGraph first = service.getGraph(project);
        fixture.clock.advance(WITHIN_DELAY);
        ProgramGraph[] second = new ProgramGraph[1];
        int reread = ProgramGraphSourceFixture.javaSourcesRead(() -> second[0] = service.getGraph(project));

        assertEquals(0, reread, "inside the delay a cache hit must not read any source");
        assertSame(first, second[0]);
        assertTrue(first.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertTrue(first.limitations().contains(ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION),
                "without an exact fingerprint the graph is declared as a bounded-delay view of the disk");
    }

    @Test
    void callAfterTheDelayReadsEverySourceOnceAndKeepsAnUnchangedGraph(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, FILES);
        ProgramGraphService service = fixture.productionService();
        String project = fixture.project.id().toString();

        ProgramGraph first = service.getGraph(project);
        fixture.clock.advance(WITHIN_DELAY);
        fixture.clock.advance(PAST_DELAY);
        ProgramGraph[] later = new ProgramGraph[2];
        int rereadAfterDelay = ProgramGraphSourceFixture.javaSourcesRead(() -> later[0] = service.getGraph(project));
        int rereadRightAfter = ProgramGraphSourceFixture.javaSourcesRead(() -> later[1] = service.getGraph(project));

        assertEquals(FILES, rereadAfterDelay, "after the delay every source is read again, once");
        assertEquals(0, rereadRightAfter, "the new key is reused in turn");
        assertSame(first, later[0], "unchanged sources give the same key: the cached graph is kept");
        assertSame(first, later[1]);
        assertEquals(1L, service.cacheStats().misses());
        assertEquals(2L, service.cacheStats().hits());
    }

    @Test
    void changedSourceIsServedFromCacheInsideTheDelayAndRebuiltAfterIt(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, FILES);
        ProgramGraphService service = fixture.productionService();
        String project = fixture.project.id().toString();

        ProgramGraph first = service.getGraph(project);
        fixture.changeFirstSource();
        fixture.clock.advance(WITHIN_DELAY);
        ProgramGraph insideDelay = service.getGraph(project);
        fixture.clock.advance(PAST_DELAY);
        ProgramGraph afterDelay = service.getGraph(project);

        assertSame(first, insideDelay, "the change is not seen before the delay elapses: this is what is declared");
        assertNotSame(first, afterDelay, "after the delay the changed source gives a new key and a new graph");
        assertTrue(afterDelay.nodes().size() > first.nodes().size(), "the rebuilt graph contains the added method");
        assertTrue(afterDelay.limitations().contains(ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION));
        assertEquals(2L, service.cacheStats().misses());
        assertEquals(1, service.cacheStats().entries(), "the superseded graph is dropped");
    }

    @Test
    void modifiedSourceWithExactFingerprintIsStillRefusedByAFreshService(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, FILES);
        fixture.publishExactFingerprint();
        fixture.changeFirstSource();

        ProgramGraph graph = fixture.productionService().getGraph(fixture.project.id().toString());

        assertFalse(graph.supports(ProgramGraphCapability.CONTROL_FLOW));
        assertTrue(graph.limitations().contains(
                FingerprintConstrainedJavaProgramGraphProvider.SOURCE_MISMATCH_LIMITATION));
        assertFalse(graph.limitations().contains(ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION));
    }

    @Test
    void cacheKeyIsTheResolvedKeyAndOnlyTheSourceDerivedOneIsDeclared(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, FILES);
        CodeKnowledgeSnapshot snapshot = fixture.snapshots.loadActiveKnowledge(fixture.project.id()).orElseThrow();
        FingerprintConstrainedJavaProgramGraphProvider provider =
                new FingerprintConstrainedJavaProgramGraphProvider(fixture.fingerprints, fixture.clock);

        ProgramGraphProvider.CacheKeyResolution sourceDerived = provider.resolveCacheKey(fixture.project, snapshot);
        assertTrue(sourceDerived.sourceStateReused());
        assertEquals(sourceDerived.key(), provider.cacheKey(fixture.project, snapshot));

        fixture.publishExactFingerprint();
        ProgramGraphProvider.CacheKeyResolution exact = provider.resolveCacheKey(fixture.project, snapshot);
        assertFalse(exact.sourceStateReused());
        assertTrue(exact.key().contains(":snapshot-fingerprint:"));
        assertNotEquals(sourceDerived.key(), exact.key());
        assertEquals(exact.key(), provider.cacheKey(fixture.project, snapshot));
    }

    @Test
    void limitationNamesTheDelayItDeclares() {
        assertEquals("PROGRAM_GRAPH_REFLECTS_DISK_AS_OF_AT_MOST_5_SECONDS_AGO_NOT_INDEXED_STATE",
                ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION);
        assertTrue(ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION.contains(
                "_" + ProgramGraphService.SOURCE_STATE_REUSE_SECONDS + "_SECONDS_"));
    }
}

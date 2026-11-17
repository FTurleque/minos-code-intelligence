package com.minos.program.analysis;

import com.minos.application.resolution.ProjectResolver;
import com.minos.program.ProgramGraph;
import com.minos.store.cache.HeapShare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AUD-PERF-04 — the graph and fingerprint caches keep the smaller of their ceiling and their share of the heap. */
class ProgramGraphCacheHeapShareTest {

    private static final long SMALL_HEAP = 1_128_267_776L;
    private static final long LARGE_HEAP = 64L * 1024L * 1024L * 1024L;

    @Test
    void underASmallHeapBothCeilingsAreTheirShares(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 2);
        FingerprintConstrainedJavaProgramGraphProvider provider =
                new FingerprintConstrainedJavaProgramGraphProvider(fixture.fingerprints, fixture.clock, SMALL_HEAP);

        ProgramGraphService service = service(fixture, provider, SMALL_HEAP);

        assertEquals(HeapShare.PROGRAM_GRAPH.ceiling(ProgramGraphService.DEFAULT_MAX_CACHE_WEIGHT, SMALL_HEAP),
                service.cacheStats().maximumWeight());
        assertEquals(HeapShare.JAVA_FINGERPRINTS.ceiling(
                        FingerprintConstrainedJavaProgramGraphProvider.DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES, SMALL_HEAP),
                provider.fingerprintCacheStats().maximumWeightBytes());
    }

    @Test
    void underALargeHeapBothCeilingsAreTheConfiguredOnes(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 2);
        FingerprintConstrainedJavaProgramGraphProvider provider =
                new FingerprintConstrainedJavaProgramGraphProvider(fixture.fingerprints, fixture.clock, LARGE_HEAP);

        ProgramGraphService service = service(fixture, provider, LARGE_HEAP);

        assertEquals(ProgramGraphService.DEFAULT_MAX_CACHE_WEIGHT, service.cacheStats().maximumWeight());
        assertEquals(FingerprintConstrainedJavaProgramGraphProvider.DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES,
                provider.fingerprintCacheStats().maximumWeightBytes());
    }

    @Test
    void aGraphHeavierThanTheCeilingIsNotKeptAndTheAnswerIsTheSame(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 3);
        fixture.publishExactFingerprint();
        String projectId = fixture.project.id().toString();
        // A heap of 100 bytes: a share of 2 bytes holds no graph.
        ProgramGraphService tiny = service(fixture,
                new FingerprintConstrainedJavaProgramGraphProvider(fixture.fingerprints, fixture.clock, 100L), 100L);
        ProgramGraphService reference = service(fixture,
                new FingerprintConstrainedJavaProgramGraphProvider(fixture.fingerprints, fixture.clock, LARGE_HEAP),
                LARGE_HEAP);

        ProgramGraph first = tiny.getGraph(projectId);
        ProgramGraph second = tiny.getGraph(projectId);
        ProgramGraph kept = reference.getGraph(projectId);

        assertEquals(kept, first);
        assertEquals(kept, second);
        assertEquals(0L, tiny.cacheStats().weight());
        assertEquals(0, tiny.cacheStats().entries());
    }

    private static ProgramGraphService service(ProgramGraphSourceFixture fixture,
                                               FingerprintConstrainedJavaProgramGraphProvider provider, long heap) {
        return new ProgramGraphService(new ProjectResolver(fixture.registry), fixture.snapshots,
                List.of(new RelationshipProgramGraphProvider(), provider),
                ProgramGraphService.DEFAULT_MAX_CACHE_ENTRIES, ProgramGraphService.DEFAULT_MAX_CACHE_WEIGHT, heap);
    }
}

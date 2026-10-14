package com.minos.program.analysis;

import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ImpactReferenceFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-11 : l'impact avancé lit l'adjacence triée portée par l'entrée de cache du graphe de programme,
 * construite une fois pour ce graphe, et rend exactement les rapports de la référence.
 */
class AdvancedImpactAdjacencyReuseTest {

    @Test
    void twoAdvancedImpactsOnTheSameCachedGraphBuildTheAdjacencyOnce(@TempDir Path root) throws Exception {
        AdvancedImpactReferenceReportsTest.Fixture fixture = new AdvancedImpactReferenceReportsTest.Fixture(root);
        List<String> reference = referenceLines();

        String first = AdvancedImpactReferenceReportsTest.digestLine(fixture.service()
                .analyze(fixture.projectId(), new ImpactAnalysisRequest("s00", 6, 200)));
        String second = AdvancedImpactReferenceReportsTest.digestLine(fixture.service()
                .analyze(fixture.projectId(), new ImpactAnalysisRequest("s01", 6, 200)));

        assertEquals(1L, fixture.graphs().impactAdjacencyBuilds());
        assertEquals(1L, fixture.graphs().cacheStats().misses());
        assertTrue(reference.contains(first));
        assertTrue(reference.contains(second));
    }

    @Test
    void simultaneousFirstAdvancedImpactsAllRenderTheExpectedReport(@TempDir Path root) throws Exception {
        AdvancedImpactReferenceReportsTest.Fixture fixture = new AdvancedImpactReferenceReportsTest.Fixture(root);
        fixture.graphs().getGraph(fixture.projectId(), ProgramGraphService.PUBLIC_MAX_NODES,
                ProgramGraphService.PUBLIC_MAX_EDGES);
        List<ImpactAnalysisRequest> requests = AdvancedImpactReferenceReportsTest.requests().subList(0, 10);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<String>> results = new ArrayList<>();
            for (ImpactAnalysisRequest request : requests) {
                Callable<String> task = () -> {
                    start.await();
                    return AdvancedImpactReferenceReportsTest.digestLine(
                            fixture.service().analyze(fixture.projectId(), request));
                };
                results.add(executor.submit(task));
            }
            start.countDown();
            List<String> expected = referenceLines().subList(0, requests.size());
            for (int index = 0; index < requests.size(); index++) {
                assertEquals(expected.get(index), results.get(index).get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, fixture.graphs().impactAdjacencyBuilds());
    }

    private static List<String> referenceLines() throws Exception {
        return ImpactReferenceFixture.reference(AdvancedImpactReferenceReportsTest.REFERENCE).lines()
                .map(line -> line + '\n').toList();
    }
}

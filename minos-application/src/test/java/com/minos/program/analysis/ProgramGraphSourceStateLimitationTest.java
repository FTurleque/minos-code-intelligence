package com.minos.program.analysis;

import com.minos.domain.Symbol;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.LocalProjectImpactQuery;
import com.minos.output.AdvancedAnalysisResultRenderer;
import com.minos.program.ProgramGraph;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-07, D3 : la limitation est émise une fois, par le service du graphe, et relayée telle quelle par le
 * graphe de programme, l'impact avancé et les chemins de sécurité.
 */
class ProgramGraphSourceStateLimitationTest {

    private static final String LIMITATION = ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION;

    @Test
    void theThreeAnalysesCarryTheSameLimitationWithoutExactFingerprint(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 3);

        Limitations observed = analyze(fixture);

        assertEquals(1L, count(observed.graph()));
        assertEquals(1L, count(observed.impact()));
        assertEquals(1L, count(observed.security()));
        for (String rendered : observed.rendered()) {
            assertTrue(rendered.contains("\"" + LIMITATION + "\""), "the rendered output relays the identifier unchanged");
        }
    }

    @Test
    void noneOfTheThreeAnalysesCarriesItWithAnExactFingerprint(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 3);
        fixture.publishExactFingerprint();

        Limitations observed = analyze(fixture);

        assertEquals(0L, count(observed.graph()));
        assertEquals(0L, count(observed.impact()));
        assertEquals(0L, count(observed.security()));
        for (String rendered : observed.rendered()) {
            assertFalse(rendered.contains(LIMITATION));
        }
    }

    @Test
    void snapshotWithoutJavaSourceDeclaresNothing(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 0);

        ProgramGraph graph = fixture.productionService().getGraph(fixture.project.id().toString());

        assertFalse(graph.limitations().contains(LIMITATION),
                "no source was read: the graph is the indexed state, there is nothing to declare");
    }

    @Test
    void sourceProviderWithoutFingerprintStoreStillHashesOnEveryCallAndDeclaresNothing(@TempDir Path root)
            throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 3);
        ProgramGraphService service = fixture.service(ProgramGraphService.productionProviders());
        String project = fixture.project.id().toString();

        ProgramGraph first = service.getGraph(project);
        int reread = ProgramGraphSourceFixture.javaSourcesRead(() -> service.getGraph(project));

        assertEquals(3, reread, "this provider reuses nothing, so its graph follows the disk on every call");
        assertFalse(first.limitations().contains(LIMITATION));
    }

    @Test
    void providerDeclarationReachesTheGraphAndABlankKeyIsRefused(@TempDir Path root) throws Exception {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 1);
        String project = fixture.project.id().toString();

        ProgramGraph declared = fixture.service(List.of(new DeclaringProvider("declared-key"))).getGraph(project);
        assertTrue(declared.limitations().contains(LIMITATION));

        ProgramGraphService blank = fixture.service(List.of(new DeclaringProvider(" ")));
        assertThrows(IllegalStateException.class, () -> blank.getGraph(project));
    }

    private static Limitations analyze(ProgramGraphSourceFixture fixture) throws Exception {
        ProgramGraphService service = fixture.productionService();
        String project = fixture.project.id().toString();
        Symbol symbol = fixture.snapshots.loadActiveKnowledge(fixture.project.id()).orElseThrow().symbols().getFirst();

        ProgramGraph graph = service.getGraph(project);
        AdvancedImpactService.AdvancedImpactReport impact = new AdvancedImpactService(
                new LocalProjectImpactQuery(fixture.registry, fixture.snapshots), service)
                .analyze(project, ImpactAnalysisRequest.defaults(symbol.id()));
        SecurityAnalysisService.SecurityReport security = new SecurityAnalysisService(service)
                .analyze(project, new SecurityAnalysisService.SecurityRequest(null, 8, 10));
        return new Limitations(graph.limitations(), impact.limitations(), security.limitations(), List.of(
                AdvancedAnalysisResultRenderer.renderProgramGraph(graph),
                AdvancedAnalysisResultRenderer.renderAdvancedImpact(impact),
                AdvancedAnalysisResultRenderer.renderSecurity(security)));
    }

    private static long count(List<String> limitations) {
        return limitations.stream().filter(LIMITATION::equals).count();
    }

    private record Limitations(List<String> graph, List<String> impact, List<String> security, List<String> rendered) {
    }

    /** Fournisseur minimal qui déclare lui-même une clé réutilisée. */
    private record DeclaringProvider(String key) implements ProgramGraphProvider {
        @Override
        public String id() {
            return "declaring-provider";
        }

        @Override
        public CacheKeyResolution resolveCacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
            return new CacheKeyResolution(key, true);
        }

        @Override
        public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
            return new ProgramGraph(project.id().toString(), snapshot.snapshotId(), null, null, null, null);
        }
    }
}

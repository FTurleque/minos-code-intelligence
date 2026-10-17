package com.minos.program.analysis;

import com.minos.domain.Symbol;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.LocalProjectImpactQuery;
import com.minos.program.ProgramGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-SEC-11 : les règles source, puits et assainisseur viennent du dépôt analysé ; toute réponse qui s'en sert le
 * déclare par une limitation, relayée par le graphe de programme, l'impact avancé et les chemins de sécurité. Un
 * assainisseur ne retire pas un chemin : il le marque.
 */
class ProgramGraphSecurityRulesProvenanceTest {

    private static final String PROVENANCE = "SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY";
    private static final String NOT_CONFIGURED = "JAVA_SECURITY_RULES_NOT_CONFIGURED";
    private static final String RULES = ".minos/java-advanced-provider.properties";
    private static final String HELPERS = """
                static String source() { return "input"; }
                static String sanitize(String value) { return value; }
                static void sink(String value) { }
            """;

    @Test
    void aRepositoryThatSuppliesItsRulesIsDeclaredByTheThreeAnalyses(@TempDir Path root) throws Exception {
        Analysed analysed = analyse(root, "sources=source\nsanitizers=sanitize\nsinks=sink\n", """
                String raw = source();
                String clean = sanitize(raw);
                sink(clean);
                """);

        assertEquals(1L, analysed.graph().limitations().stream().filter(PROVENANCE::equals).count());
        assertTrue(analysed.impact().limitations().contains(PROVENANCE), analysed.impact().limitations().toString());
        assertTrue(analysed.security().limitations().contains(PROVENANCE), analysed.security().limitations().toString());
        assertFalse(analysed.graph().limitations().contains(NOT_CONFIGURED));
        assertEquals(1, analysed.security().observedPaths().size());
    }

    @Test
    void aHostileRepositoryThatOmitsItsSourcesGetsNoPathButTheDeclaration(@TempDir Path root) throws Exception {
        Analysed analysed = analyse(root, "sinks=sink\nsanitizers=sanitize\n", """
                String raw = source();
                sink(raw);
                """);

        assertTrue(analysed.security().observedPaths().isEmpty());
        assertTrue(analysed.security().limitations().contains(PROVENANCE));
        assertTrue(analysed.security().limitations().contains("SECURITY_SOURCE_OR_SINK_ANNOTATIONS_UNAVAILABLE"));
        assertTrue(analysed.security().limitations().contains("ABSENCE_OF_PATH_IS_NOT_PROOF_OF_SAFETY"));
    }

    @Test
    void anEmptyRulesFileIsStillDeclaredBecauseItIsTheRepositoryThatWroteIt(@TempDir Path root) throws Exception {
        Analysed analysed = analyse(root, "", "sink(source());\n");

        assertTrue(analysed.graph().limitations().contains(PROVENANCE));
        assertTrue(analysed.security().observedPaths().isEmpty());
    }

    @Test
    void aRepositoryWithoutRulesFileIsNotedAsUnconfiguredAndNotAsSupplier(@TempDir Path root) throws Exception {
        Analysed analysed = analyse(root, null, """
                String raw = source();
                sink(raw);
                """);

        for (List<String> limitations : List.of(analysed.graph().limitations(), analysed.impact().limitations(),
                analysed.security().limitations())) {
            assertFalse(limitations.contains(PROVENANCE), limitations.toString());
        }
        assertTrue(analysed.graph().limitations().contains(NOT_CONFIGURED));
    }

    @Test
    void aSanitizerOnThePathMarksItAndNeverRemovesIt(@TempDir Path root) throws Exception {
        Analysed analysed = analyse(root, "sources=source\nsanitizers=sanitize\nsinks=sink\n", """
                String raw = source();
                String clean = sanitize(raw);
                sink(clean);
                """);

        assertEquals(1, analysed.security().observedPaths().size());
        SecurityAnalysisService.SecurityPath path = analysed.security().observedPaths().getFirst();
        assertTrue(path.sanitizedPathObserved());
        assertFalse(path.sanitizerNodeIds().isEmpty());
    }

    @Test
    void aDeclaredSanitizerOffThePathLeavesTheFlagFalse(@TempDir Path root) throws Exception {
        Analysed analysed = analyse(root, "sources=source\nsanitizers=sanitize\nsinks=sink\n", """
                String raw = source();
                String unused = sanitize("constant");
                sink(raw);
                """);

        assertEquals(1, analysed.security().observedPaths().size());
        SecurityAnalysisService.SecurityPath path = analysed.security().observedPaths().getFirst();
        assertFalse(path.sanitizedPathObserved());
        assertTrue(path.sanitizerNodeIds().isEmpty());
    }

    private static Analysed analyse(Path root, String rules, String runBody) throws IOException {
        ProgramGraphSourceFixture fixture = new ProgramGraphSourceFixture(root, 1);
        Files.writeString(fixture.projectRoot.resolve(fixture.fileIds.getFirst()), "package demo;\nfinal class Generated0 {\n"
                + HELPERS + "    static void run() {\n" + runBody.indent(8) + "    }\n}\n", StandardCharsets.UTF_8);
        if (rules != null) {
            Path rulesFile = fixture.projectRoot.resolve(RULES);
            Files.createDirectories(rulesFile.getParent());
            Files.writeString(rulesFile, rules, StandardCharsets.UTF_8);
        }
        ProgramGraphService service = fixture.productionService();
        String project = fixture.project.id().toString();
        Symbol symbol = fixture.snapshots.loadActiveKnowledge(fixture.project.id()).orElseThrow().symbols().getFirst();
        ProgramGraph graph = service.getGraph(project);
        AdvancedImpactService.AdvancedImpactReport impact = new AdvancedImpactService(
                new LocalProjectImpactQuery(fixture.registry, fixture.snapshots), service)
                .analyze(project, ImpactAnalysisRequest.defaults(symbol.id()));
        SecurityAnalysisService.SecurityReport security = new SecurityAnalysisService(service)
                .analyze(project, new SecurityAnalysisService.SecurityRequest(null, 8, 10));
        return new Analysed(graph, impact, security);
    }

    private record Analysed(
            ProgramGraph graph,
            AdvancedImpactService.AdvancedImpactReport impact,
            SecurityAnalysisService.SecurityReport security
    ) {
    }
}

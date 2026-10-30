package com.minos.program.analysis;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ImpactAnalysisService;
import com.minos.impact.ImpactReferenceFixture;
import com.minos.impact.ProjectImpactQuery;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AUD-PERF-11 : les rapports d'impact avancé restent identiques, ordre compris, à la référence figée avant que
 * l'adjacence du graphe de programme ne soit portée par l'entrée de cache du graphe.
 *
 * <p>{@code -Dminos.advancedImpactReference.write=<fichier>} écrit le rendu courant au lieu de le comparer.</p>
 */
class AdvancedImpactReferenceReportsTest {

    static final String REFERENCE = "/impact/advanced-reference-reports.txt";
    private static final String WRITE_PROPERTY = "minos.advancedImpactReference.write";
    private static final long SEED = 20_261_015L;
    private static final int VARIABLES = 30;
    private static final int EDGES = 260;
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    private static final double[] CONFIDENCES = {0.5, 0.7, 0.9, 1.0};

    @Test
    void advancedImpactRendersTheReference(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        StringBuilder rendered = new StringBuilder();
        for (ImpactAnalysisRequest request : requests()) {
            rendered.append(digestLine(fixture.service().analyze(fixture.projectId(), request)));
        }
        String target = System.getProperty(WRITE_PROPERTY);
        if (target != null && !target.isBlank()) {
            Files.writeString(Path.of(target), rendered, StandardCharsets.UTF_8);
            return;
        }
        assertEquals(ImpactReferenceFixture.reference(REFERENCE), rendered.toString());
    }

    static List<ImpactAnalysisRequest> requests() {
        List<ImpactAnalysisRequest> requests = new ArrayList<>();
        for (int index = 0; index < ImpactReferenceFixture.LOCAL_SYMBOLS; index++) {
            requests.add(new ImpactAnalysisRequest(ImpactReferenceFixture.symbolId(index), 2, 10));
            requests.add(new ImpactAnalysisRequest(ImpactReferenceFixture.symbolId(index), 6, 200));
        }
        return List.copyOf(requests);
    }

    static String digestLine(AdvancedImpactService.AdvancedImpactReport report) {
        StringBuilder text = new StringBuilder();
        text.append("baseline=").append(ImpactReferenceFixture.render(report.baseline()));
        text.append("limitations=").append(report.limitations()).append('\n');
        for (AdvancedImpactService.AdvancedImpactItem item : report.advancedAdded()) {
            text.append("A ").append(item.symbolId()).append(' ').append(item.label()).append(" d=").append(item.depth())
                    .append(" c=").append(item.confidence()).append(' ').append(item.nature())
                    .append(" path=").append(String.join(",", item.programEdgePath())).append('\n');
        }
        return report.baseline().request().symbolId() + ' ' + report.baseline().request().maxDepth() + ' '
                + report.baseline().request().maxResults() + " added=" + report.advancedAddedCount() + ' '
                + ImpactReferenceFixture.sha256(text.toString()) + '\n';
    }

    /** Graphe de programme pseudo-aléatoire à graine fixe sur les symboles de {@link ImpactReferenceFixture}. */
    static ProgramGraph graph(String projectId, String snapshotId) {
        Random random = new Random(SEED);
        List<ProgramGraphNode> nodes = new ArrayList<>();
        for (int index = 0; index < ImpactReferenceFixture.SYMBOLS; index++) {
            String symbolId = ImpactReferenceFixture.symbolId(index);
            nodes.add(new ProgramGraphNode("symbol:" + symbolId, projectId, symbolId, ProgramNodeKind.SYMBOL,
                    "label-" + symbolId, null, InformationNature.FACTUAL, null, ORIGIN, List.of()));
        }
        for (int index = 0; index < VARIABLES; index++) {
            nodes.add(new ProgramGraphNode(String.format(Locale.ROOT, "var:%02d", index), projectId, null,
                    ProgramNodeKind.VARIABLE, "variable-" + index, null, InformationNature.FACTUAL, null, ORIGIN,
                    List.of()));
        }
        ProgramEdgeKind[] kinds = ProgramEdgeKind.values();
        List<ProgramGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < EDGES; index++) {
            String id = String.format(Locale.ROOT, "e%03d", (index * 7919) % 1000);
            String source = nodes.get(random.nextInt(nodes.size())).id();
            String target = nodes.get(random.nextInt(nodes.size())).id();
            ProgramEdgeKind kind = random.nextInt(3) == 0 ? ProgramEdgeKind.CALL : kinds[random.nextInt(kinds.length)];
            int natureChoice = random.nextInt(6);
            double confidence = CONFIDENCES[random.nextInt(CONFIDENCES.length)];
            if (natureChoice < 3) {
                edges.add(new ProgramGraphEdge(id, projectId, source, target, kind, InformationNature.FACTUAL,
                        random.nextBoolean() ? null : confidence, ORIGIN, List.of()));
            } else {
                InformationNature nature = natureChoice < 5 ? InformationNature.DERIVED : InformationNature.HEURISTIC;
                edges.add(new ProgramGraphEdge(id, projectId, source, target, kind, nature, confidence, ORIGIN,
                        List.of(new Evidence(EvidenceType.DERIVATION_PATH, "edge " + id, null, null, null,
                                confidence))));
            }
        }
        Collections.shuffle(edges, random);
        return new ProgramGraph(projectId, snapshotId, Set.of(ProgramGraphCapability.CALL_GRAPH), nodes, edges,
                List.of());
    }

    /** Service de graphe réel, fournisseur synthétique, impact de base calculé sur le snapshot de référence. */
    static final class Fixture {
        private final ProgramGraphSourceFixture.Registry registry = new ProgramGraphSourceFixture.Registry();
        private final ProgramGraphSourceFixture.Snapshots snapshots = new ProgramGraphSourceFixture.Snapshots();
        private final RegisteredProject project;
        private final ProgramGraphService graphs;
        private final AdvancedImpactService service;

        Fixture(Path root) throws Exception {
            project = registry.registerProject(Files.createDirectories(root.resolve("project")), "advanced-reference");
            CodeKnowledgeSnapshot reference = ImpactReferenceFixture.snapshot();
            snapshots.publish(project.id(), ImpactReferenceFixture.SNAPSHOT_ID, List.of());
            graphs = new ProgramGraphService(registry, snapshots, List.of(new SyntheticProvider()));
            ImpactAnalysisService impacts = new ImpactAnalysisService();
            ProjectImpactQuery baseline = (ignored, request) -> impacts.analyze(reference, request);
            service = new AdvancedImpactService(baseline, graphs);
        }

        String projectId() {
            return project.id().toString();
        }

        AdvancedImpactService service() {
            return service;
        }

        ProgramGraphService graphs() {
            return graphs;
        }
    }

    private static final class SyntheticProvider implements ProgramGraphProvider {
        @Override
        public String id() {
            return "synthetic-reference";
        }

        @Override
        public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
            return graph(project.id().toString(), snapshot.snapshotId());
        }
    }
}

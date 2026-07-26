package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitectureConcentrationServiceTest {

    private final ArchitectureConcentrationService service = new ArchitectureConcentrationService();

    @Test
    void computesDirectedConcentrationWithoutAssigningCentralityLabels() {
        ArchitectureOverview overview = overview(
                "snapshot-1",
                module("module:c", "c"),
                module("module:a", "a"),
                module("module:b", "b")
        );
        ArchitectureDependencyGraph graph = graph(
                "snapshot-1",
                dependency("edge:b-a", "module:b", "module:a", 2),
                dependency("edge:c-b", "module:c", "module:b", 1),
                dependency("edge:a-b", "module:a", "module:b", 3)
        );

        ArchitectureConcentrationReport report = service.analyze(overview, graph);

        assertEquals(List.of("module:a", "module:b", "module:c"), report.modules().stream()
                .map(ArchitectureModuleConcentration::moduleId)
                .toList());
        assertEquals(3, report.moduleCount());
        assertEquals(6, report.interModuleDependencyCount());
        assertEquals(5.0 / 9.0, report.incomingHerfindahlIndex(), 1.0e-12);
        assertEquals(7.0 / 18.0, report.outgoingHerfindahlIndex(), 1.0e-12);
        assertEquals(2.0 / 3.0, report.maxIncomingShare(), 1.0e-12);
        assertEquals(0.5, report.maxOutgoingShare(), 1.0e-12);

        ArchitectureModuleConcentration a = metric(report, "module:a");
        assertEquals(2, a.incomingDependencyCount());
        assertEquals(3, a.outgoingDependencyCount());
        assertEquals(1, a.incomingModuleCount());
        assertEquals(1, a.outgoingModuleCount());
        assertEquals(1.0 / 3.0, a.incomingShare(), 1.0e-12);
        assertEquals(0.5, a.outgoingShare(), 1.0e-12);

        ArchitectureModuleConcentration b = metric(report, "module:b");
        assertEquals(4, b.incomingDependencyCount());
        assertEquals(2, b.outgoingDependencyCount());
        assertEquals(2, b.incomingModuleCount());
        assertEquals(1, b.outgoingModuleCount());

        ArchitectureModuleConcentration c = metric(report, "module:c");
        assertEquals(0, c.incomingDependencyCount());
        assertEquals(1, c.outgoingDependencyCount());
        assertEquals(0.0, c.incomingShare(), 0.0);
        assertEquals(1.0 / 6.0, c.outgoingShare(), 1.0e-12);
    }

    @Test
    void returnsZeroConcentrationWhenThereAreNoInterModuleDependencies() {
        ArchitectureOverview overview = overview(
                "snapshot-empty",
                module("module:a", "a"),
                module("module:b", "b")
        );
        ArchitectureDependencyGraph graph = new ArchitectureDependencyGraph(
                "project-1",
                "snapshot-empty",
                3,
                0,
                2,
                1,
                List.of(),
                InformationNature.DERIVED,
                List.of(evidence("empty inter-module graph"))
        );

        ArchitectureConcentrationReport report = service.analyze(overview, graph);

        assertEquals(0, report.interModuleDependencyCount());
        assertEquals(0.0, report.incomingHerfindahlIndex(), 0.0);
        assertEquals(0.0, report.outgoingHerfindahlIndex(), 0.0);
        assertEquals(0.0, report.maxIncomingShare(), 0.0);
        assertEquals(0.0, report.maxOutgoingShare(), 0.0);
        report.modules().forEach(metric -> {
            assertEquals(0, metric.incomingDependencyCount());
            assertEquals(0, metric.outgoingDependencyCount());
            assertEquals(0.0, metric.incomingShare(), 0.0);
            assertEquals(0.0, metric.outgoingShare(), 0.0);
        });
    }

    @Test
    void rejectsMismatchedOrInconsistentGraphs() {
        ArchitectureOverview overview = overview(
                "snapshot-1",
                module("module:a", "a"),
                module("module:b", "b")
        );
        ArchitectureDependencyGraph otherSnapshot = new ArchitectureDependencyGraph(
                "project-1",
                "snapshot-2",
                0,
                0,
                0,
                0,
                List.of(),
                InformationNature.DERIVED,
                List.of(evidence("other snapshot"))
        );
        ArchitectureDependencyGraph inconsistent = new ArchitectureDependencyGraph(
                "project-1",
                "snapshot-1",
                2,
                2,
                0,
                0,
                List.of(dependency("edge:a-b", "module:a", "module:b", 1)),
                InformationNature.DERIVED,
                List.of(evidence("inconsistent edge count"))
        );

        assertThrows(IllegalArgumentException.class, () -> service.analyze(overview, otherSnapshot));
        assertThrows(IllegalArgumentException.class, () -> service.analyze(overview, inconsistent));
    }

    private static ArchitectureOverview overview(String snapshotId, ArchitectureModule... modules) {
        return new ArchitectureOverview(
                "project-1",
                "project",
                snapshotId,
                List.of("JAVA"),
                List.of("MAVEN"),
                0,
                0,
                0,
                0,
                List.of(modules),
                InformationNature.DERIVED,
                List.of(evidence("overview"))
        );
    }

    private static ArchitectureModule module(String id, String name) {
        return new ArchitectureModule(
                id,
                name,
                name,
                List.of("MAVEN"),
                List.of("JAVA"),
                1,
                0,
                List.of(),
                InformationNature.FACTUAL,
                InformationNature.DERIVED,
                List.of(evidence("module " + name))
        );
    }

    private static ArchitectureDependencyGraph graph(
            String snapshotId,
            ArchitectureModuleDependency... dependencies
    ) {
        int interModuleCount = List.of(dependencies).stream()
                .mapToInt(ArchitectureModuleDependency::dependencyCount)
                .sum();
        return new ArchitectureDependencyGraph(
                "project-1",
                snapshotId,
                interModuleCount,
                interModuleCount,
                0,
                0,
                List.of(dependencies),
                InformationNature.DERIVED,
                List.of(evidence("graph"))
        );
    }

    private static ArchitectureModuleDependency dependency(
            String id,
            String source,
            String target,
            int count
    ) {
        return new ArchitectureModuleDependency(
                id,
                source,
                target,
                count,
                1,
                1,
                List.of("rel:" + id),
                InformationNature.DERIVED,
                1.0,
                List.of(evidence("dependency " + id))
        );
    }

    private static ArchitectureModuleConcentration metric(
            ArchitectureConcentrationReport report,
            String moduleId
    ) {
        return report.modules().stream()
                .filter(metric -> moduleId.equals(metric.moduleId()))
                .findFirst()
                .orElseThrow();
    }

    private static Evidence evidence(String description) {
        return new Evidence(EvidenceType.DERIVATION_PATH, description, null, null, null, 1.0);
    }
}

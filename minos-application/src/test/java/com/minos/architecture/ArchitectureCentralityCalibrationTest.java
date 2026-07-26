package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectureCentralityCalibrationTest {

    private final ArchitectureConcentrationService service = new ArchitectureConcentrationService();

    @Test
    void distinguishesBalancedCycleFromDirectedChainAndStars() {
        CalibrationResult balanced = analyze(
                "balanced-cycle",
                dependency("a-b", "module:a", "module:b", 1),
                dependency("b-c", "module:b", "module:c", 1),
                dependency("c-d", "module:c", "module:d", 1),
                dependency("d-a", "module:d", "module:a", 1)
        );
        CalibrationResult chain = analyze(
                "directed-chain",
                dependency("a-b", "module:a", "module:b", 1),
                dependency("b-c", "module:b", "module:c", 1),
                dependency("c-d", "module:c", "module:d", 1)
        );
        CalibrationResult fanIn = analyze(
                "fan-in",
                dependency("a-d", "module:a", "module:d", 1),
                dependency("b-d", "module:b", "module:d", 1),
                dependency("c-d", "module:c", "module:d", 1)
        );
        CalibrationResult fanOut = analyze(
                "fan-out",
                dependency("d-a", "module:d", "module:a", 1),
                dependency("d-b", "module:d", "module:b", 1),
                dependency("d-c", "module:d", "module:c", 1)
        );

        assertProfile(balanced.report(), 0.25, 0.25, 0.25, 0.25);
        assertProfile(chain.report(), 1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0);
        assertProfile(fanIn.report(), 1.0, 1.0 / 3.0, 1.0, 1.0 / 3.0);
        assertProfile(fanOut.report(), 1.0 / 3.0, 1.0, 1.0 / 3.0, 1.0);

        ArchitectureModuleConcentration fanInHub = metric(fanIn.report(), "module:d");
        assertEquals(3, fanInHub.incomingDependencyCount());
        assertEquals(3, fanInHub.incomingModuleCount());
        assertEquals(0, fanInHub.outgoingDependencyCount());

        ArchitectureModuleConcentration fanOutHub = metric(fanOut.report(), "module:d");
        assertEquals(3, fanOutHub.outgoingDependencyCount());
        assertEquals(3, fanOutHub.outgoingModuleCount());
        assertEquals(0, fanOutHub.incomingDependencyCount());

        print(balanced);
        print(chain);
        print(fanIn);
        print(fanOut);
    }

    @Test
    void keepsWeightsVisibleWhenOneEdgeDominates() {
        CalibrationResult weighted = analyze(
                "weighted-fan-in",
                dependency("a-d", "module:a", "module:d", 8),
                dependency("b-d", "module:b", "module:d", 1),
                dependency("c-d", "module:c", "module:d", 1)
        );

        ArchitectureConcentrationReport report = weighted.report();
        assertEquals(10, report.interModuleDependencyCount());
        assertEquals(1.0, report.incomingHerfindahlIndex(), 1.0e-12);
        assertEquals(0.66, report.outgoingHerfindahlIndex(), 1.0e-12);
        assertEquals(1.0, report.maxIncomingShare(), 1.0e-12);
        assertEquals(0.8, report.maxOutgoingShare(), 1.0e-12);
        assertEquals(8, metric(report, "module:a").outgoingDependencyCount());
        assertEquals(0.8, metric(report, "module:a").outgoingShare(), 1.0e-12);

        print(weighted);
    }

    private CalibrationResult analyze(String name, ArchitectureModuleDependency... dependencies) {
        ArchitectureOverview overview = overview("snapshot-" + name);
        ArchitectureDependencyGraph graph = graph("snapshot-" + name, dependencies);
        return new CalibrationResult(name, service.analyze(overview, graph));
    }

    private static void assertProfile(
            ArchitectureConcentrationReport report,
            double expectedIncomingHhi,
            double expectedOutgoingHhi,
            double expectedMaxIncoming,
            double expectedMaxOutgoing
    ) {
        assertEquals(expectedIncomingHhi, report.incomingHerfindahlIndex(), 1.0e-12);
        assertEquals(expectedOutgoingHhi, report.outgoingHerfindahlIndex(), 1.0e-12);
        assertEquals(expectedMaxIncoming, report.maxIncomingShare(), 1.0e-12);
        assertEquals(expectedMaxOutgoing, report.maxOutgoingShare(), 1.0e-12);
    }

    private static void print(CalibrationResult result) {
        ArchitectureConcentrationReport report = result.report();
        System.out.printf(
                "M6.4 calibration %s: dependencies=%d, HHI-in=%.6f, HHI-out=%.6f, max-in=%.6f, max-out=%.6f%n",
                result.name(),
                report.interModuleDependencyCount(),
                report.incomingHerfindahlIndex(),
                report.outgoingHerfindahlIndex(),
                report.maxIncomingShare(),
                report.maxOutgoingShare()
        );
    }

    private static ArchitectureOverview overview(String snapshotId) {
        return new ArchitectureOverview(
                "project-calibration",
                "calibration",
                snapshotId,
                List.of("JAVA"),
                List.of("MAVEN"),
                0,
                0,
                0,
                0,
                List.of(
                        module("module:a", "a"),
                        module("module:b", "b"),
                        module("module:c", "c"),
                        module("module:d", "d")
                ),
                InformationNature.DERIVED,
                List.of(evidence("calibration overview"))
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
        int count = List.of(dependencies).stream()
                .mapToInt(ArchitectureModuleDependency::dependencyCount)
                .sum();
        return new ArchitectureDependencyGraph(
                "project-calibration",
                snapshotId,
                count,
                count,
                0,
                0,
                List.of(dependencies),
                InformationNature.DERIVED,
                List.of(evidence("calibration graph"))
        );
    }

    private static ArchitectureModuleDependency dependency(
            String id,
            String source,
            String target,
            int count
    ) {
        return new ArchitectureModuleDependency(
                "edge:" + id,
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

    private record CalibrationResult(String name, ArchitectureConcentrationReport report) {
    }
}

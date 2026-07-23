package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectureCentralityServiceTest {

    private final ArchitectureCentralityService service = new ArchitectureCentralityService();

    @Test
    void ranksFanInAndFanOutRelativelyWithoutCompositeScore() {
        ArchitectureCentralityReport fanIn = service.rank(report(
                metric("module:d", 3, 0, 3, 0, 1.0, 0.0),
                metric("module:b", 0, 1, 0, 1, 0.0, 1.0 / 3.0),
                metric("module:a", 0, 1, 0, 1, 0.0, 1.0 / 3.0),
                metric("module:c", 0, 1, 0, 1, 0.0, 1.0 / 3.0)
        ));

        assertEquals(List.of("module:a", "module:b", "module:c", "module:d"), fanIn.modules().stream()
                .map(ArchitectureModuleCentrality::moduleId)
                .toList());
        assertEquals(List.of("module:d"), fanIn.topIncomingModuleIds());
        assertEquals(List.of("module:a", "module:b", "module:c"), fanIn.topOutgoingModuleIds());
        assertEquals(1, module(fanIn, "module:d").incomingRank());
        assertEquals(0, module(fanIn, "module:d").outgoingRank());
        assertEquals(0, module(fanIn, "module:a").incomingRank());
        assertEquals(1, module(fanIn, "module:a").outgoingRank());
        assertEquals(1, fanIn.rankedIncomingModuleCount());
        assertEquals(3, fanIn.rankedOutgoingModuleCount());
    }

    @Test
    void assignsDenseRanksAndKeepsDependencyWeightsVisible() {
        ArchitectureCentralityReport weighted = service.rank(report(
                metric("module:a", 0, 8, 0, 1, 0.0, 0.8),
                metric("module:b", 0, 1, 0, 1, 0.0, 0.1),
                metric("module:c", 0, 1, 0, 1, 0.0, 0.1),
                metric("module:d", 10, 0, 3, 0, 1.0, 0.0)
        ));

        assertEquals(List.of("module:d"), weighted.topIncomingModuleIds());
        assertEquals(List.of("module:a"), weighted.topOutgoingModuleIds());
        assertEquals(1, module(weighted, "module:a").outgoingRank());
        assertEquals(2, module(weighted, "module:b").outgoingRank());
        assertEquals(2, module(weighted, "module:c").outgoingRank());
        assertEquals(1, module(weighted, "module:d").incomingRank());
        assertEquals(0, module(weighted, "module:d").outgoingRank());
    }

    @Test
    void leavesModulesUnrankedWhenThereIsNoDirectionalSignal() {
        ArchitectureCentralityReport empty = service.rank(report(
                metric("module:b", 0, 0, 0, 0, 0.0, 0.0),
                metric("module:a", 0, 0, 0, 0, 0.0, 0.0)
        ));

        assertEquals(List.of("module:a", "module:b"), empty.modules().stream()
                .map(ArchitectureModuleCentrality::moduleId)
                .toList());
        assertEquals(0, empty.rankedIncomingModuleCount());
        assertEquals(0, empty.rankedOutgoingModuleCount());
        assertEquals(List.of(), empty.topIncomingModuleIds());
        assertEquals(List.of(), empty.topOutgoingModuleIds());
        empty.modules().forEach(module -> {
            assertEquals(0, module.incomingRank());
            assertEquals(0, module.outgoingRank());
        });
    }

    private static ArchitectureConcentrationReport report(ArchitectureModuleConcentration... modules) {
        return new ArchitectureConcentrationReport(
                "project-centrality",
                "snapshot-centrality",
                modules.length,
                10,
                0.0,
                0.0,
                maxIncoming(modules),
                maxOutgoing(modules),
                List.of(modules),
                InformationNature.DERIVED,
                List.of(evidence("centrality source report"))
        );
    }

    private static double maxIncoming(ArchitectureModuleConcentration[] modules) {
        return List.of(modules).stream()
                .mapToDouble(ArchitectureModuleConcentration::incomingShare)
                .max()
                .orElse(0.0);
    }

    private static double maxOutgoing(ArchitectureModuleConcentration[] modules) {
        return List.of(modules).stream()
                .mapToDouble(ArchitectureModuleConcentration::outgoingShare)
                .max()
                .orElse(0.0);
    }

    private static ArchitectureModuleConcentration metric(
            String moduleId,
            int incomingDependencies,
            int outgoingDependencies,
            int incomingModules,
            int outgoingModules,
            double incomingShare,
            double outgoingShare
    ) {
        return new ArchitectureModuleConcentration(
                moduleId,
                incomingDependencies,
                outgoingDependencies,
                incomingModules,
                outgoingModules,
                incomingShare,
                outgoingShare,
                InformationNature.DERIVED,
                List.of(evidence("metric " + moduleId))
        );
    }

    private static ArchitectureModuleCentrality module(ArchitectureCentralityReport report, String moduleId) {
        return report.modules().stream()
                .filter(module -> moduleId.equals(module.moduleId()))
                .findFirst()
                .orElseThrow();
    }

    private static Evidence evidence(String description) {
        return new Evidence(EvidenceType.DERIVATION_PATH, description, null, null, null, 1.0);
    }
}

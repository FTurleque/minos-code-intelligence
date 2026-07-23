package com.minos.impact;

import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.domain.Symbol;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImpactAnalysisRealFixtureTest {

    @Test
    void replaysVersionedTypeScriptMultiModuleImpactGraph(@TempDir Path root) throws Exception {
        Path fixtureRoot = Path.of("fixtures", "typescript", "typescript-modules");
        Path indexFile = fixtureRoot.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));

        new ScipSymbolSnapshotImporter().importSnapshot(
                indexFile,
                new ScipSymbolSnapshotRequest(
                        projectId,
                        "snapshot-m8-real-typescript-modules",
                        null,
                        "scip-typescript",
                        "0.4.0",
                        "m8-real-fixture",
                        Map.of()
                ),
                snapshots
        );

        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(projectId).orElseThrow();
        Symbol greetingPortMethod = snapshot.symbols().stream()
                .filter(symbol -> "GreetingPort.greet".equals(symbol.qualifiedName()))
                .findFirst()
                .orElseThrow();

        ImpactAnalysisReport report = new ImpactAnalysisService().analyze(
                snapshot,
                new ImpactAnalysisRequest(greetingPortMethod.id(), 4, 200)
        );

        assertTrue(report.impacts().stream().anyMatch(impact ->
                "GreetingService.greet".equals(impact.symbol().qualifiedName())
                        && impact.level() == ImpactLevel.DIRECT));
        assertFalse(report.potentiallyImpactedTests().isEmpty());
        assertTrue(report.potentiallyImpactedTests().stream().allMatch(impact ->
                impact.testImpact() && !impact.path().isEmpty()));

        System.out.printf(
                "M8 typescript-modules impact: root=%s, impacts=%d, tests=%d, max-depth=%d, limitations=%s%n",
                greetingPortMethod.qualifiedName(),
                report.impacts().size(),
                report.potentiallyImpactedTests().size(),
                report.impacts().stream().mapToInt(ImpactedSymbol::depth).max().orElse(0),
                report.limitations()
        );
    }
}

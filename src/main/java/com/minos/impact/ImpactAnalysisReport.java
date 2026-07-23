package com.minos.impact;

import com.minos.domain.Symbol;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Rapport déterministe d'impact potentiel sur un snapshot de connaissance.
 */
public record ImpactAnalysisReport(
        UUID projectId,
        String snapshotId,
        Symbol rootSymbol,
        ImpactAnalysisRequest request,
        List<ImpactedSymbol> impacts,
        List<ImpactedSymbol> potentiallyImpactedTests,
        List<ImpactLimitation> limitations
) {
    public ImpactAnalysisReport {
        Objects.requireNonNull(projectId, "projectId");
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        Objects.requireNonNull(rootSymbol, "rootSymbol");
        Objects.requireNonNull(request, "request");
        impacts = List.copyOf(Objects.requireNonNull(impacts, "impacts"));
        potentiallyImpactedTests = List.copyOf(Objects.requireNonNull(
                potentiallyImpactedTests, "potentiallyImpactedTests"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (!rootSymbol.projectId().equals(projectId.toString())) {
            throw new IllegalArgumentException("root symbol must belong to report project");
        }
        if (!request.symbolId().equals(rootSymbol.id())) {
            throw new IllegalArgumentException("request symbol must match root symbol");
        }
        for (ImpactedSymbol test : potentiallyImpactedTests) {
            if (!test.testImpact() || !impacts.contains(test)) {
                throw new IllegalArgumentException("potentially impacted tests must be test impacts from impacts list");
            }
        }
    }
}

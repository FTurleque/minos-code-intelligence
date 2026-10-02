package com.minos.incremental;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexingRun;

import java.util.Objects;
import java.util.Optional;

/**
 * Résultat observable d'un refresh M7.
 */
public record IncrementalIndexingResult(
        IndexerNegotiationResult negotiation,
        IncrementalIndexingPlan plan,
        Optional<IndexingRun> run,
        boolean workspaceStableDuringRun,
        boolean fingerprintBaselineAdvanced,
        Optional<String> diagnostic
) {
    private static final String REDACTED_DIAGNOSTIC = "internal diagnostic redacted";

    public IncrementalIndexingResult {
        Objects.requireNonNull(negotiation, "negotiation");
        Objects.requireNonNull(plan, "plan");
        run = Objects.requireNonNull(run, "run");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic").map(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException("diagnostic must not contain blank text");
            }
            return PublicErrorMessages.sanitize(text, REDACTED_DIAGNOSTIC);
        });

        if (plan.mode() == com.minos.orchestration.IndexingMode.NONE && run.isPresent()) {
            throw new IllegalArgumentException("NONE plan must not create a run");
        }
        if (fingerprintBaselineAdvanced) {
            if (run.isEmpty() || run.orElseThrow().status() != IndexingRun.Status.SUCCEEDED) {
                throw new IllegalArgumentException("advanced fingerprint baseline requires a successful run");
            }
            if (!workspaceStableDuringRun) {
                throw new IllegalArgumentException("advanced fingerprint baseline requires a stable workspace");
            }
        }
    }
}

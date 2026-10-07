package com.minos.cli;

import com.minos.orchestration.ResumableRunSummary;

import java.util.Optional;

/** Fournit à `minos index-status` le run qu'un projet offre à la reprise (ADR 0039 §6). */
@FunctionalInterface
public interface IndexResumeStatusSource {

    /** Résumé du run reprenable, ou vide ; {@code projectId} est l'identifiant rendu par la vue projet. */
    Optional<ResumableRunSummary> resumableRun(String projectId);
}

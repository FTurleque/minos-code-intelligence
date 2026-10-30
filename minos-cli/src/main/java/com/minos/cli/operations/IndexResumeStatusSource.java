package com.minos.cli.operations;

import com.minos.application.ResumableRunStatus;

import java.util.Optional;

/** Fournit à `minos index-status` le run qu'un projet offre à la reprise (ADR 0039 §6). */
@FunctionalInterface
public interface IndexResumeStatusSource {

    /** Résumé du run reprenable, ou vide ; {@code projectId} est l'identifiant rendu par la vue projet. */
    Optional<ResumableRunStatus> resumableRun(String projectId);
}

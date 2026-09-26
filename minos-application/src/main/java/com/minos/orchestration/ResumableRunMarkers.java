package com.minos.orchestration;

import java.io.IOException;
import java.util.UUID;

/**
 * Port du marqueur « run reprenable » (ADR 0039 §5).
 *
 * <p>L'orchestrateur ne connaît pas la structure de {@code MINOS_HOME/runs/} ; l'adaptateur runtime
 * pose un marqueur {@code runs/<runId>/.resumable} quand un run devient {@code INTERRUPTED} et le
 * retire quand le run devient terminal. La reprise ne dépend pas du marqueur pour sa correction : il
 * sert uniquement à la rétention des répertoires de run, qui vit hors de l'application.</p>
 */
public interface ResumableRunMarkers {

    /** Marque le run comme reprenable ; idempotent. Une erreur interdit d'offrir la reprise. */
    void mark(UUID runId) throws IOException;

    /** Retire le marqueur ; l'absence de marqueur ou du répertoire de run n'est pas une erreur. */
    void unmark(UUID runId) throws IOException;

    /** Adaptateur inerte : aucun marqueur n'est jamais posé (stores sans répertoire de run). */
    static ResumableRunMarkers none() {
        return new ResumableRunMarkers() {
            @Override public void mark(UUID runId) { }
            @Override public void unmark(UUID runId) { }
        };
    }
}

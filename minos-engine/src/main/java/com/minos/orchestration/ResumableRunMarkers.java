package com.minos.orchestration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port du marqueur « répertoire de run retenu » (ADR 0039 §5, R5).
 *
 * <p>L'orchestrateur ne connaît pas la structure de {@code MINOS_HOME/runs/} ; l'adaptateur runtime
 * pose un marqueur {@code runs/<runId>/.resumable} qui protège le répertoire de la rétention des runs
 * d'une autre indexation. Il est posé dès le début d'un run (avant son premier provider) et quand un
 * run devient {@code INTERRUPTED} ; il est retiré quand le run devient terminal, sauf s'il reste
 * offert à la reprise. La reprise ne dépend pas du marqueur pour sa correction : il sert uniquement à
 * la rétention des répertoires de run, qui vit hors de l'application et borne sa durée de vie.</p>
 */
public interface ResumableRunMarkers {

    /** Retient le répertoire du run ; idempotent. Une erreur interdit d'offrir la reprise. */
    void mark(UUID runId) throws IOException;

    /** Retire le marqueur ; l'absence de marqueur ou du répertoire de run n'est pas une erreur. */
    void unmark(UUID runId) throws IOException;

    /**
     * Répertoire {@code runs/<runId>} sous lequel les artefacts réutilisables doivent rester
     * canoniquement confinés (ADR 0039 §7). Vide quand le runtime n'a pas de répertoire de run :
     * aucune reprise n'est alors possible (fail-closed).
     */
    default Optional<Path> runDirectory(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        return Optional.empty();
    }

    /** Adaptateur inerte : aucun marqueur n'est jamais posé (stores sans répertoire de run). */
    static ResumableRunMarkers none() {
        return new ResumableRunMarkers() {
            @Override public void mark(UUID runId) { }
            @Override public void unmark(UUID runId) { }
        };
    }
}

package com.minos.orchestration;

import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Vue publique du run qu'un projet offre à la reprise (ADR 0039 §6) : identifiant, phase
 * interrompue, date du dernier point de contrôle et nombre de cibles réutilisables. Aucun chemin
 * n'est exposé.
 */
public record ResumableRunSummary(
        UUID runId,
        IndexingRun.Phase phase,
        Instant checkpointAt,
        int resumableTargets,
        int completedExecutions
) {
    private static final System.Logger LOGGER = System.getLogger(ResumableRunSummary.class.getName());

    public ResumableRunSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(checkpointAt, "checkpointAt");
        if (resumableTargets < 0 || completedExecutions < resumableTargets) {
            throw new IllegalArgumentException("resumableTargets must be within completedExecutions");
        }
    }

    /**
     * Résout la référence {@code resumableRunId} de l'état projet. Une référence incohérente (run
     * absent ou qui n'est pas {@code INTERRUPTED}, par exemple après un crash entre l'écriture du run
     * et celle de l'état projet) est ignorée avec un avertissement : rien n'est offert à la reprise.
     */
    public static Optional<ResumableRunSummary> of(IndexStateStore stateStore, UUID projectId) {
        Objects.requireNonNull(stateStore, "stateStore");
        Objects.requireNonNull(projectId, "projectId");
        Optional<UUID> reference = stateStore.findProjectState(projectId).flatMap(ProjectIndexState::resumableRunId);
        if (reference.isEmpty()) return Optional.empty();
        UUID runId = reference.orElseThrow();
        Optional<IndexingRun> run = stateStore.findRun(runId).filter(candidate -> candidate.projectId().equals(projectId));
        if (run.isEmpty() || run.orElseThrow().status() != IndexingRun.Status.INTERRUPTED) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS project state references a resumable run that is missing or not interrupted; ignoring it"
                            + " (projectId=" + projectId + ", runId=" + runId + ")");
            return Optional.empty();
        }
        return Optional.of(of(run.orElseThrow()));
    }

    static ResumableRunSummary of(IndexingRun run) {
        Objects.requireNonNull(run, "run");
        int resumable = 0;
        Instant latest = run.completedAt().orElse(run.createdAt());
        for (IndexerExecution execution : run.executions()) {
            Optional<ExecutionCheckpoint> checkpoint = execution.checkpoint();
            if (checkpoint.isEmpty()) continue;
            resumable++;
            Instant completedAt = checkpoint.orElseThrow().completedAt();
            if (resumable == 1 || completedAt.isAfter(latest)) latest = completedAt;
        }
        return new ResumableRunSummary(run.id(), run.phase(), latest, resumable, run.executions().size());
    }

    /** Âge du dernier point de contrôle, jamais négatif. */
    public long checkpointAgeSeconds(Instant now) {
        Duration age = Duration.between(checkpointAt, Objects.requireNonNull(now, "now"));
        return age.isNegative() ? 0L : age.toSeconds();
    }
}

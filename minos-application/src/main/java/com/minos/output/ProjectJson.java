package com.minos.output;

import com.minos.application.ProjectSummary;
import com.minos.orchestration.ResumableRunSummary;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.minos.output.DeterministicJson.object;

/**
 * Projections JSON d'un projet, partagées par la CLI ({@code project inspect}, {@code index-status}) et le MCP
 * ({@code minos_project_structure}, {@code minos_index_status}) : les clés et leur ordre se décident ici, une
 * seule fois (Q13). Les objets rendus sont ordonnés et modifiables : l'appelant peut y ajouter ses propres clés
 * en fin d'objet (le MCP ajoute {@code providerProfiles}).
 */
public final class ProjectJson {

    private ProjectJson() {
    }

    /** Fiche d'un projet : identité, langages, modules, état de l'index. */
    public static Map<String, Object> project(ProjectSummary project) {
        Objects.requireNonNull(project, "project");
        return object(
                "id", project.id(),
                "name", project.name(),
                "rootPath", project.rootPath(),
                "rootAvailable", project.rootAvailable(),
                "languages", project.languages(),
                "buildSystems", project.buildSystems(),
                "moduleCount", project.moduleCount(),
                "indexState", project.indexState(),
                "activeSnapshotId", project.activeSnapshotId(),
                "lastSuccessfulIndexAt", project.lastSuccessfulIndexAt(),
                "providerId", project.providerId(),
                "providerVersion", project.providerVersion());
    }

    /**
     * État de l'index d'un projet et run offert à la reprise (ADR 0039 §6, sans emplacement d'artefact) ; les
     * champs de reprise valent {@code null} quand rien n'est offert.
     */
    public static Map<String, Object> indexStatus(
            ProjectSummary project, Optional<ResumableRunSummary> resumable, Instant now) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(resumable, "resumable");
        Objects.requireNonNull(now, "now");
        return object(
                "projectId", project.id(),
                "projectName", project.name(),
                "state", project.indexState(),
                "activeSnapshotId", project.activeSnapshotId(),
                "lastSuccessfulIndexAt", project.lastSuccessfulIndexAt(),
                "providerId", project.providerId(),
                "providerVersion", project.providerVersion(),
                "resumableRunId", resumable.map(summary -> summary.runId().toString()).orElse(null),
                "resumableRunPhase", resumable.map(summary -> summary.phase().name()).orElse(null),
                "resumableCheckpointAgeSeconds",
                resumable.map(summary -> summary.checkpointAgeSeconds(now)).orElse(null),
                "resumableTargets", resumable.map(ResumableRunSummary::resumableTargets).orElse(null));
    }
}

package com.minos.application.indexing;

import com.minos.application.ProviderRuntimeSummary;

import java.util.List;
import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/**
 * What an indexing would do, as the surfaces show it (AUD-ARC-01): the project, its sorted languages and build
 * systems, the negotiated providers and their runtimes, the mode name ({@code NONE}, {@code FULL} or
 * {@code INCREMENTAL}), the sorted reasons and the changed files.
 */
public record IndexPlanSummary(
        String projectId,
        String projectName,
        String rootPath,
        List<String> languages,
        List<String> buildSystems,
        List<String> providerIds,
        List<ProviderRuntimeSummary> providerRuntimes,
        String mode,
        List<String> reasons,
        List<String> changedFiles,
        boolean forcedFull
) {
    public IndexPlanSummary {
        requireText(projectId, "projectId");
        requireText(projectName, "projectName");
        requireText(rootPath, "rootPath");
        languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
        buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
        providerIds = List.copyOf(Objects.requireNonNull(providerIds, "providerIds"));
        providerRuntimes = List.copyOf(Objects.requireNonNull(providerRuntimes, "providerRuntimes"));
        requireText(mode, "mode");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
    }

    static IndexPlanSummary of(ProjectIndexingUseCase.Preparation prepared) {
        var project = prepared.project();
        return new IndexPlanSummary(
                project.id().toString(), project.displayName(), project.rootPath().toString(),
                prepared.discovery().languages().stream().map(Enum::name).sorted().toList(),
                prepared.discovery().buildSystems().stream().map(Enum::name).sorted().toList(),
                prepared.providerIds(),
                prepared.providerRuntimes().stream().map(ProviderRuntimeSummary::of).toList(),
                prepared.effectiveMode().name(), prepared.reasons(), prepared.changedFiles(), prepared.forcedFull());
    }
}

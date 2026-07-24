package com.minos.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Additive M12 public contract for workspaces, cross-repository intelligence and Git activity.
 *
 * <p>This interface extends the stable M11 API without adding abstract methods to
 * {@link MinosApi}; existing M11 implementations therefore remain binary independent
 * from the M12 capability.</p>
 */
public interface MinosMultiRepositoryApi extends MinosApi {

    String MULTI_REPOSITORY_CONTRACT_VERSION = "1";

    default String multiRepositoryContractVersion() {
        return MULTI_REPOSITORY_CONTRACT_VERSION;
    }

    WorkspaceDto createWorkspace(String name) throws MinosApiException;

    List<WorkspaceDto> listWorkspaces() throws MinosApiException;

    WorkspaceDto getWorkspace(String workspaceIdentifier) throws MinosApiException;

    WorkspaceDto assignProjectToWorkspace(
            String projectIdentifier,
            String workspaceIdentifier
    ) throws MinosApiException;

    GitRepositoryDto inspectGit(String projectIdentifier) throws MinosApiException;

    GitActivityDto analyzeGitActivity(
            String projectIdentifier,
            GitActivityQuery query
    ) throws MinosApiException;

    WorkspaceIntelligenceDto analyzeWorkspace(
            String workspaceIdentifier,
            WorkspaceQuery query
    ) throws MinosApiException;

    record GitActivityQuery(Instant since, int maxCommits, int maxFiles, int zoneDepth) {
        public GitActivityQuery {
            Objects.requireNonNull(since, "since");
            requireRange(maxCommits, 1, 10_000, "maxCommits");
            requireRange(maxFiles, 1, 10_000, "maxFiles");
            requireRange(zoneDepth, 1, 8, "zoneDepth");
        }
    }

    record WorkspaceQuery(int maxRelationships) {
        public WorkspaceQuery {
            requireRange(maxRelationships, 1, 10_000, "maxRelationships");
        }

        public static WorkspaceQuery defaults() {
            return new WorkspaceQuery(1_000);
        }
    }

    record WorkspaceDto(
            String id,
            String name,
            List<String> projectIds,
            String createdAt,
            String updatedAt
    ) {
        public WorkspaceDto {
            projectIds = immutable(projectIds);
        }
    }

    record GitRepositoryDto(
            String repositoryId,
            String workTree,
            String originRemote,
            String branch,
            String headCommit,
            boolean detachedHead,
            boolean shallow,
            boolean clean,
            List<String> limitations
    ) {
        public GitRepositoryDto {
            limitations = immutable(limitations);
        }
    }

    record GitCommitDto(
            String commitId,
            String committedAt,
            String authorName,
            String authorEmail,
            String message,
            List<String> changedPaths
    ) {
        public GitCommitDto {
            changedPaths = immutable(changedPaths);
        }
    }

    record GitFileActivityDto(
            String path,
            int commitCount,
            int uniqueAuthorCount,
            String lastChangedAt,
            String lastCommitId
    ) {
    }

    record GitZoneActivityDto(
            String zone,
            int commitTouches,
            int distinctFileCount,
            String lastChangedAt
    ) {
    }

    record GitActivityDto(
            GitRepositoryDto repository,
            String since,
            int maxCommits,
            int maxFiles,
            int zoneDepth,
            int scannedCommitCount,
            boolean historyTruncated,
            boolean filesTruncated,
            List<GitCommitDto> recentCommits,
            List<GitFileActivityDto> files,
            List<GitZoneActivityDto> zones,
            List<String> limitations
    ) {
        public GitActivityDto {
            recentCommits = immutable(recentCommits);
            files = immutable(files);
            zones = immutable(zones);
            limitations = immutable(limitations);
        }
    }

    record WorkspaceProjectDto(
            String projectId,
            String projectName,
            String rootPath,
            boolean indexed,
            String snapshotId,
            int localSymbolCount,
            int unresolvedRelationshipCount
    ) {
    }

    record CrossRepositoryRelationshipDto(
            String sourceProjectId,
            String sourceRelationshipId,
            String sourceSymbolId,
            String targetProjectId,
            String targetSymbolId,
            String targetQualifiedName,
            String kind,
            String providerId,
            String providerExternalId,
            String resolutionBasis,
            double confidence
    ) {
    }

    record WorkspaceIntelligenceDto(
            WorkspaceDto workspace,
            List<WorkspaceProjectDto> projects,
            int exactResolutionCount,
            int ambiguousTargetCount,
            int unresolvedTargetCount,
            boolean relationshipsTruncated,
            List<CrossRepositoryRelationshipDto> crossRepositoryRelationships,
            List<String> limitations
    ) {
        public WorkspaceIntelligenceDto {
            projects = immutable(projects);
            crossRepositoryRelationships = immutable(crossRepositoryRelationships);
            limitations = immutable(limitations);
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

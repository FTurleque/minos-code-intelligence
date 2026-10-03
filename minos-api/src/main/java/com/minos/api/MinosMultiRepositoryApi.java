package com.minos.api;

import java.time.Instant;
import java.util.List;

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

    /**
     * Lists the workspaces. <b>Strict</b>: a single unreadable registry entry makes it fail, as it always did. Use
     * {@link #listWorkspaceInventory()} to list what can be read and be told what could not.
     */
    List<WorkspaceDto> listWorkspaces() throws MinosApiException;

    /**
     * Lists the workspaces that could be established, and counts the registry entries that could not be read.
     *
     * <p>{@link #listWorkspaces()} answers "which workspaces are there" and cannot say "and some entries were
     * unreadable": making it tolerant would hide the damage from a caller that never asked for it. This operation
     * returns {@link WorkspaceInventoryDto}, which carries the same {@link WorkspaceDto} list plus the two counts. A list
     * with a non-zero count is <em>valid for what was read and incomplete</em>: it is not a list of all the workspaces,
     * and it must not be read as one.</p>
     *
     * <p>A registry that cannot be listed at all (an unreadable directory, a storage outage) fails with {@link
     * ErrorCode#IO_FAILURE}: that is an outage, not a partial inventory. A registry in which <em>every</em> entry is
     * unreadable is listed: an empty list with the counts, never an empty list without them.</p>
     *
     * <p>The default keeps contract-v1 third-party implementations source- and binary-compatible. It answers {@link
     * ErrorCode#UNAVAILABLE} rather than fabricating a complete inventory.</p>
     */
    default WorkspaceInventoryDto listWorkspaceInventory() throws MinosApiException {
        throw new MinosApiException(
                ErrorCode.UNAVAILABLE,
                "workspace inventory is not available in this implementation");
    }

    /**
     * Resolves a workspace by identifier or by name. <b>Strict</b>: a single unreadable registry entry makes it fail,
     * as it always did. Use {@link #lookupWorkspace(String)} for the tolerant resolution.
     */
    WorkspaceDto getWorkspace(String workspaceIdentifier) throws MinosApiException;

    /**
     * Resolves a workspace by identifier or by name, tolerant in its answer and not in its verdict.
     *
     * <p>Found among the readable entries: the workspace, with {@link WorkspaceLookupDto} counting the unreadable
     * entries that qualify the answer. Not found: {@link ErrorCode#INVALID_REQUEST} ("Unknown workspace") when nothing
     * that could hold it was unreadable, and {@link ErrorCode#IO_FAILURE} with a message that says N registry entries
     * are unreadable and it cannot be told whether the workspace exists when something was. The two are different
     * answers: the second one does not say the workspace is absent. By identifier only the entry of that identifier
     * matters; by name, every unreadable workspace entry does.</p>
     *
     * <p>The default answers {@link ErrorCode#UNAVAILABLE}, like {@link #listWorkspaceInventory()}.</p>
     */
    default WorkspaceLookupDto lookupWorkspace(String workspaceIdentifier) throws MinosApiException {
        throw new MinosApiException(
                ErrorCode.UNAVAILABLE,
                "tolerant workspace lookup is not available in this implementation");
    }

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
            if (since == null) {
                throw new IllegalArgumentException("since must not be null");
            }
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

    /**
     * The workspaces that could be established, and what could not be read. {@code unreadableWorkspaceEntries} counts
     * workspace entries that may exist and are missing from {@code workspaces}; {@code unreadableProjectEntries} counts
     * project entries whose membership is not in the listed workspaces' {@code projectIds}. Both are 0 for a registry
     * that was read in full. They are counts, not messages: what the entries are is shown by {@code minos project list}.
     */
    record WorkspaceInventoryDto(
            List<WorkspaceDto> workspaces,
            int unreadableWorkspaceEntries,
            int unreadableProjectEntries
    ) {
        public WorkspaceInventoryDto {
            workspaces = immutable(workspaces);
            requireNonNegative(unreadableWorkspaceEntries, "unreadableWorkspaceEntries");
            requireNonNegative(unreadableProjectEntries, "unreadableProjectEntries");
        }
    }

    /**
     * One workspace and the unreadable entries that qualify it. {@code unreadableProjectEntries} &gt; 0 means the
     * {@code projectIds} of {@code workspace} are the membership that could be read, not necessarily all of it;
     * {@code unreadableWorkspaceEntries} &gt; 0 (resolution by name) means the name could not be proven unique.
     */
    record WorkspaceLookupDto(
            WorkspaceDto workspace,
            int unreadableWorkspaceEntries,
            int unreadableProjectEntries
    ) {
        public WorkspaceLookupDto {
            if (workspace == null) {
                throw new IllegalArgumentException("workspace must not be null");
            }
            requireNonNegative(unreadableWorkspaceEntries, "unreadableWorkspaceEntries");
            requireNonNegative(unreadableProjectEntries, "unreadableProjectEntries");
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

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

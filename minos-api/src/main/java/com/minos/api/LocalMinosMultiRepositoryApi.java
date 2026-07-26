package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.git.GitIntelligenceService;
import com.minos.workspace.WorkspaceIntelligenceService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Local M12 implementation layered on the validated M11 {@link LocalMinosApi}.
 */
public final class LocalMinosMultiRepositoryApi implements MinosMultiRepositoryApi {

    private final LocalMinosApi delegate;
    private final GitIntelligenceService gitIntelligence;
    private final WorkspaceIntelligenceService workspaceIntelligence;

    public LocalMinosMultiRepositoryApi(Path home) throws MinosApiException {
        this(openApplication(home));
    }

    /** Uses the same application composition as CLI/MCP instead of rebuilding local stores. */
    public LocalMinosMultiRepositoryApi(MinosApplication application) {
        MinosApplication app = Objects.requireNonNull(application, "application");
        this.delegate = new LocalMinosApi(app);
        this.gitIntelligence = app.gitIntelligence();
        this.workspaceIntelligence = app.workspaceIntelligence();
    }

    @Override
    public ProjectDto addProject(Path rootPath, String displayName) throws MinosApiException {
        return delegate.addProject(rootPath, displayName);
    }

    @Override
    public List<ProjectDto> listProjects() throws MinosApiException {
        return delegate.listProjects();
    }

    @Override
    public ProjectDto getProject(String projectIdentifier) throws MinosApiException {
        return delegate.getProject(projectIdentifier);
    }

    @Override
    public IndexImportDto importScip(
            String projectIdentifier,
            Path indexFile,
            IndexImportRequest request
    ) throws MinosApiException {
        return delegate.importScip(projectIdentifier, indexFile, request);
    }

    @Override
    public List<SymbolDto> findSymbols(String projectIdentifier, SymbolQuery query) throws MinosApiException {
        return delegate.findSymbols(projectIdentifier, query);
    }

    @Override
    public List<UsageDto> findUsages(String projectIdentifier, String symbolId, int limit) throws MinosApiException {
        return delegate.findUsages(projectIdentifier, symbolId, limit);
    }

    @Override
    public List<RelationshipDto> findRelationships(
            String projectIdentifier,
            RelationshipQuery query
    ) throws MinosApiException {
        return delegate.findRelationships(projectIdentifier, query);
    }

    @Override
    public ArchitectureDto getArchitecture(String projectIdentifier) throws MinosApiException {
        return delegate.getArchitecture(projectIdentifier);
    }

    @Override
    public ModuleContextDto getModuleContext(
            String projectIdentifier,
            String moduleIdentifier
    ) throws MinosApiException {
        return delegate.getModuleContext(projectIdentifier, moduleIdentifier);
    }

    @Override
    public ImpactReportDto analyzeImpact(String projectIdentifier, ImpactQuery query) throws MinosApiException {
        return delegate.analyzeImpact(projectIdentifier, query);
    }

    @Override
    public WorkspaceDto createWorkspace(String name) throws MinosApiException {
        return execute(() -> workspace(workspaceIntelligence.createWorkspace(name)));
    }

    @Override
    public List<WorkspaceDto> listWorkspaces() throws MinosApiException {
        return execute(() -> workspaceIntelligence.listWorkspaces().stream()
                .map(LocalMinosMultiRepositoryApi::workspace)
                .toList());
    }

    @Override
    public WorkspaceDto getWorkspace(String workspaceIdentifier) throws MinosApiException {
        return execute(() -> workspace(workspaceIntelligence.getWorkspace(workspaceIdentifier)));
    }

    @Override
    public WorkspaceDto assignProjectToWorkspace(
            String projectIdentifier,
            String workspaceIdentifier
    ) throws MinosApiException {
        return execute(() -> workspace(workspaceIntelligence.assignProject(projectIdentifier, workspaceIdentifier)));
    }

    @Override
    public GitRepositoryDto inspectGit(String projectIdentifier) throws MinosApiException {
        ProjectDto project = delegate.getProject(projectIdentifier);
        return execute(() -> repository(gitIntelligence.inspect(Path.of(project.rootPath()))));
    }

    @Override
    public GitActivityDto analyzeGitActivity(
            String projectIdentifier,
            GitActivityQuery query
    ) throws MinosApiException {
        ProjectDto project = delegate.getProject(projectIdentifier);
        return execute(() -> {
            GitActivityQuery value = required(query, "query");
            return gitActivity(gitIntelligence.analyze(
                    Path.of(project.rootPath()),
                    new GitIntelligenceService.ActivityQuery(
                            value.since(), value.maxCommits(), value.maxFiles(), value.zoneDepth()
                    )
            ));
        });
    }

    @Override
    public WorkspaceIntelligenceDto analyzeWorkspace(
            String workspaceIdentifier,
            WorkspaceQuery query
    ) throws MinosApiException {
        return execute(() -> {
            WorkspaceQuery value = required(query, "query");
            return workspaceReport(workspaceIntelligence.analyze(
                    workspaceIdentifier,
                    value.maxRelationships()
            ));
        });
    }

    private static WorkspaceDto workspace(WorkspaceIntelligenceService.WorkspaceView value) {
        return new WorkspaceDto(
                value.id(), value.name(), value.projectIds(), value.createdAt(), value.updatedAt()
        );
    }

    private static GitRepositoryDto repository(GitIntelligenceService.RepositoryView value) {
        return new GitRepositoryDto(
                value.repositoryId(), value.workTree(), value.originRemote(), value.branch(), value.headCommit(),
                value.detachedHead(), value.shallow(), value.clean(), value.limitations()
        );
    }

    private static GitActivityDto gitActivity(GitIntelligenceService.ActivityReport report) {
        return new GitActivityDto(
                repository(report.repository()),
                report.query().since().toString(),
                report.query().maxCommits(),
                report.query().maxFiles(),
                report.query().zoneDepth(),
                report.scannedCommitCount(),
                report.historyTruncated(),
                report.filesTruncated(),
                report.recentCommits().stream().map(LocalMinosMultiRepositoryApi::commit).toList(),
                report.files().stream().map(LocalMinosMultiRepositoryApi::fileActivity).toList(),
                report.zones().stream().map(LocalMinosMultiRepositoryApi::zoneActivity).toList(),
                report.limitations()
        );
    }

    private static GitCommitDto commit(GitIntelligenceService.CommitActivity value) {
        return new GitCommitDto(
                value.commitId(), value.committedAt().toString(), value.authorName(), value.authorEmail(),
                value.message(), value.changedPaths()
        );
    }

    private static GitFileActivityDto fileActivity(GitIntelligenceService.FileActivity value) {
        return new GitFileActivityDto(
                value.path(), value.commitCount(), value.uniqueAuthorCount(), value.lastChangedAt().toString(),
                value.lastCommitId()
        );
    }

    private static GitZoneActivityDto zoneActivity(GitIntelligenceService.ZoneActivity value) {
        return new GitZoneActivityDto(
                value.zone(), value.commitTouches(), value.distinctFileCount(), value.lastChangedAt().toString()
        );
    }

    private static WorkspaceIntelligenceDto workspaceReport(WorkspaceIntelligenceService.WorkspaceReport report) {
        return new WorkspaceIntelligenceDto(
                workspace(report.workspace()),
                report.projects().stream().map(LocalMinosMultiRepositoryApi::workspaceProject).toList(),
                report.exactResolutionCount(),
                report.ambiguousTargetCount(),
                report.unresolvedTargetCount(),
                report.relationshipsTruncated(),
                report.crossRepositoryRelationships().stream()
                        .map(LocalMinosMultiRepositoryApi::crossRelationship)
                        .toList(),
                report.limitations()
        );
    }

    private static WorkspaceProjectDto workspaceProject(WorkspaceIntelligenceService.ProjectSnapshotView value) {
        return new WorkspaceProjectDto(
                value.projectId(), value.projectName(), value.rootPath(), value.indexed(), value.snapshotId(),
                value.localSymbolCount(), value.unresolvedRelationshipCount()
        );
    }

    private static CrossRepositoryRelationshipDto crossRelationship(
            WorkspaceIntelligenceService.CrossRepositoryRelationship value
    ) {
        return new CrossRepositoryRelationshipDto(
                value.sourceProjectId(), value.sourceRelationshipId(), value.sourceSymbolId(),
                value.targetProjectId(), value.targetSymbolId(), value.targetQualifiedName(), value.kind(),
                value.providerId(), value.providerExternalId(), value.resolutionBasis(), value.confidence()
        );
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static <T> T execute(ApiCall<T> call) throws MinosApiException {
        try {
            return call.call();
        } catch (MinosApiException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new MinosApiException(ErrorCode.INVALID_REQUEST, failureMessage(exception), exception);
        } catch (IllegalStateException exception) {
            throw new MinosApiException(ErrorCode.UNAVAILABLE, failureMessage(exception), exception);
        } catch (IOException exception) {
            throw new MinosApiException(ErrorCode.IO_FAILURE, failureMessage(exception), exception);
        } catch (Exception exception) {
            throw new MinosApiException(ErrorCode.EXECUTION_FAILURE, failureMessage(exception), exception);
        }
    }

    private static MinosApplication openApplication(Path home) throws MinosApiException {
        try {
            return MinosApplication.open(Objects.requireNonNull(home, "home"));
        } catch (IOException exception) {
            throw new MinosApiException(ErrorCode.IO_FAILURE, "MINOS M12 API bootstrap failed", exception);
        }
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T call() throws Exception;
    }
}

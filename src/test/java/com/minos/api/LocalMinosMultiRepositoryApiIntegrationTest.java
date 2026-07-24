package com.minos.api;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMinosMultiRepositoryApiIntegrationTest {

    @Test
    void publicM12ApiExposesWorkspaceAndGitIntelligence(@TempDir Path temp) throws Exception {
        Path projectRoot = Files.createDirectories(temp.resolve("repository"));
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Files.createDirectories(projectRoot.resolve("src"));
            Files.writeString(projectRoot.resolve("src/Main.java"), "class Main {}\n");
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("MINOS", "minos@example.com")
                    .setCommitter("MINOS", "minos@example.com")
                    .call();
        }

        MinosMultiRepositoryApi api = new LocalMinosMultiRepositoryApi(temp.resolve("minos-home"));
        assertEquals("1", api.contractVersion());
        assertEquals("1", api.multiRepositoryContractVersion());

        MinosApi.ProjectDto project = api.addProject(projectRoot, "m12-project");
        MinosMultiRepositoryApi.WorkspaceDto workspace = api.createWorkspace("m12-workspace");
        workspace = api.assignProjectToWorkspace(project.id(), workspace.id());
        assertEquals(1, workspace.projectIds().size());
        assertEquals(project.id(), workspace.projectIds().getFirst());
        assertEquals(1, api.listWorkspaces().size());
        assertEquals(workspace.id(), api.getWorkspace("m12-workspace").id());

        MinosMultiRepositoryApi.GitRepositoryDto repository = api.inspectGit(project.id());
        assertNotNull(repository.repositoryId());
        assertNotNull(repository.headCommit());
        assertTrue(repository.clean());
        assertTrue(repository.limitations().contains("NO_ORIGIN_REMOTE"));

        MinosMultiRepositoryApi.GitActivityDto activity = api.analyzeGitActivity(
                project.id(),
                new MinosMultiRepositoryApi.GitActivityQuery(Instant.EPOCH, 100, 100, 2)
        );
        assertEquals(1, activity.scannedCommitCount());
        assertFalse(activity.historyTruncated());
        assertFalse(activity.filesTruncated());
        assertEquals("src/Main.java", activity.files().getFirst().path());

        MinosMultiRepositoryApi.WorkspaceIntelligenceDto multiRepo = api.analyzeWorkspace(
                workspace.id(),
                MinosMultiRepositoryApi.WorkspaceQuery.defaults()
        );
        assertEquals(1, multiRepo.projects().size());
        assertFalse(multiRepo.projects().getFirst().indexed());
        assertTrue(multiRepo.limitations().contains("PROJECT_WITHOUT_ACTIVE_SNAPSHOT"));

        MinosApi.MinosApiException invalidRequest = assertThrows(
                MinosApi.MinosApiException.class,
                () -> api.analyzeWorkspace(workspace.id(), null)
        );
        assertEquals(MinosApi.ErrorCode.INVALID_REQUEST, invalidRequest.code());

        System.out.printf(
                "M12 multi-repo Git: workspace=%s, projects=%d, git-commits=%d, files=%d, exact-cross-repo=%d%n",
                workspace.id(), multiRepo.projects().size(), activity.scannedCommitCount(), activity.files().size(),
                multiRepo.exactResolutionCount()
        );
    }
}

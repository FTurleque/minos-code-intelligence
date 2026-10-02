package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** R1 lot 2 : le statut projet de l'API expose le run reprenable. */
class LocalMinosApiResumeStatusTest {

    @Test
    void projectStatusExposesTheResumableRun(@TempDir Path temp) throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        MinosApplication application = MinosApplication.open(temp.resolve("home"));
        RegisteredProject registered = application.projectRegistry().registerProject(project, "api-resume");
        UUID runId = UUID.randomUUID();
        Instant interruptedAt = Instant.now().minusSeconds(45);
        application.indexStateStore().saveRun(new IndexingRun(runId, registered.id(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROMOTION, interruptedAt.minusSeconds(60), Optional.of(interruptedAt), List.of(),
                Optional.of("snapshot-staged"), Optional.empty(), Optional.empty(), Optional.of("interrupted"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        application.indexStateStore().saveProjectState(new ProjectIndexState(registered.id(),
                ProjectIndexState.Availability.FAILED, Optional.empty(), Optional.of(runId), interruptedAt,
                Optional.of("interrupted"), Optional.of(runId)));

        try (LocalMinosApi api = new LocalMinosApi(application)) {
            MinosApi.ProjectDto dto = api.getProject("api-resume");

            assertEquals(runId.toString(), dto.resumableRunId());
            assertEquals(0, dto.resumableTargets());
            assertNotNull(dto.resumableCheckpointAgeSeconds());
            assertEquals("FAILED", dto.indexState());
        }

        MinosApplication fresh = MinosApplication.open(temp.resolve("home-empty"));
        fresh.projectRegistry().registerProject(project, "api-plain");
        try (LocalMinosApi api = new LocalMinosApi(fresh)) {
            MinosApi.ProjectDto dto = api.getProject("api-plain");
            assertNull(dto.resumableRunId());
            assertNull(dto.resumableTargets());
        }
    }
}

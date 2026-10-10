package com.minos.output;

import com.minos.application.ProjectInspectionService;
import com.minos.application.ProjectOperations;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ResumableRunSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.minos.output.json.DeterministicJson;

class ProjectJsonTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final UUID RUN = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void theProjectObjectHasItsTwelveKeysInDeclaredOrder() {
        Map<String, Object> map = ProjectJson.project(operationsView());

        assertEquals(List.of("id", "name", "rootPath", "rootAvailable", "languages", "buildSystems", "moduleCount",
                "indexState", "activeSnapshotId", "lastSuccessfulIndexAt", "providerId", "providerVersion"),
                List.copyOf(map.keySet()));
        assertEquals("{\"id\":\"p-1\",\"name\":\"demo\",\"rootPath\":\"/work/demo\",\"rootAvailable\":true,"
                        + "\"languages\":[\"java\"],\"buildSystems\":[\"maven\"],\"moduleCount\":3,"
                        + "\"indexState\":\"READY\",\"activeSnapshotId\":\"snap-1\","
                        + "\"lastSuccessfulIndexAt\":null,\"providerId\":\"scip-java\",\"providerVersion\":\"1.0\"}",
                DeterministicJson.render(map));
    }

    @Test
    void bothProjectViewRecordsGiveTheSameProjection() {
        assertEquals(ProjectJson.project(operationsView()), ProjectJson.project(inspectionView()));
        assertEquals(ProjectJson.indexStatus(operationsView(), Optional.empty(), NOW),
                ProjectJson.indexStatus(inspectionView(), Optional.empty(), NOW));
    }

    @Test
    void theIndexStatusOffersNothingWhenNoRunIsResumable() {
        Map<String, Object> map = ProjectJson.indexStatus(operationsView(), Optional.empty(), NOW);

        assertEquals("{\"projectId\":\"p-1\",\"projectName\":\"demo\",\"state\":\"READY\","
                        + "\"activeSnapshotId\":\"snap-1\",\"lastSuccessfulIndexAt\":null,"
                        + "\"providerId\":\"scip-java\",\"providerVersion\":\"1.0\","
                        + "\"resumableRunId\":null,\"resumableRunPhase\":null,"
                        + "\"resumableCheckpointAgeSeconds\":null,\"resumableTargets\":null}",
                DeterministicJson.render(map));
    }

    @Test
    void theIndexStatusDescribesTheResumableRun() {
        ResumableRunSummary summary = new ResumableRunSummary(
                RUN, IndexingRun.Phase.PROVIDER_EXECUTION, NOW.minusSeconds(90), 2, 3);

        Map<String, Object> map = ProjectJson.indexStatus(operationsView(), Optional.of(summary), NOW);

        assertEquals(RUN.toString(), map.get("resumableRunId"));
        assertEquals("PROVIDER_EXECUTION", map.get("resumableRunPhase"));
        assertEquals(90L, map.get("resumableCheckpointAgeSeconds"));
        assertEquals(2, map.get("resumableTargets"));
    }

    @Test
    void theProjectionStaysOpenForTheCallerToAppendItsOwnKeys() {
        Map<String, Object> map = ProjectJson.project(operationsView());

        map.put("providerProfiles", List.of());

        assertEquals("providerProfiles", List.copyOf(map.keySet()).getLast());
    }

    private static ProjectOperations.ProjectView operationsView() {
        return new ProjectOperations.ProjectView("p-1", "demo", "/work/demo", true, List.of("java"),
                List.of("maven"), 3, "READY", "snap-1", null, "scip-java", "1.0");
    }

    private static ProjectInspectionService.ProjectView inspectionView() {
        return new ProjectInspectionService.ProjectView("p-1", "demo", "/work/demo", true, List.of("java"),
                List.of("maven"), 3, "READY", "snap-1", null, "scip-java", "1.0");
    }
}

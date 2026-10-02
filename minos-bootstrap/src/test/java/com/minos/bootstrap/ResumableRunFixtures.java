package com.minos.bootstrap;

import com.minos.application.MinosApplication;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fixture de TEST (sources de test de minos-bootstrap, publiées par son test-jar) : un projet Java minimal
 * enregistré sous {@link #PROJECT_NAME}, dont le dernier run est {@code INTERRUPTED} et reprenable
 * (ADR 0039 §6) : phase {@code PROVIDER_EXECUTION}, une cible {@code scip-java} avec point de contrôle, état
 * de projet {@code FAILED} qui désigne ce run comme reprenable. Partagée par les vues `index-status` de la
 * CLI et du MCP.
 */
public final class ResumableRunFixtures {

    public static final String PROJECT_NAME = "resume-fixture";

    private ResumableRunFixtures() {
    }

    /**
     * Crée le projet sous {@code workspace/project}, l'enregistre et y dépose le run interrompu reprenable.
     *
     * @return l'identifiant du run reprenable
     */
    public static UUID seedInterruptedResumableRun(MinosApplication application, Path workspace) throws IOException {
        Path project = workspace.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/App.java"), "class App {}");
        RegisteredProject registered = application.projectRegistry().registerProject(project, PROJECT_NAME);
        UUID runId = UUID.randomUUID();
        Instant checkpointAt = Instant.now().minusSeconds(120);
        application.indexStateStore().saveRun(new IndexingRun(runId, registered.id(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.Phase.PROVIDER_EXECUTION, checkpointAt.minusSeconds(60), Optional.of(checkpointAt.plusSeconds(30)),
                List.of(new IndexerExecution(Language.JAVA, "scip-java",
                        application.home().resolve("runs").resolve(runId.toString()).resolve("scip-java/index.scip"),
                        Optional.of(new ExecutionCheckpoint(Path.of(""), "0.10.0", 12L, "a".repeat(64), "b".repeat(64),
                                IndexingMode.FULL, List.of(), checkpointAt)))),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("interrupted"),
                IndexingRun.CURRENT_FORMAT_VERSION));
        application.indexStateStore().saveProjectState(new ProjectIndexState(registered.id(),
                ProjectIndexState.Availability.FAILED, Optional.empty(), Optional.of(runId),
                checkpointAt.plusSeconds(30), Optional.of("interrupted"), Optional.of(runId)));
        return runId;
    }
}

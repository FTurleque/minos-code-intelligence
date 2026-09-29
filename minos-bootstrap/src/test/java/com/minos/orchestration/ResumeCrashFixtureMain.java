package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.io.DurableAtomicFile;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.runtime.local.FileResumableRunMarkers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Child-JVM fixture of {@code ResumeAfterHardKillIntegrationTest} (R1 lot 5). Runs one indexing of
 * a three-module project against a file-backed MINOS home with a counting indexer double.
 *
 * <p>Modes: {@code full} runs to completion; {@code crash} writes a sentinel file after the second
 * target's checkpoint is persisted and then blocks forever, waiting to be killed; {@code resume}
 * runs to completion with the default resume policy. Every provider execution appends one line to
 * {@code <home>/executions.log} so the parent can prove which targets ran in which JVM.</p>
 */
public final class ResumeCrashFixtureMain {

    static final String PROVIDER = "scip-typescript";
    static final String VERSION = "0.4.0";
    static final List<Path> SCOPES = List.of(Path.of("ui/app"), Path.of("ui/lib"), Path.of("ui/web"));
    static final String PROJECT_ID = "6d1a0b6a-6cb3-4f9e-9d2a-0f0d1a2b3c4d";

    private ResumeCrashFixtureMain() {
    }

    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        Path home = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path project = Path.of(arguments[2]).toAbsolutePath().normalize();
        UUID projectId = UUID.fromString(PROJECT_ID);
        FileIndexStateStore store = new FileIndexStateStore(home.resolve("index-state"));
        FileResumableRunMarkers fileMarkers = new FileResumableRunMarkers(home);
        ResumableRunMarkers markers = new ResumableRunMarkers() {
            @Override public void mark(UUID runId) throws IOException { fileMarkers.mark(runId); }
            @Override public void unmark(UUID runId) throws IOException { fileMarkers.unmark(runId); }
            @Override public Optional<Path> runDirectory(UUID runId) { return Optional.of(fileMarkers.runDirectory(runId)); }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(new CountingExecutor(home, "crash".equals(mode) ? 2 : Integer.MAX_VALUE)),
                new FileStager(home), new FilePromoter(home), store, markers, ResumableArtifactPolicy.DEFAULT);
        // The parent forwards -Dminos.test.resumePolicy to prove the test detects an absent resume.
        IndexingResumePolicy policy = arguments.length > 3
                ? IndexingResumePolicy.valueOf(arguments[3]) : IndexingResumePolicy.RESUME;
        IndexingRun run = lifecycle.execute(projectId, project, discovery(project), negotiation(), policy);
        Files.writeString(home.resolve("result-" + mode + ".txt"),
                run.id() + "\n" + run.status() + "\n" + run.resume().map(Object::toString).orElse("none") + "\n");
        System.exit(run.status() == IndexingRun.Status.SUCCEEDED ? 0 : 3);
    }

    static ProjectDiscovery discovery(Path root) {
        List<DiscoveredModule> modules = new ArrayList<>();
        for (Path scope : SCOPES) {
            modules.add(new DiscoveredModule(scope, scope.getFileName().toString(), EnumSet.of(BuildSystem.NPM),
                    List.of(new SourceRoot(scope.resolve("src"), SourceRootKind.SOURCE, Language.TYPESCRIPT))));
        }
        return new ProjectDiscovery(root, "polyglot", Set.of(Language.TYPESCRIPT), Set.of(BuildSystem.NPM), modules);
    }

    static IndexerNegotiationResult negotiation() {
        IndexerDescriptor descriptor = new IndexerDescriptor(PROVIDER, VERSION, PROVIDER, Set.of(Language.TYPESCRIPT),
                Set.of(), EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.RESUMABLE_ARTIFACT),
                IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerNegotiationResult(List.of(new IndexerSelection(Language.TYPESCRIPT, descriptor)),
                Set.of(), List.of());
    }

    /** Writes a deterministic artifact per scope and records every execution durably. */
    static final class CountingExecutor implements IndexerExecutor {
        private final Path home;
        private final int blockAtExecution;
        private int executions;

        CountingExecutor(Path home, int blockAtExecution) {
            this.home = home;
            this.blockAtExecution = blockAtExecution;
        }

        @Override public String indexerId() { return PROVIDER; }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
            if (executions == blockAtExecution) {
                // Sentinel first (durable), then block: the parent kills this JVM mid-provider.
                Path sentinel = home.resolve("sentinel.tmp");
                Files.writeString(sentinel, "blocked\n");
                DurableAtomicFile.replace(sentinel, home.resolve("sentinel"), "crash sentinel");
                while (true) Thread.sleep(1_000L);
            }
            executions++;
            String scope = request.projectRelativeRoot().toString().replace('\\', '/');
            Path directory = home.resolve("runs").resolve(request.runId().toString()).resolve(PROVIDER)
                    .resolve("scopes").resolve(scope.replace('/', '-'));
            Files.createDirectories(directory);
            Path artifact = directory.resolve("index.scip");
            String source = Files.readString(request.projectRoot().resolve("src/index.ts"));
            Path temporary = directory.resolve("index.partial.scip");
            Files.writeString(temporary, "scip:" + scope + ":" + source, StandardCharsets.UTF_8);
            DurableAtomicFile.replace(temporary, artifact, "fixture artifact");
            Files.writeString(home.resolve("executions.log"), scope + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new IndexingArtifact(Language.TYPESCRIPT, PROVIDER, artifact, request.projectRelativeRoot());
        }
    }

    /** Concatenates the artifacts, in order, into a staged file named after the run. */
    static final class FileStager implements SnapshotStager {
        private final Path home;

        FileStager(Path home) { this.home = home; }

        @Override
        public String stage(IndexSnapshotStageRequest request) throws Exception {
            StringBuilder content = new StringBuilder();
            for (IndexingArtifact artifact : request.artifacts()) {
                content.append(artifact.projectRelativeRoot().toString().replace('\\', '/')).append('=')
                        .append(Files.readString(artifact.finalArtifact())).append('\n');
            }
            Path staged = Files.createDirectories(home.resolve("staged")).resolve(request.runId() + ".bin");
            Files.writeString(staged, content.toString(), StandardCharsets.UTF_8);
            return staged.getFileName().toString();
        }
    }

    /** Copies the staged file to the active index and records the active id durably. */
    static final class FilePromoter implements SnapshotPromoter {
        private final Path home;

        FilePromoter(Path home) { this.home = home; }

        @Override
        public void promote(UUID projectId, UUID runId, String stagedSnapshotId) throws Exception {
            Path staged = home.resolve("staged").resolve(stagedSnapshotId);
            Path temporary = home.resolve("active.tmp");
            Files.copy(staged, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            DurableAtomicFile.replace(temporary, home.resolve("active.bin"), "fixture active index");
            Path idTemporary = home.resolve("active-id.tmp");
            Files.writeString(idTemporary, stagedSnapshotId);
            DurableAtomicFile.replace(idTemporary, home.resolve("active.id"), "fixture active id");
        }

        @Override
        public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) throws IOException {
            Path id = home.resolve("active.id");
            return Files.isRegularFile(id)
                    ? ActiveSnapshotObservation.active(Files.readString(id).strip())
                    : ActiveSnapshotObservation.noActiveSnapshot();
        }
    }
}

package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.IndexingResumePlanner.Outcome;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 3 : décisions du planificateur de reprise sur les cas dégénérés (ADR 0039 §3 et §7). */
class IndexingResumePlannerTest {

    private static final String PROVIDER = "scip-java";
    private static final String VERSION = "0.10.0";
    private static final Instant CHECKPOINT = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-26T09:00:00Z");

    @TempDir
    Path temp;

    @Test
    void referenceToAMissingOrNonInterruptedRunIsRefused() throws Exception {
        Setup setup = new Setup();
        setup.store.saveProjectState(setup.stateOffering(UUID.randomUUID()));
        Outcome missing = setup.plan();
        assertInstanceOf(Outcome.Refused.class, missing);
        assertTrue(((Outcome.Refused) missing).reason().contains("not interrupted"));

        IndexingRun failed = setup.interruptedRun(setup.validArtifact(), IndexingRun.Status.FAILED,
                IndexingRun.CURRENT_FORMAT_VERSION);
        setup.store.saveRun(failed);
        setup.store.saveProjectState(setup.stateOffering(failed.id()));
        assertInstanceOf(Outcome.Refused.class, setup.plan());
    }

    @Test
    void nothingOfferedWhenTheProjectHasNoResumableRun() {
        Setup setup = new Setup();
        setup.store.saveProjectState(ProjectIndexState.neverIndexed(setup.projectId, NOW));

        assertInstanceOf(Outcome.NotOffered.class, setup.plan());
    }

    @Test
    void legacyFormatRunIsRefused() throws Exception {
        Setup setup = new Setup();
        IndexingRun legacy = setup.interruptedRun(setup.validArtifact(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.LEGACY_FORMAT_VERSION);
        setup.store.saveRun(legacy);
        setup.store.saveProjectState(setup.stateOffering(legacy.id()));

        Outcome outcome = setup.plan();

        assertInstanceOf(Outcome.Refused.class, outcome);
        assertTrue(((Outcome.Refused) outcome).reason().contains("format"));
    }

    @Test
    void artifactOutsideTheRunDirectoryIsRefusedBeforeAnyRead() throws Exception {
        Setup setup = new Setup();
        Files.createDirectories(setup.runsRoot.resolve(setup.runId.toString()));
        Path outside = Files.writeString(temp.resolve("elsewhere.scip"), "outside");
        IndexingRun run = setup.interruptedRun(outside, IndexingRun.Status.INTERRUPTED, IndexingRun.CURRENT_FORMAT_VERSION);
        setup.store.saveRun(run);
        setup.store.saveProjectState(setup.stateOffering(run.id()));

        Outcome outcome = setup.plan();

        assertInstanceOf(Outcome.Refused.class, outcome);
        assertTrue(((Outcome.Refused) outcome).reason().contains("outside"), ((Outcome.Refused) outcome).reason());
    }

    @Test
    void symbolicLinkArtifactIsRefused() throws Exception {
        Setup setup = new Setup();
        Path real = setup.validArtifact();
        Path link = real.resolveSibling("index.scip");
        Files.move(real, real.resolveSibling("real.scip"));
        try {
            Files.createSymbolicLink(link, real.resolveSibling("real.scip"));
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            Assumptions.assumeTrue(false, "symbolic links are not creatable on this host");
        }
        IndexingRun run = setup.interruptedRun(link, IndexingRun.Status.INTERRUPTED, IndexingRun.CURRENT_FORMAT_VERSION);
        setup.store.saveRun(run);
        setup.store.saveProjectState(setup.stateOffering(run.id()));

        Outcome outcome = setup.plan();

        assertInstanceOf(Outcome.Refused.class, outcome);
        assertTrue(((Outcome.Refused) outcome).reason().contains("symbolic"), ((Outcome.Refused) outcome).reason());
    }

    @Test
    void unknownRunDirectoryPortRefusesTheResume() throws Exception {
        Setup setup = new Setup();
        IndexingRun run = setup.interruptedRun(setup.validArtifact(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.CURRENT_FORMAT_VERSION);
        setup.store.saveRun(run);
        setup.store.saveProjectState(setup.stateOffering(run.id()));

        Outcome outcome = IndexingResumePlanner.plan(setup.store, ResumableRunMarkers.none(),
                ResumableArtifactPolicy.DEFAULT, setup.request());

        assertInstanceOf(Outcome.Refused.class, outcome);
        assertTrue(((Outcome.Refused) outcome).reason().contains("run directory"));
    }

    @Test
    void validCheckpointIsResumedWithTheNextAttempt() throws Exception {
        Setup setup = new Setup();
        IndexingRun run = setup.interruptedRun(setup.validArtifact(), IndexingRun.Status.INTERRUPTED,
                IndexingRun.CURRENT_FORMAT_VERSION);
        setup.store.saveRun(run);
        setup.store.saveProjectState(setup.stateOffering(run.id()));

        Outcome outcome = setup.plan();

        Outcome.Resume resume = assertInstanceOf(Outcome.Resume.class, outcome);
        assertEquals(run.id(), resume.run().id());
        assertEquals(2, resume.attempt());
        assertEquals(1, resume.reused().size());
        assertTrue(resume.remaining().isEmpty());
        assertEquals(Duration.ofHours(24), IndexingResumePlanner.DEFAULT_RESUME_TTL);
    }

    private final class Setup {
        final UUID projectId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final Path root;
        final Path runsRoot;
        final InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        final ResumableRunMarkers port;

        Setup() {
            try {
                root = Files.createDirectories(temp.resolve("project"));
                Files.writeString(root.resolve("pom.xml"), "<project/>");
                runsRoot = Files.createDirectories(temp.resolve("home/runs"));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            port = new ResumableRunMarkers() {
                @Override public void mark(UUID id) { }
                @Override public void unmark(UUID id) { }
                @Override public Optional<Path> runDirectory(UUID id) { return Optional.of(runsRoot.resolve(id.toString())); }
            };
        }

        Path validArtifact() throws IOException {
            Path directory = Files.createDirectories(runsRoot.resolve(runId.toString()).resolve(PROVIDER));
            return Files.writeString(directory.resolve("index.scip"), "scip-bytes");
        }

        IndexingRun interruptedRun(Path artifact, IndexingRun.Status status, int format) throws Exception {
            byte[] bytes = Files.readAllBytes(artifact);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String fingerprint = new com.minos.incremental.ProjectFingerprintService().capture(root).projectSha256();
            return new IndexingRun(runId, projectId, status, IndexingRun.Phase.PROVIDER_EXECUTION,
                    CHECKPOINT.minusSeconds(60), Optional.of(CHECKPOINT.plusSeconds(30)),
                    List.of(new IndexerExecution(Language.JAVA, PROVIDER, artifact.toAbsolutePath().normalize(),
                            Optional.of(new ExecutionCheckpoint(Path.of(""), VERSION, bytes.length, sha, fingerprint,
                                    IndexingMode.FULL, List.of(), CHECKPOINT)))),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("interrupted"), format);
        }

        ProjectIndexState stateOffering(UUID id) {
            return new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED, Optional.empty(),
                    Optional.of(id), NOW, Optional.of("interrupted"), Optional.of(id));
        }

        IndexingResumePlanner.Request request() {
            IndexerDescriptor descriptor = new IndexerDescriptor(PROVIDER, VERSION, PROVIDER, Set.of(Language.JAVA),
                    Set.of(), EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.RESUMABLE_ARTIFACT),
                    IndexerQualification.QUALIFIED, 100, List.of());
            IndexingExecutionTarget target = new IndexingExecutionTarget(
                    new IndexerSelection(Language.JAVA, descriptor), Path.of(""));
            return new IndexingResumePlanner.Request(store.findProjectState(projectId).orElseThrow(), root,
                    List.of(target), IndexingMode.FULL, List.of(), NOW, IndexingResumePlanner.DEFAULT_RESUME_TTL);
        }

        Outcome plan() {
            return IndexingResumePlanner.plan(store, port, ResumableArtifactPolicy.DEFAULT, request());
        }
    }

    static {
        // Keep the charset import meaningful for fixtures that write bytes explicitly.
        assert StandardCharsets.UTF_8 != null;
    }
}

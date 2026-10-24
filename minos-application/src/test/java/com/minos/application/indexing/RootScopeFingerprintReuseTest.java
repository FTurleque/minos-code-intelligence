package com.minos.application.indexing;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectIgnorePolicy;
import com.minos.discovery.SourceRootDetector;
import com.minos.incremental.BuildDescriptorPolicy;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-04: an indexing whose target is the project root hashes the source tree twice (reference, end of run),
 * because the root scope reuses the reference fingerprint: the run checkpoints with the orchestration's own service.
 *
 * <p>The source changes during the discovery, after the reference capture and before the run: the checkpoint of the
 * root scope then tells which capture it holds. Reused, it is the reference; captured again, it is the changed tree.</p>
 */
class RootScopeFingerprintReuseTest {

    @TempDir Path temp;

    @Test
    void theRootScopeReusesTheReferenceFingerprintInsteadOfHashingTheTreeAgain() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.extraDetectors = List.of(new SourceChangedAtDiscovery(fixture.root));

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals(outcome.preparation().fingerprintBefore().projectSha256(), rootCheckpoint(outcome.run().orElseThrow()),
                "the root scope holds the reference fingerprint: no third hash of the tree");
        assertFalse(outcome.fingerprintPromoted(), "the tree changed after the reference: the baseline is not promoted");
    }

    @Test
    void theRunCheckpointsWithTheFingerprintServiceOfTheOrchestrationWhateverItsRules() throws Exception {
        // D2: the engine keeps no service of its own. A service with other rules than the engine defaults is still
        // the one the run checkpoints and verifies a resume with, so the reference it captured is reused as is.
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.fingerprintService = new ProjectFingerprintService(BuildDescriptorPolicy.legacyDefaults());
        fixture.extraDetectors = List.of(new SourceChangedAtDiscovery(fixture.root));

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        String checkpoint = rootCheckpoint(outcome.run().orElseThrow());
        assertEquals(outcome.preparation().fingerprintBefore().projectSha256(), checkpoint);
        assertNotEquals(fixture.fingerprintService.captureScope(fixture.root, Path.of("")).projectSha256(), checkpoint,
                "the tree changed after the reference: the value is the reference, not a new capture");
    }

    @Test
    void theReusedValueIsExactlyTheCaptureOfTheRootScopeSoAResumeAcceptsIt() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        ProjectFingerprintService service = new ProjectFingerprintService();

        assertEquals(service.capture(fixture.root), service.captureScope(fixture.root, Path.of("")));

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        // The value a resume recomputes on an unchanged tree (IndexingResumePlanner) is the one persisted.
        assertEquals(service.captureScope(fixture.root, Path.of("")).projectSha256(),
                rootCheckpoint(outcome.run().orElseThrow()));
    }

    @Test
    void aContentChangeThatKeepsSizeAndDateIsStillAChangeOfTheWorkspace() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        Path source = fixture.root.resolve("src/main/java/App.java");
        FileTime date = Files.getLastModifiedTime(source);
        fixture.executor.duringRun = () -> {
            try {
                Files.writeString(source, "class Ap2 {}");
                Files.setLastModifiedTime(source, date);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        };

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals(Files.size(source), "class App {}".length());
        assertEquals(date, Files.getLastModifiedTime(source));
        assertFalse(outcome.fingerprintPromoted(), "the end-of-run fingerprint is a full hash, not size and date");
        assertTrue(outcome.diagnostic().orElseThrow().contains("workspace changed during indexing"));
    }

    private static String rootCheckpoint(IndexingRun run) {
        return run.executions().getFirst().checkpoint().orElseThrow().scopeFingerprint();
    }

    /** Changes a source once the discovery walks the modules: after the reference capture, before the run. */
    private static final class SourceChangedAtDiscovery implements SourceRootDetector {
        private final Path project;
        private boolean changed;

        private SourceChangedAtDiscovery(Path project) {
            this.project = project;
        }

        @Override
        public List<ProjectDiscovery.SourceRoot> detect(Path projectRoot, Path moduleRoot, ProjectIgnorePolicy ignorePolicy)
                throws IOException {
            if (!changed) {
                changed = true;
                Files.writeString(project.resolve("src/main/java/App.java"), "class App { int discovered; }");
            }
            return List.of();
        }
    }
}

package com.minos.runtime.local;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules of the Linux cgroup ownership boundary, proved on every system through its seam: each missing prerequisite
 * refuses, the transformer is single-use, and a kill or release failure is reported once, never swallowed.
 */
class LinuxCgroupOwnershipBoundaryRulesTest {

    @TempDir
    Path temporary;

    @Test
    void theCapabilityNamesTheFirstMissingPrerequisite() {
        Path root = temporary.resolve("cgroup");
        Path shell = temporary.resolve("sh");

        assertTrue(boundary(Optional.of(root), Optional.of(shell), new FakeJob()).capability().strong());
        assertEquals(List.of("delegated cgroup v2 root with cgroup.kill is unavailable"),
                boundary(Optional.empty(), Optional.of(shell), new FakeJob()).capability().diagnostics());
        assertEquals(List.of("trusted POSIX sh is unavailable"),
                boundary(Optional.of(root), Optional.empty(), new FakeJob()).capability().diagnostics());
        assertFalse(boundary(Optional.empty(), Optional.empty(), new FakeJob()).capability().strong());
    }

    @Test
    void noTransformerIsBuiltWithoutARootOrAShell() {
        IndexingExecutionRequest request = request();
        LinuxCgroupOwnershipBoundary noRoot = boundary(Optional.empty(), Optional.of(temporary), new FakeJob());
        LinuxCgroupOwnershipBoundary noShell = boundary(Optional.of(temporary), Optional.empty(), new FakeJob());

        IllegalStateException rootFailure = assertThrows(IllegalStateException.class, () -> noRoot.transformer(request));
        IllegalStateException shellFailure = assertThrows(IllegalStateException.class,
                () -> noShell.transformer(request));

        assertTrue(rootFailure.getMessage().endsWith("delegated cgroup v2 ownership is unavailable"));
        assertTrue(shellFailure.getMessage().endsWith(
                "trusted POSIX sh required by cgroup ownership launcher is unavailable"));
    }

    @Test
    void theTransformerEntersTheJobOnceAndRefusesASecondPlan() throws Exception {
        FakeJob job = new FakeJob();
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = transformer(job);

        IndexerProcessPlan transformed = transformer.transform(plan(), temporary);

        assertEquals(List.of("enter", "provider"), transformed.command());
        IndexerProcessPlan second = plan();
        assertThrows(IllegalStateException.class, () -> transformer.transform(second, temporary));
        assertDoesNotThrow(transformer::releaseContainment);
        assertTrue(job.closed);
        assertDoesNotThrow(transformer::releaseContainment, "a released transformer releases nothing more");
    }

    @Test
    void aKillFailureIsRaisedWhenTheContainmentIsReleased() throws Exception {
        FakeJob job = new FakeJob();
        job.killFailure = new IllegalStateException("kill failed");
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = transformer(job);
        transformer.transform(plan(), temporary);

        transformer.killContainedJob();
        transformer.killContainedJob();
        IllegalStateException failure = assertThrows(IllegalStateException.class, transformer::releaseContainment);

        assertSame(job.killFailure, failure);
        assertEquals(0, failure.getSuppressed().length, "the same failure twice is reported once");
        assertTrue(job.closed);
    }

    @Test
    void aCloseFailureIsRaisedAloneOrSuppressedUnderAKillFailure() throws Exception {
        FakeJob alone = new FakeJob();
        alone.closeFailure = new IllegalStateException("close failed");
        ProcessIndexerExecutor.ProcessPlanTransformer onlyClose = transformer(alone);
        onlyClose.transform(plan(), temporary);

        assertSame(alone.closeFailure, assertThrows(IllegalStateException.class, onlyClose::releaseContainment));

        FakeJob both = new FakeJob();
        both.killFailure = new IllegalStateException("kill failed");
        both.closeFailure = new IllegalStateException("close failed");
        ProcessIndexerExecutor.ProcessPlanTransformer killThenClose = transformer(both);
        killThenClose.transform(plan(), temporary);
        killThenClose.killContainedJob();

        IllegalStateException failure = assertThrows(IllegalStateException.class, killThenClose::releaseContainment);

        assertSame(both.killFailure, failure);
        assertEquals(List.of(both.closeFailure), List.of(failure.getSuppressed()));
    }

    @Test
    void twoDistinctKillFailuresAreBothReported() throws Exception {
        FakeJob job = new FakeJob();
        IllegalStateException first = new IllegalStateException("first kill failed");
        IllegalStateException second = new IllegalStateException("second kill failed");
        job.killFailures.add(first);
        job.killFailures.add(second);
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = transformer(job);
        transformer.transform(plan(), temporary);

        transformer.killContainedJob();
        transformer.killContainedJob();
        IllegalStateException failure = assertThrows(IllegalStateException.class, transformer::releaseContainment);

        assertSame(first, failure);
        assertEquals(List.of(second), List.of(failure.getSuppressed()));
    }

    @Test
    void aSuccessfulKillLeavesNothingToReportAtRelease() throws Exception {
        FakeJob job = new FakeJob();
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = transformer(job);
        transformer.transform(plan(), temporary);

        transformer.killContainedJob();

        assertTrue(job.killed);
        assertDoesNotThrow(transformer::releaseContainment);
    }

    @Test
    void theSameFailureFromKillAndCloseIsReportedOnce() throws Exception {
        FakeJob job = new FakeJob();
        job.killFailure = new IllegalStateException("job unreachable");
        job.closeFailure = job.killFailure;
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = transformer(job);
        transformer.transform(plan(), temporary);
        transformer.killContainedJob();

        IllegalStateException failure = assertThrows(IllegalStateException.class, transformer::releaseContainment);

        assertSame(job.killFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void anUnusedTransformerKillsAndReleasesNothing() {
        FakeJob job = new FakeJob();
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = transformer(job);

        assertDoesNotThrow(transformer::killContainedJob);
        assertDoesNotThrow(transformer::releaseContainment);
        assertFalse(job.created);
    }

    private ProcessIndexerExecutor.ProcessPlanTransformer transformer(FakeJob job) {
        return boundary(Optional.of(temporary.resolve("cgroup")), Optional.of(temporary.resolve("sh")), job)
                .transformer(request());
    }

    private static LinuxCgroupOwnershipBoundary boundary(Optional<Path> root, Optional<Path> shell, FakeJob job) {
        return new LinuxCgroupOwnershipBoundary(() -> root, () -> shell, (jobRoot, name) -> {
            job.created = true;
            return job;
        });
    }

    private IndexerProcessPlan plan() {
        return new IndexerProcessPlan(List.of("provider"), temporary, Map.of(), temporary.resolve("index.scip"),
                Duration.ofMinutes(1));
    }

    private IndexingExecutionRequest request() {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), temporary,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }

    /** A job whose kill and close fail on demand; it enters by prefixing the command. */
    private static final class FakeJob implements LinuxCgroupOwnershipBoundary.OwnershipJob {
        private boolean created;
        private boolean closed;
        private boolean killed;
        private RuntimeException killFailure;
        private RuntimeException closeFailure;
        private final List<RuntimeException> killFailures = new ArrayList<>();

        @Override
        public List<String> enterThenExec(Path shell, List<String> command) {
            List<String> entered = new ArrayList<>(List.of("enter"));
            entered.addAll(command);
            return entered;
        }

        @Override
        public void kill() {
            if (!killFailures.isEmpty()) throw killFailures.remove(0);
            if (killFailure != null) throw killFailure;
            killed = true;
        }

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) throw closeFailure;
        }
    }
}

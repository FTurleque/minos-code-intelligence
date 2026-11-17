package com.minos.runtime.local;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.local.sandbox.SandboxTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Linux cgroup ownership boundary, on Linux only: its capability and the lifecycle of an unused transformer. */
@EnabledOnOs(OS.LINUX)
class LinuxCgroupOwnershipBoundaryTest {

    @TempDir
    Path temporary;

    @Test
    void theBoundaryIsStrongWhereADelegatedCgroupRootAndAShellExist() {
        LinuxCgroupOwnershipBoundary boundary = new LinuxCgroupOwnershipBoundary();
        StrongProcessOwnershipIndexerExecutor.Capability capability = boundary.capability();

        SandboxTestSupport.requireStrongCapability(capability.strong(), capability.diagnostics());
        assertEquals(LinuxCgroupOwnershipBoundary.MECHANISM, capability.mechanism());
    }

    @Test
    void aTransformerThatNeverCreatedItsJobKillsAndReleasesNothing() {
        LinuxCgroupOwnershipBoundary boundary = new LinuxCgroupOwnershipBoundary();
        StrongProcessOwnershipIndexerExecutor.Capability capability = boundary.capability();
        SandboxTestSupport.requireStrongCapability(capability.strong(), capability.diagnostics());

        ProcessIndexerExecutor.ProcessPlanTransformer transformer = boundary.transformer(request());

        assertDoesNotThrow(transformer::killContainedJob);
        assertDoesNotThrow(transformer::releaseContainment);
    }

    private IndexingExecutionRequest request() {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), temporary,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}

package com.minos.remote;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A worker request names exactly one source: a remote revision or a local project fingerprint. */
class DistributedIndexingWorkerRequestTest {

    @TempDir Path project;

    @Test
    void aRemoteRequestKeepsItsRevisionAndHasNoFingerprint() {
        WorkerRequest remote = new WorkerRequest(execution(), "0.13.2",
                "https://github.com/acme/demo.git", "a".repeat(40), WorkerNetworkPolicy.DENY);

        assertFalse(remote.local());
        assertThrows(IllegalStateException.class, remote::projectFingerprint);
        assertEquals("a".repeat(40), remote.sourceCommit());
        assertEquals("https://github.com/acme/demo.git", remote.sourceRepository());
    }

    @Test
    void aLocalRequestCarriesTheProjectFingerprintWithoutARevision() {
        WorkerRequest local = WorkerRequest.local(execution(), "0.13.2", "d".repeat(64), WorkerNetworkPolicy.DENY);

        assertTrue(local.local());
        assertEquals("d".repeat(64), local.projectFingerprint());
        assertThrows(IllegalStateException.class, local::sourceRepository);
        assertThrows(IllegalStateException.class, local::sourceCommit);
    }

    @Test
    void aRequestRefusesAMixedOrMalformedSource() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkerRequest.local(execution(), "0.13.2", "d".repeat(40), WorkerNetworkPolicy.DENY));
        assertThrows(IllegalArgumentException.class,
                () -> WorkerRequest.local(execution(), "0.13.2", null, WorkerNetworkPolicy.DENY));
        assertThrows(IllegalArgumentException.class, () -> new WorkerRequest(execution(), "0.13.2",
                "https://token@github.com/acme/demo.git", "a".repeat(40), WorkerNetworkPolicy.DENY));
        assertThrows(NullPointerException.class, () -> new WorkerRequest(execution(), "0.13.2",
                (DistributedArtifactSource) null, WorkerNetworkPolicy.DENY));
    }

    private IndexingExecutionRequest execution() {
        IndexerDescriptor descriptor = new IndexerDescriptor("scip-java", "0.13.2", "SCIP Java",
                Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project, project, Path.of(""),
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of(), Optional.empty());
    }
}

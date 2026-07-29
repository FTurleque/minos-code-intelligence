package com.minos.remote;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistributedArtifactManifestTest {

    @Test
    void acceptsCompleteSecretFreeProvenance() {
        DistributedArtifactManifest manifest = manifest(WorkerNetworkPolicy.ALLOW, false);

        assertEquals(DistributedArtifactManifest.FORMAT_V1, manifest.format());
        assertEquals(DistributedArtifactManifest.ARTIFACT_PATH, manifest.artifactPath());
    }

    @Test
    void rejectsUnenforcedNetworkDenyAndUnsafeSourceIdentity() {
        assertThrows(IllegalArgumentException.class, () -> manifest(WorkerNetworkPolicy.DENY, false));
        assertThrows(IllegalArgumentException.class, () -> new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://token@github.com/acme/demo.git",
                "a".repeat(40),
                Language.JAVA,
                "scip-java",
                "0.13.1",
                "worker-1",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerNetworkPolicy.ALLOW,
                false,
                Instant.parse("2026-07-29T00:00:00Z"),
                Instant.parse("2026-07-29T00:00:01Z"),
                "index.scip",
                1,
                "b".repeat(64)
        ));
    }

    @Test
    void rejectsUnknownFormatPathChecksumAndBackwardsTime() {
        DistributedArtifactManifest valid = manifest(WorkerNetworkPolicy.DENY, true);
        assertThrows(IllegalArgumentException.class, () -> copy(valid, "v2", valid.artifactPath(),
                valid.artifactSha256(), valid.startedAt(), valid.completedAt()));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.format(), "../index.scip",
                valid.artifactSha256(), valid.startedAt(), valid.completedAt()));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.format(), valid.artifactPath(),
                "abc", valid.startedAt(), valid.completedAt()));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.format(), valid.artifactPath(),
                valid.artifactSha256(), valid.completedAt(), valid.startedAt()));
    }

    private static DistributedArtifactManifest manifest(WorkerNetworkPolicy policy, boolean enforced) {
        return new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "https://github.com/acme/demo.git",
                "a".repeat(40),
                Language.JAVA,
                "scip-java",
                "0.13.1",
                "worker-1",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                policy,
                enforced,
                Instant.parse("2026-07-29T00:00:00Z"),
                Instant.parse("2026-07-29T00:00:01Z"),
                "index.scip",
                5,
                "b".repeat(64)
        );
    }

    private static DistributedArtifactManifest copy(
            DistributedArtifactManifest value,
            String format,
            String path,
            String checksum,
            Instant started,
            Instant completed
    ) {
        return new DistributedArtifactManifest(
                format, value.runId(), value.projectId(), value.sourceRepository(), value.sourceCommit(),
                value.language(), value.providerId(), value.providerVersion(), value.workerId(), value.isolation(),
                value.networkPolicy(), value.networkDenyEnforced(), started, completed, path,
                value.artifactSize(), checksum
        );
    }
}

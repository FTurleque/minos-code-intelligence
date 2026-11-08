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

        assertEquals(DistributedArtifactManifest.FORMAT_V2, manifest.format());
        assertEquals(DistributedArtifactManifest.ARTIFACT_PATH, manifest.artifactPath());
        assertEquals("", manifest.projectRelativeRoot());
    }

    @Test
    void rejectsUnenforcedNetworkDenyAndUnsafeSourceIdentity() {
        assertThrows(IllegalArgumentException.class, () -> manifest(WorkerNetworkPolicy.DENY, false));
        assertThrows(IllegalArgumentException.class, () -> new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "",
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
        assertThrows(IllegalArgumentException.class, () -> copy(valid, "minos-distributed-artifact-v999",
                valid.projectRelativeRoot(), valid.artifactPath(),
                valid.artifactSha256(), valid.startedAt(), valid.completedAt()));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.format(),
                valid.projectRelativeRoot(), "../index.scip",
                valid.artifactSha256(), valid.startedAt(), valid.completedAt()));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.format(),
                valid.projectRelativeRoot(), valid.artifactPath(),
                "abc", valid.startedAt(), valid.completedAt()));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, valid.format(),
                valid.projectRelativeRoot(), valid.artifactPath(),
                valid.artifactSha256(), valid.completedAt(), valid.startedAt()));
    }

    @Test
    void v1ManifestRejectsNonRootScope() {
        assertThrows(IllegalArgumentException.class, () -> new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "services/catalog",
                "https://github.com/acme/demo.git",
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
                5,
                "b".repeat(64)
        ));
    }

    @Test
    void v2AcceptsNonRootScopeAndNormalizesDot() {
        DistributedArtifactManifest withScope = scopeManifest("services/catalog");
        assertEquals("services/catalog", withScope.projectRelativeRoot());

        DistributedArtifactManifest withDot = scopeManifest(".");
        assertEquals("", withDot.projectRelativeRoot());

        DistributedArtifactManifest withNull = scopeManifest(null);
        assertEquals("", withNull.projectRelativeRoot());
    }

    @Test
    void rejectsAbsoluteBackslashNulAndOverscopedPaths() {
        assertThrows(IllegalArgumentException.class, () -> scopeManifest("/absolute/path"));
        assertThrows(IllegalArgumentException.class, () -> scopeManifest("module" + '\\' + "sub"));
        assertThrows(IllegalArgumentException.class,
                () -> scopeManifest(String.valueOf(new char[]{(char) 0})));
        assertThrows(IllegalArgumentException.class, () -> scopeManifest("../../escape"));
        assertThrows(IllegalArgumentException.class, () -> scopeManifest("a".repeat(1025)));
    }

    @Test
    void v3CarriesALocalProjectFingerprintInsteadOfARemoteRevision() {
        DistributedArtifactManifest local = localManifest("c".repeat(64));

        assertEquals(DistributedArtifactManifest.FORMAT_V3, local.format());
        assertEquals(new DistributedArtifactSource.LocalProject("c".repeat(64)), local.source());
        assertEquals(DistributedArtifactSource.LocalProject.SOURCE_KIND, local.source().kind());
        assertEquals("c".repeat(64), local.projectFingerprint());
        assertThrows(IllegalStateException.class, local::sourceRepository);
        assertThrows(IllegalStateException.class, local::sourceCommit);
        DistributedArtifactManifest remote = manifest(WorkerNetworkPolicy.DENY, true);
        assertEquals(DistributedArtifactSource.RemoteRevision.SOURCE_KIND, remote.source().kind());
        assertThrows(IllegalStateException.class, remote::projectFingerprint);
    }

    @Test
    void v3RejectsARemoteRevisionAMalformedFingerprintAndAnUnenforcedDeny() {
        String gitLength = "c".repeat(40);
        String uppercase = "C".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> localManifest(gitLength));
        assertThrows(IllegalArgumentException.class, () -> localManifest(uppercase));
        assertThrows(IllegalArgumentException.class, () -> localManifest(null));
        DistributedArtifactManifest valid = localManifest("c".repeat(64));
        DistributedArtifactSource remote = new DistributedArtifactSource.RemoteRevision(
                "https://github.com/acme/demo.git", "a".repeat(40));
        DistributedArtifactSource local = valid.source();
        WorkerNetworkPolicy policy = valid.networkPolicy();
        boolean enforced = valid.networkDenyEnforced();
        assertThrows(IllegalArgumentException.class, () -> copyWith(valid, remote, policy, enforced));
        assertThrows(IllegalArgumentException.class, () -> copyWith(valid, local, WorkerNetworkPolicy.DENY, false));
    }

    @Test
    void remoteFormatsRejectAProjectFingerprint() {
        DistributedArtifactManifest valid = manifest(WorkerNetworkPolicy.DENY, true);
        DistributedArtifactSource local = new DistributedArtifactSource.LocalProject("c".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> copyWith(valid, local, WorkerNetworkPolicy.DENY, true));
    }

    /** {@code value} with another source and network policy, everything else unchanged. */
    private static DistributedArtifactManifest copyWith(DistributedArtifactManifest value,
                                                        DistributedArtifactSource source,
                                                        WorkerNetworkPolicy policy, boolean enforced) {
        return new DistributedArtifactManifest(value.format(), value.runId(), value.projectId(),
                value.projectRelativeRoot(), source, value.language(), value.providerId(), value.providerVersion(),
                value.workerId(), value.isolation(), policy, enforced, value.startedAt(), value.completedAt(),
                value.artifactPath(), value.artifactSize(), value.artifactSha256());
    }

    private static DistributedArtifactManifest localManifest(String fingerprint) {
        return DistributedArtifactManifest.localWsl(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "services/catalog",
                fingerprint,
                Language.JAVA,
                "scip-java",
                "0.13.2",
                "wsl:Ubuntu",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                Instant.parse("2026-11-08T00:00:00Z"),
                Instant.parse("2026-11-08T00:00:01Z"),
                5,
                "b".repeat(64)
        );
    }

    private static DistributedArtifactManifest manifest(WorkerNetworkPolicy policy, boolean enforced) {
        return new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "",
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

    private static DistributedArtifactManifest scopeManifest(String scope) {
        return new DistributedArtifactManifest(
                DistributedArtifactManifest.FORMAT_V2,
                UUID.randomUUID(),
                UUID.randomUUID(),
                scope,
                "https://github.com/acme/demo.git",
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
                5,
                "b".repeat(64)
        );
    }

    private static DistributedArtifactManifest copy(
            DistributedArtifactManifest value,
            String format,
            String scope,
            String path,
            String checksum,
            Instant started,
            Instant completed
    ) {
        return new DistributedArtifactManifest(
                format, value.runId(), value.projectId(), scope, value.sourceRepository(),
                value.sourceCommit(), value.language(), value.providerId(), value.providerVersion(),
                value.workerId(), value.isolation(), value.networkPolicy(), value.networkDenyEnforced(),
                started, completed, path, value.artifactSize(), checksum
        );
    }
}

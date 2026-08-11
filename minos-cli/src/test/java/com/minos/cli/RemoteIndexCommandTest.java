package com.minos.cli;

import com.minos.orchestration.IndexingMode;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteRepositoryRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteIndexCommandTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void materializeRendersCanonicalSecretFreeJsonAndPreservesCredentialReferenceOnly() throws Exception {
        AtomicReference<RemoteRepositoryRequest> captured = new AtomicReference<>();
        RemoteIndexOperations operations = new StubOperations() {
            @Override
            public RemoteMaterializationView materialize(RemoteRepositoryRequest request) {
                captured.set(request);
                return materialization(false);
            }
        };
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = new RemoteIndexCommand(operations).run(new String[]{
                "materialize",
                "https://github.com/acme/demo",
                "--ref", "main",
                "--commit", COMMIT,
                "--subdir", "fixtures/java",
                "--credential-env", "MINOS_REMOTE_TOKEN",
                "--format", "json"
        }, output, error);

        assertEquals(0, exit);
        assertEquals("refs/heads/main", captured.get().reference());
        assertEquals("MINOS_REMOTE_TOKEN", captured.get().credentialEnvironmentVariable().orElseThrow());
        assertTrue(output.toString().contains("\"repository\":\"https://github.com/acme/demo.git\""));
        assertTrue(output.toString().contains("\"cacheHit\":false"));
        assertFalse(output.toString().contains("MINOS_REMOTE_TOKEN"));
        assertTrue(error.isEmpty());
    }

    @Test
    void indexRequiresExplicitWorkerNetworkPolicyAndRendersEvidence() throws Exception {
        StringBuilder usageError = new StringBuilder();
        int usageExit = new RemoteIndexCommand(new StubOperations()).run(new String[]{
                "index", "https://gitlab.com/acme/demo",
                "--ref", "main", "--commit", COMMIT, "--name", "demo"
        }, new StringBuilder(), usageError);
        assertEquals(2, usageExit);
        assertTrue(usageError.toString().contains("--worker-network is required"));

        AtomicReference<WorkerNetworkPolicy> network = new AtomicReference<>();
        RemoteIndexOperations operations = new StubOperations() {
            @Override
            public RemoteIndexView index(
                    RemoteRepositoryRequest request,
                    String displayName,
                    String providerOverride,
                    String workerId,
                    WorkerNetworkPolicy policy
            ) {
                network.set(policy);
                return indexView();
            }
        };
        StringBuilder output = new StringBuilder();
        int exit = new RemoteIndexCommand(operations).run(new String[]{
                "index", "https://gitlab.com/acme/demo",
                "--ref", "main", "--commit", COMMIT,
                "--name", "demo", "--provider", "scip-java",
                "--worker", "worker-one", "--worker-network", "allow",
                "--format", "json"
        }, output, new StringBuilder());

        assertEquals(0, exit);
        assertEquals(WorkerNetworkPolicy.ALLOW, network.get());
        assertTrue(output.toString().contains("\"status\":\"SUCCEEDED\""));
        assertTrue(output.toString().contains("\"artifactSha256\":\"" + "a".repeat(64) + "\""));
        assertTrue(output.toString().contains("\"networkDenyEnforced\":false"));
    }

    @Test
    void rejectsUnsafeUrlBeforeCallingOperations() throws Exception {
        StringBuilder error = new StringBuilder();
        int exit = new RemoteIndexCommand(new StubOperations()).run(new String[]{
                "materialize", "https://token@github.com/acme/demo",
                "--ref", "main", "--commit", COMMIT
        }, new StringBuilder(), error);

        assertEquals(1, exit);
        assertTrue(error.toString().contains("must not contain credentials"));
        assertFalse(error.toString().contains("token@"));
    }

    private static RemoteIndexOperations.RemoteMaterializationView materialization(boolean hit) {
        return new RemoteIndexOperations.RemoteMaterializationView(
                "GITHUB",
                "https://github.com/acme/demo.git",
                "refs/heads/main",
                COMMIT,
                "N:\\cache\\repository",
                "N:\\cache\\repository\\fixtures\\java",
                "c".repeat(64),
                hit,
                "2026-07-29T00:00:00Z"
        );
    }

    private static RemoteIndexOperations.RemoteIndexView indexView() {
        return indexViewWithScope("");
    }

    private static RemoteIndexOperations.RemoteIndexView indexViewWithScope(String scope) {
        AutonomousIndexOperations.IndexPlanView plan = new AutonomousIndexOperations.IndexPlanView(
                "11111111-1111-1111-1111-111111111111",
                "demo",
                "N:\\cache\\repository\\fixtures\\java",
                List.of("JAVA"),
                List.of("MAVEN"),
                List.of("scip-java"),
                List.of(),
                IndexingMode.FULL,
                List.of("FORCED_FULL"),
                List.of(),
                true
        );
        AutonomousIndexOperations.IndexExecutionView execution = new AutonomousIndexOperations.IndexExecutionView(
                plan,
                "22222222-2222-2222-2222-222222222222",
                "SUCCEEDED",
                "run-22222222-2222-2222-2222-222222222222",
                true,
                null
        );
        RemoteIndexOperations.ArtifactEvidence artifact = new RemoteIndexOperations.ArtifactEvidence(
                "scip-java", "0.13.1", "JAVA", "worker-one",
                "PROCESS_EPHEMERAL_WORKSPACE", "ALLOW", false,
                "a".repeat(64), "b".repeat(64), "c".repeat(64), false,
                scope
        );
        return new RemoteIndexOperations.RemoteIndexView(
                materialization(false),
                plan.projectId(),
                plan.projectName(),
                execution,
                List.of(artifact)
        );
    }

    @Test
    void rendersProjectRelativeRootInJsonAndTextOutput() throws Exception {
        RemoteIndexOperations operations = new StubOperations() {
            @Override
            public RemoteIndexView index(
                    RemoteRepositoryRequest request,
                    String displayName,
                    String providerOverride,
                    String workerId,
                    WorkerNetworkPolicy policy
            ) {
                return indexViewWithScope("services/catalog");
            }
        };

        StringBuilder json = new StringBuilder();
        new RemoteIndexCommand(operations).run(new String[]{
                "index", "https://gitlab.com/acme/demo",
                "--ref", "main", "--commit", COMMIT,
                "--name", "demo", "--worker-network", "allow",
                "--format", "json"
        }, json, new StringBuilder());
        assertTrue(json.toString().contains("\"projectRelativeRoot\":\"services/catalog\""),
                "JSON must expose projectRelativeRoot");

        StringBuilder text = new StringBuilder();
        new RemoteIndexCommand(operations).run(new String[]{
                "index", "https://gitlab.com/acme/demo",
                "--ref", "main", "--commit", COMMIT,
                "--name", "demo", "--worker-network", "allow"
        }, text, new StringBuilder());
        assertTrue(text.toString().contains("scope=services/catalog"),
                "text output must expose scope");
    }

    private static class StubOperations implements RemoteIndexOperations {
        @Override
        public RemoteMaterializationView materialize(RemoteRepositoryRequest request) {
            throw new AssertionError("unexpected materialize call");
        }

        @Override
        public RemoteIndexView index(
                RemoteRepositoryRequest request,
                String displayName,
                String providerOverride,
                String workerId,
                WorkerNetworkPolicy workerNetworkPolicy
        ) {
            throw new AssertionError("unexpected index call");
        }
    }
}

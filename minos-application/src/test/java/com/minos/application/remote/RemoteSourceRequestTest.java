package com.minos.application.remote;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteRepositoryRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * D9: the remote source is validated where it is built, with the engine message, so a command refuses it before it
 * opens the application; D2: the worker network choice has one value per engine policy, with the same name.
 */
class RemoteSourceRequestTest {

    private static final String COMMIT = "0123456789ABCDEF0123456789abcdef01234567";

    @Test
    void aValidSourceIsCanonicalAndCarriesOnlyTheNameOfItsCredentialVariable() {
        RemoteSourceRequest request = RemoteSourceRequest.of(
                "https://github.com/acme/demo", "main", COMMIT, "fixtures/java", "MINOS_REMOTE_TOKEN");

        assertEquals("https://github.com/acme/demo.git", request.repository());
        assertEquals("refs/heads/main", request.reference());
        assertEquals(COMMIT.toLowerCase(java.util.Locale.ROOT), request.commit());
        assertEquals(Optional.of("MINOS_REMOTE_TOKEN"), request.credentialEnvironmentVariable());
        assertEquals("RemoteSourceRequest[repository=https://github.com/acme/demo.git, reference=refs/heads/main, commit="
                + COMMIT.toLowerCase(java.util.Locale.ROOT) + ", projectSubdirectory=fixtures/java, "
                + "credentialEnvironmentVariable=MINOS_REMOTE_TOKEN]", request.toString());
        assertEquals(Optional.empty(), RemoteSourceRequest.of("https://gitlab.com/acme/demo", "main", COMMIT, null, null)
                .credentialEnvironmentVariable());
    }

    @Test
    void anUrlCarryingASecretIsRefusedWithTheEngineMessage() {
        String url = "https://token@github.com/acme/demo";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> RemoteSourceRequest.of(url, "main", COMMIT, null, null));
        IllegalArgumentException engine = assertThrows(IllegalArgumentException.class,
                () -> RemoteRepositoryRequest.of(url, "main", COMMIT, null, null));

        assertEquals(engine.getMessage(), refused.getMessage());
        assertFalse(refused.getMessage().contains("token@"), refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> RemoteSourceRequest.of("https://github.com/acme/demo", "main", "abc123", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteSourceRequest.of("https://github.com/acme/demo", "main", COMMIT, "../escape", null));
    }

    @Test
    void everyWorkerNetworkPolicyHasAChoiceOfTheSameNameAndBack() {
        Set<String> choices = new TreeSet<>(Arrays.stream(WorkerNetworkChoice.values()).map(Enum::name).toList());
        Set<String> policies = new TreeSet<>(Arrays.stream(WorkerNetworkPolicy.values()).map(Enum::name).toList());

        Set<String> withoutChoice = new TreeSet<>(policies);
        withoutChoice.removeAll(choices);
        Set<String> withoutPolicy = new TreeSet<>(choices);
        withoutPolicy.removeAll(policies);
        assertEquals(Set.of(), withoutChoice, "engine network policies without an application choice");
        assertEquals(Set.of(), withoutPolicy, "application choices without an engine network policy");
        for (WorkerNetworkChoice choice : WorkerNetworkChoice.values()) {
            assertEquals(choice.name(), choice.policy().name());
        }
    }
}

package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
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

/** indexation-java-via-wsl › La voie WSL réutilise l'empreinte du projet connue de l'indexation. */
class IndexingExecutionRequestFingerprintTest {

    @Test
    void aLowerCaseSha256IsCarriedAndTheNineArgumentShapeCarriesNone(@TempDir Path project) {
        String fingerprint = "0123456789abcdef".repeat(4);

        assertEquals(Optional.of(fingerprint), request(project, Optional.of(fingerprint)).registeredProjectFingerprint());
        assertEquals(Optional.empty(), new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                project, Path.of(""), selection(), IndexingMode.FULL, List.of(), Optional.empty())
                .registeredProjectFingerprint());
    }

    @Test
    void aMalformedFingerprintIsRefusedWithoutAnAbsolutePath(@TempDir Path project) {
        for (String malformed : List.of("ABCDEF0123456789".repeat(4), "a".repeat(63), "a".repeat(65),
                "g".repeat(64), "")) {
            Optional<String> fingerprint = Optional.of(malformed);
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> request(project, fingerprint), malformed);

            assertEquals("registeredProjectFingerprint must be a lower-case hexadecimal SHA-256",
                    refusal.getMessage());
            assertFalse(refusal.getMessage().contains(project.toString()));
        }
    }

    private static IndexingExecutionRequest request(Path project, Optional<String> fingerprint) {
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project, project, Path.of(""),
                selection(), IndexingMode.FULL, List.of(), Optional.empty(), fingerprint);
    }

    private static IndexerSelection selection() {
        return new IndexerSelection(Language.JAVA, new IndexerDescriptor("scip-java", "0.13.2", "scip-java",
                Set.of(Language.JAVA), Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED, 1, List.of()));
    }
}

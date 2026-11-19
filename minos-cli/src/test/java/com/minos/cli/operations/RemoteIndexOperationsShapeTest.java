package com.minos.cli.operations;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The remote port renders the application summaries directly: it declares no field-for-field copy of them. */
class RemoteIndexOperationsShapeTest {

    private static final Set<String> STRICT_MIRRORS = Set.of("RemoteMaterializationView", "ArtifactEvidence");

    @Test
    void theRemotePortDeclaresNoStrictMirrorOfAnApplicationSummary() {
        List<String> mirrors = Arrays.stream(RemoteIndexOperations.class.getDeclaredClasses())
                .map(Class::getSimpleName)
                .filter(STRICT_MIRRORS::contains)
                .sorted()
                .toList();

        assertEquals(List.of(), mirrors);
    }
}

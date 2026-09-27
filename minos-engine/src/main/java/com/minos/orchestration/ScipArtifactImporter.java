package com.minos.orchestration;

import com.minos.adapter.scip.ScipSymbolSnapshotReport;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Port : publication d'un artefact SCIP explicite comme snapshot de connaissance (cas d'usage
 * {@code import-scip}). L'application ne connaît que ce contrat ; l'adaptateur SCIP (minos-provider-scip)
 * le réalise. La requête et le rapport sont des données pures, descendues avec le port.
 */
@FunctionalInterface
public interface ScipArtifactImporter {

    ScipSymbolSnapshotReport importSnapshot(
            Path indexFile,
            ScipSymbolSnapshotRequest request,
            CodeKnowledgeSnapshotStore snapshotStore
    ) throws IOException;
}

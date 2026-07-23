package com.minos.adapter.scip;

import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.nio.file.Path;
import java.util.Map;

/**
 * Harness M3 pour publier un vrai index SCIP puis laisser la CLI produit le
 * relire dans un autre processus. Ce point d'entrée reste limité aux tests.
 */
public final class ScipPersistentSnapshotExperiment {

    private ScipPersistentSnapshotExperiment() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: ScipPersistentSnapshotExperiment <index.scip> <minos-home> <project-root>"
            );
        }

        Path index = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path home = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path projectRoot = Path.of(arguments[2]).toAbsolutePath().normalize();
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "m3-typescript-simple");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(
                home.resolve("symbol-snapshots")
        );
        ScipSymbolSnapshotReport report = new ScipSymbolSnapshotImporter().importSnapshot(
                index,
                new ScipSymbolSnapshotRequest(
                        project.id(),
                        "m3-real-typescript-simple",
                        "main",
                        "scip-typescript",
                        "0.4.0",
                        "m3-real-typescript-simple",
                        Map.of()
                ),
                snapshots
        );
        CodeKnowledgeSnapshot snapshot = snapshots.loadActiveKnowledge(project.id()).orElseThrow();

        System.out.println("projectId\t" + project.id());
        System.out.println("snapshotId\t" + report.snapshotId());
        System.out.println("symbols\t" + snapshot.symbols().size());
        System.out.println("occurrences\t" + snapshot.occurrences().size());
        System.out.println("providerRelationships\t" + report.relationshipCount());
        System.out.println("derivedRelationships\t" + report.derivedRelationshipCount());
        System.out.println("relatedTestRelationships\t"
                + report.relatedTestRelationshipCount());
        System.out.println("persistedRelationships\t" + snapshot.relationships().size());
    }
}

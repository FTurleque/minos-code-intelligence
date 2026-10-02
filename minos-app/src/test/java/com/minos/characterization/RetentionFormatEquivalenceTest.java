package com.minos.characterization;

import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.orchestration.ScipSymbolSnapshotRequest;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.storage.local.store.KnowledgeSnapshotCodecs;
import com.minos.storage.local.store.SnapshotCodecV2;
import com.minos.storage.local.store.SnapshotIntegrityService;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Preuve de l'exception de {@code retention.golden} (ADR 0046) : pour chaque snapshot du scénario de
 * rétention, le fichier V3 décodé est exactement le modèle que le code de {@code d9ae1005} écrivait en V2.
 *
 * <p>Même import que {@code minos import-scip} dans le scénario (même fixture, identité de projet figée,
 * {@code indexRunId = "application-" + snapshotId}, fournisseur et version). Le fichier V3 a le sha256 que le
 * nouveau golden liste ; son modèle décodé, réencodé en V2, redonne à l'octet près le sha256 que la base avait
 * écrit (ancien golden). Le V2 étant déterministe, la base et V3 portent donc le même contenu logique.</p>
 */
class RetentionFormatEquivalenceTest {

    private static final Path SCIP = Path.of("fixtures", "typescript", "typescript-modules",
            ".minos-m0", "scip-typescript", "index.scip");
    private static final UUID PINNED_PROJECT = UUID.fromString("a2a2a2a2-0000-4000-8000-00000000c0de");

    /** sha256 des fichiers V2 écrits par le code de {@code d9ae1005} (retention.golden de la base). */
    private static final Map<String, String> BASE_V2_SHA256 = Map.of(
            "a2-snapshot-1", "ae70a8e4bb46fae4a18135b79285b8fed91f5407d9966c362040b6fe3bf500ea",
            "a2-snapshot-2", "0aa1a3f11ca138712207d34e3564d09b8c52dc2d0bc6063817713432af0ce87d",
            "a2-snapshot-3", "cf9fefcb3f8cf9111a8724a985d3cc43ccbe542820a09ac68a84d93effcd00ec",
            "a2-snapshot-4", "97f23527038c76c8c12ec133bb9ee3d59782e171457d6290d92645e5d5c9ff5c");

    /** sha256 des fichiers V3 écrits aujourd'hui (retention.golden après ADR 0046). */
    private static final Map<String, String> V3_SHA256 = Map.of(
            "a2-snapshot-1", "65c5b7880fd63471ed815bd232bf34698c77d75b7dd1c807c8bbd3661f27003a",
            "a2-snapshot-2", "cdc627e4c1fb20bf3486c3784b4230cbe5dbd34cf7ff42f1b775ec81ab8f239c",
            "a2-snapshot-3", "43f45d5f34448628ea9a185fa80804faf53a57075a3358b946160d9e65da6b30",
            "a2-snapshot-4", "57ba81ec88efd8d5490567c2f6191b5b2ede46f26c5b00793b1ee1e3f9c88839");

    @Test
    void everyRetentionSnapshotCarriesInV3TheModelTheBaseWroteInV2(@TempDir Path temp) throws Exception {
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(temp.resolve("symbol-snapshots"));
        SnapshotIntegrityService integrity = new SnapshotIntegrityService();
        for (String snapshotId : List.of("a2-snapshot-1", "a2-snapshot-2", "a2-snapshot-3", "a2-snapshot-4")) {
            new ScipSymbolSnapshotImporter().importSnapshot(SCIP, new ScipSymbolSnapshotRequest(PINNED_PROJECT,
                    snapshotId, null, "scip-typescript", "0.4.0", "application-" + snapshotId, Map.of()), store);

            Path v3 = snapshotFile(store.storageRoot().resolve(PINNED_PROJECT.toString()),
                    "snapshot-" + integrity.logicalIdHash(snapshotId) + "-");
            assertEquals(3, KnowledgeSnapshotCodecs.formatVersionOf(v3));
            assertEquals("snapshot-" + integrity.logicalIdHash(snapshotId) + "-" + V3_SHA256.get(snapshotId) + ".knowledge",
                    v3.getFileName().toString());

            CodeKnowledgeSnapshot model = KnowledgeSnapshotCodecs.read(v3);
            Path v2 = temp.resolve(snapshotId + ".v2.knowledge");
            assertEquals(BASE_V2_SHA256.get(snapshotId), new SnapshotCodecV2().write(v2, model).sha256(),
                    "re-encoded in V2, the model must give back the exact bytes the base wrote");
            assertEquals(model, new SnapshotCodecV2().read(v2));
        }
    }

    private static Path snapshotFile(Path directory, String prefix) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> matches = files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList();
            assertEquals(1, matches.size(), "one file per logical snapshot id: " + matches);
            return matches.getFirst();
        }
    }
}

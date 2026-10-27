package com.minos.storage.local.store;

import java.nio.file.Path;

/**
 * Produit les magasins de référence de {@code src/test/resources/snapshot-formats/base-d9ae1005/} avec le code
 * de stockage de {@code d9ae1005}, c'est-à-dire avant le format V3 (lot A6, étape 2). Ce n'est pas un test : il
 * se compile, avec {@link LegacySnapshotContent}, contre les classes de {@code minos-storage-local} de la base,
 * placées en tête du classpath, puis s'exécute une fois. L'écriture étant déterministe, relancer la procédure
 * redonne les mêmes octets, ce qui permet de vérifier leur provenance (procédure dans {@code ARCHI-SUIVI.md},
 * § A6.11). Les fichiers de snapshot sont ensuite renommés {@code snapshot.<extension>} dans les ressources,
 * leur nom d'origine dépassant la longueur de chemin que Git accepte sous Windows ; leurs octets sont inchangés.
 */
public final class LegacySnapshotFixtureGenerator {

    private LegacySnapshotFixtureGenerator() {
    }

    public static void main(String[] arguments) throws Exception {
        Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
        new FileSymbolSnapshotStore(output.resolve("v1-symbols")).publish(
                LegacySnapshotContent.V1_PROJECT, LegacySnapshotContent.V1_SNAPSHOT, LegacySnapshotContent.v1Symbols());
        publish(output.resolve("v2-well-formed"), LegacySnapshotContent.v2());
        publish(output.resolve("v2-lone-surrogate"), LegacySnapshotContent.v2WithLoneSurrogate());
    }

    private static void publish(Path root, com.minos.store.CodeKnowledgeSnapshot snapshot) throws Exception {
        new FileSymbolSnapshotStore(root).publish(snapshot.projectId(), snapshot.snapshotId(),
                snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
    }
}

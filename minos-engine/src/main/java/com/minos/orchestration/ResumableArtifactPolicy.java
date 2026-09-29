package com.minos.orchestration;

import java.util.Objects;

/**
 * Décide si l'artefact d'un indexeur peut être réutilisé par une reprise (ADR 0039, risque
 * « provider non déterministe »). La décision dérive de la capacité déclarée
 * {@link IndexerCapability#RESUMABLE_ARTIFACT} du descripteur : elle est explicite, par provider,
 * jamais implicite. {@code scip-java} la déclare dans le catalogue SCIP ; les autres indexeurs SCIP
 * lisent des états externes non couverts par l'empreinte de scope et ne la déclarent pas.
 */
public record ResumableArtifactPolicy(boolean honoursDeclaredCapability) {

    /** Réutilise les artefacts des seuls indexeurs déclarant {@code RESUMABLE_ARTIFACT}. */
    public static final ResumableArtifactPolicy DEFAULT = new ResumableArtifactPolicy(true);

    /** Aucun artefact n'est jamais réutilisé, quelle que soit la capacité déclarée. */
    public static ResumableArtifactPolicy none() {
        return new ResumableArtifactPolicy(false);
    }

    public boolean qualifies(IndexerDescriptor indexer) {
        return honoursDeclaredCapability
                && Objects.requireNonNull(indexer, "indexer").capabilities().contains(IndexerCapability.RESUMABLE_ARTIFACT);
    }
}

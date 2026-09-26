package com.minos.orchestration;

import java.util.Objects;
import java.util.Set;

/**
 * Indexeurs dont l'artefact peut être réutilisé par une reprise (ADR 0039, risque « provider non
 * déterministe »). Tient lieu de {@code IndexerCapability.RESUMABLE_ARTIFACT} tant que ce
 * capability n'existe pas dans le catalogue : la qualification est explicite, par identifiant,
 * jamais implicite.
 *
 * <p>Qualifié par défaut : {@code scip-java}. Son artefact ne dépend que des sources et des
 * descripteurs de build du scope (couverts par l'empreinte de scope) et des dépendances résolues
 * par ces descripteurs ; le résidu non couvert (un SNAPSHOT de dépendance remplacé à coordonnées
 * identiques dans le dépôt local) produit au pire un index dont les signatures externes datent de
 * l'exécution interrompue, jamais un index partiel. Les autres indexeurs SCIP (TypeScript, Go,
 * Rust, .NET, clang) lisent des états externes non fingerprintés (node_modules, caches de
 * toolchain, compile_commands) et ne sont pas qualifiés tant qu'un test de reproductibilité ne
 * l'a pas établi.</p>
 */
public record ResumableArtifactPolicy(Set<String> qualifiedIndexerIds) {

    public static final ResumableArtifactPolicy DEFAULT = new ResumableArtifactPolicy(Set.of("scip-java"));

    public ResumableArtifactPolicy {
        qualifiedIndexerIds = Set.copyOf(Objects.requireNonNull(qualifiedIndexerIds, "qualifiedIndexerIds"));
        if (qualifiedIndexerIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("qualified indexer ids must not be blank");
        }
    }

    /** Aucun indexeur qualifié : une reprise ne réutilise jamais d'artefact. */
    public static ResumableArtifactPolicy none() {
        return new ResumableArtifactPolicy(Set.of());
    }

    public boolean qualifies(IndexerDescriptor indexer) {
        return qualifiedIndexerIds.contains(Objects.requireNonNull(indexer, "indexer").id());
    }
}

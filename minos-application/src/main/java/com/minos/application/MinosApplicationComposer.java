package com.minos.application;

import com.minos.git.GitIntelligence;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Port de la racine de composition (ADR 0042) : fournit les adaptateurs concrets par défaut que
 * {@link MinosApplication#open(Path)} et {@link MinosApplication.Builder#build()} ne reçoivent pas
 * explicitement. {@code minos-application} ne connaît que ce contrat ; son unique implémentation vit
 * dans le module {@code minos-bootstrap} et est découverte par {@link java.util.ServiceLoader}
 * ({@link MinosApplicationComposers}).
 *
 * <p>Chaque méthode est appelée exactement au point où l'assembleur instanciait l'adaptateur
 * concret, avec les mêmes arguments : l'ordre d'initialisation ne dépend pas de ce port.</p>
 */
public interface MinosApplicationComposer {

    /** Backend configuré : {@code local} intégré, les autres découverts par {@code ServiceLoader}. */
    StorageBackend openStorageBackend(StorageBackendConfiguration configuration) throws IOException;

    /** Backend local de repli, quand seuls certains magasins sont surchargés. */
    StorageBackend openLocalStorageBackend(Path home) throws IOException;

    /** Descripteurs des indexeurs qualifiés par défaut. */
    List<IndexerDescriptor> qualifiedIndexerDescriptors();

    /** Catalogue des providers qualifiés, lu à la demande. */
    IndexerProviderCatalog providerCatalog();

    /** Gestionnaire composite des runtimes de providers gérés, pour ce MINOS_HOME. */
    ProviderRuntimeManager providerRuntimeManager(Path home);

    /** Cycle de vie par défaut des snapshots (préparation et promotion). */
    SnapshotLifecycle snapshotLifecycle(
            Path home,
            CodeKnowledgeSnapshotStore snapshots,
            List<IndexerDescriptor> descriptors
    ) throws IOException;

    /** Intelligence Git factuelle. */
    GitIntelligence gitIntelligence();

    /** Import SCIP explicite ({@code import-scip}). */
    ScipArtifactImporter scipArtifactImporter();

    /** Magasin du plan de contrôle hébergé, sous le répertoire donné. */
    HostedControlPlaneStore hostedControlPlaneStore(Path directory, HostedTenantKeyProvider keys) throws IOException;

    /** Clés hébergées lues depuis l'environnement de l'opérateur (mode hébergé activé). */
    HostedTenantKeyProvider environmentHostedTenantKeyProvider();

    /** Préparation et promotion des snapshots, fournies ensemble. */
    record SnapshotLifecycle(SnapshotStager stager, SnapshotPromoter promoter) {
        public SnapshotLifecycle {
            Objects.requireNonNull(stager, "stager");
            Objects.requireNonNull(promoter, "promoter");
        }
    }
}

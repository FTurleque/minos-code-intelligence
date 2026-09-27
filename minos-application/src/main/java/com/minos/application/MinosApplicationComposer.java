package com.minos.application;

import com.minos.git.GitIntelligence;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.runtime.HostCommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.WorkerSandboxProbe;
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

    /** Marqueurs de run reprenable (R1, ADR 0039 § 5), rangés dans le répertoire de run de ce MINOS_HOME. */
    ResumableRunMarkers resumableRunMarkers(Path home);

    /** Sonde des sandboxes de worker de l'hôte réel (doctor, remote index). */
    WorkerSandboxProbe workerSandboxProbe();

    /** Localisation et invocation des exécutables de l'hôte réel. */
    HostCommandLocator hostCommandLocator();

    /** Matérialisation des révisions distantes (M25), pour ce MINOS_HOME. */
    RemoteRepositoryMaterializer remoteRepositoryMaterializer(Path home) throws IOException;

    /**
     * Moteur d'indexation distante (M25) pour ce MINOS_HOME : sélection de sandbox de l'hôte réel,
     * worker isolé et transport d'artefacts vérifiés.
     */
    RemoteIndexingRuntime remoteIndexingRuntime(Path home) throws IOException;

    /** Préparation et promotion des snapshots, fournies ensemble. */
    record SnapshotLifecycle(SnapshotStager stager, SnapshotPromoter promoter) {
        public SnapshotLifecycle {
            Objects.requireNonNull(stager, "stager");
            Objects.requireNonNull(promoter, "promoter");
        }
    }
}

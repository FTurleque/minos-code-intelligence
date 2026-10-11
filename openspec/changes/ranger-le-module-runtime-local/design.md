# Design

## Context

Quatrième et dernier module du rangement demandé par le propriétaire le 11 octobre 2026 (option « les trois modules restants, en acceptant les classes rendues publiques », un changement OpenSpec par module). HEAD analysé : branche de la PR #415 (`minos-cli` rangé), sur laquelle ce changement est empilé parce que `minos-cli` importe des classes de ce module.

## État vérifié au HEAD

- `minos-runtime-local/src/main/java/com/minos/runtime/local/` : 39 classes, un seul package ; 69 fichiers de test dans le même package.
- Classes importées par d'autres modules : 17, depuis `minos-bootstrap`, `minos-cli` et `minos-provider-scip` (`CommandLocator`, `WorkerSandboxBackend`, `WorkerSandboxBackends`, `ProcessIndexerExecutor`, `IndexerProcessPlan`, `InstallationProcessEnvironment`…). Aucun nom n'est garanti comme API publique.
- `META-INF/services/com.minos.orchestration.ExecutionPathIdentityProvider` nomme `com.minos.runtime.local.WindowsExecutionPathIdentityProvider`.
- Ressources : `WindowsContainmentScript.java:29-30` et `WindowsExecutionPathIdentityProvider.java:27` les chargent par chemin absolu (`/com/minos/runtime/local/…`) ; un déplacement de classe ne les casse pas.
- Analyse des dépendances internes pour le découpage D1 (outil d'analyse, commentaires retirés ligne par ligne) :
  - arêtes : `sandbox → process` (18 références), `sandbox → workspace` (2), `process → workspace` (4), `racine → sandbox` (8), `racine → process` (5), `racine → workspace` (2), `racine → distributed` (1) ; **aucun cycle** ;
  - 11 types package-private référencés depuis un autre groupe : `LinuxCgroupJob`, `LocalProviderWorkspace`, `ProcessIdentity`, `ProcessSandboxCapableIndexerExecutor`, `ProcessTreeTermination`, `ProviderProcessEnvironment`, `ProviderResidueReclamation`, `ProviderWorkspaceFiles`, `ProviderWriteQuotaSupervisor`, `RunDirectoryRetention`, `WindowsJobObjectProcessOwnership` ;
  - 20 usages de membres statiques package-private depuis un autre groupe.

### Découpages écartés

- Exécuteurs et primitives de processus dans un même package, sans classe d'orchestration à la racine : cycle `process ↔ sandbox` (`StrongProcessOwnershipIndexerExecutor` utilise `LinuxCgroupJob` et `WindowsJobObjectProcessOwnership` ; les backends utilisent `CommandLocator` et `ProviderProcessEnvironment`).
- Un package `execution` pour tous les exécuteurs : cycle `execution ↔ sandbox`, les backends implémentant `ProcessIndexerExecutor.ProcessPlanTransformer`.

### Rangement partiel (décision du propriétaire, 2026-10-11)

SonarCloud ne reçoit que la couverture du job Linux. Relevé sur la branche principale (API `measures/component_tree`) : le module est à 66,3 % lignes + conditions (3 515 lignes dont 1 022 non couvertes, 1 796 conditions dont 768). Le rangement complet aurait fait de tout le module du code neuf, à 727 unités du seuil de 80 %, dont 854 unités manquantes dans quatre classes que Linux ne peut pas exécuter : `WindowsAppContainerWorkerSandboxBackend` (446), `SandboxLauncherScript` (210), `WindowsExecutionPathIdentityProvider` (111), `WindowsJobObjectProcessOwnership` (87).

Le propriétaire a choisi de laisser ces quatre classes à la racine. `WorkerSandboxBackends`, qui choisit le backend et nomme donc le backend Windows, y reste aussi : la déplacer dans `sandbox` créerait le cycle `sandbox ↔ racine`. Le découpage ci-dessous est celui-là ; il reste sans cycle.

## Goals / Non-Goals

**Goals** : un package par responsabilité, aucun cycle, aucun code modifié hors `package`, imports et visibilité.

**Non-Goals** : restaurer une encapsulation par JPMS ; découper les grandes classes ; toucher au confinement.

## Decisions

### D1. Découpage

| Sous-package | Classes |
|---|---|
| racine | `LocalIsolatedIndexWorker`, `StrongProcessOwnershipIndexerExecutor`, `CompositeProviderRuntimeManager`, `WorkerSandboxBackends`, `WindowsAppContainerWorkerSandboxBackend`, `SandboxLauncherScript`, `WindowsJobObjectProcessOwnership`, `WindowsExecutionPathIdentityProvider` |
| `process` | `CommandLocator`, `ProviderProcessEnvironment`, `InstallationProcessEnvironment`, `IndexerProcessPlan`, `IndexerProcessPlanFactory`, `BoundedProcessOutput`, `ProcessIdentity`, `ProcessTreeTermination`, `ProcessOwnershipTracker`, `ProcessIndexerExecutor`, `ProcessSandboxCapableIndexerExecutor` |
| `sandbox` | `WorkerSandboxBackend`, `WorkerSandboxQualification`, `WorkerSandboxSelection`, `WorkerResourceContainment`, `LinuxBubblewrapWorkerSandboxBackend`, `SddlReplaceRights`, `WindowsContainmentScript`, `LinuxCgroupJob`, `CgroupJobOwnership` |
| `workspace` | `LocalProviderWorkspace`, `ProviderWorkspaceFiles`, `ProviderWriteQuota`, `ProviderWriteQuotaSupervisor`, `RunDirectoryRetention`, `ProviderResidueReclamation`, `FileResumableRunMarkers`, `RecoveryLogMessages` |
| `distributed` | `DistributedArtifactBundleStore`, `DistributedArtifactCachePolicy`, `DistributedIndexerExecutor` |

`ProcessIndexerExecutor` et `ProcessSandboxCapableIndexerExecutor` vont dans `process` : ils ne dépendent pas du bac à sable, et les backends s'y branchent. `StrongProcessOwnershipIndexerExecutor` reste à la racine parce qu'il assemble exécuteur, cgroup et Job Object.

### D2. Visibilité élargie au strict nécessaire

Une classe ou un membre ne devient `public` que si le compilateur le refuse depuis un autre sous-package. Un test qui n'accède qu'à des membres package-private d'une classe déplacée la suit plutôt que de faire ouvrir le code de production.

### D3. Ressources et SPI

Les ressources restent sous `com/minos/runtime/local/`. Le fichier `META-INF/services` ne change pas : le fournisseur reste à la racine.

### D4. Direction des dépendances (ADR 0022, ADR 0044)

Aucune dépendance Maven ne change. Les cinq packages appartiennent au seul module `minos-runtime-local` (règle A3) ; la règle A8 est rejouée.

## Risks / Trade-offs

- **Sécurité** : les primitives de confinement et d'environnement deviennent appelables par tout module qui dépend de `minos-runtime-local` (`minos-bootstrap`, `minos-provider-scip`). `check-module-boundaries.py` garde la liste de ces modules. Une revue de sécurité du diff est faite avant la PR.
- Couverture SonarCloud : code propre à une plateforme non couvert sur l'autre ; des tests sont ajoutés dans le module si le seuil de 80 % n'est pas atteint sous Linux.
- Gates littéraux : de nombreux scripts citent des chemins du module ; ils sont réécrits et rejoués.
- Baseline SpotBugs : à régénérer ; le passage en `public` peut faire apparaître des alertes `EI_EXPOSE_REP`.

## Windows et Linux

Aucun comportement ne change. Les tests de confinement restent exigés sur les deux plateformes en CI ; sur le poste Windows, les tests bubblewrap et cgroup sont comptés comme sautés.

## Qualification de la capacité

`frontieres-de-modules` : **qualifiée** quand `check-module-boundaries.py` est vert et que les deux jobs `Verify` passent ; aucune capacité produit n'est ajoutée ni retirée.

## Migration

Aucune donnée. Retour arrière : revert du commit.

## Écart constaté à l'implémentation (2026-10-11)

- **Dépendances du découpage retenu** (rangement partiel) : `racine → process` (19 références), `racine → sandbox` (17), `racine → workspace` (3), `racine → distributed` (1), `sandbox → process` (4), `sandbox → workspace` (1), `process → workspace` (4) ; aucun cycle. Les chiffres de « État vérifié au HEAD » décrivent le rangement complet, écarté.
- **Visibilité** : 13 types (dont l'interface imbriquée `ProcessIndexerExecutor.ProcessPlanTransformer`) et 22 membres deviennent publics. Une seule ouverture vient d'un test et non du code de production : `LinuxBubblewrapWorkerSandboxBackend.containment()`, lue par les tests qui comparent les deux backends ; elle rend une description immuable et ne prend aucun argument. Cinq autres ouvertures, d'abord faites pour `RuntimeStoragePrivacyTest`, ont été refermées après la relecture de sécurité (`BoundedProcessOutput.openTarget`, `ProcessIndexerExecutor.artifactDigestSidecar`, `DistributedArtifactBundleStore.MANIFEST_ENTRY`, `RunDirectoryRetention.Policy` et `prune(…, Policy, Instant)`) : ce test est découpé par sous-package (`process/RuntimeStoragePrivacyTest`, `workspace/RunStoragePrivacyTest`, `distributed/BundleCacheStoragePrivacyTest`), mêmes six cas.
- **Placement des tests** : quatorze tests ou aides transverses rejoignent le package dont ils lisent les membres package-private (`sandbox` : tests cgroup et bubblewrap, `SandboxTestSupport`, `CgroupSweepFixtures` ; `workspace` : `RecoveryLoggingTest`, `ProviderWriteContainmentTest`, `ProviderSandboxSecurityRegressionTest` ; `distributed` : `ScopeSwapRejectionTest`). Trois tests de classes déplacées restent à la racine parce qu'ils lisent des membres package-private de classes Windows (`SddlReplaceRightsTest`, `WorkerResourceContainmentTest`, `WorkerSandboxBackendsTest`). `SandboxTestSupport` devient publique.
- **Gates littéraux** : deux scripts construisent leurs chemins « base + nom de fichier » (`check-minos-01.py`, `check-vertical-decomposition-consistency.py`) ; la réécriture par nom qualifié ne les voyait pas, ils ont été corrigés à part (15 lignes).
- **SpotBugs** : 21 alertes avant, 21 après, les mêmes (comparées par motif, classe et membre) ; le baseline est régénéré parce que ses empreintes dépendent du nom de classe. Aucune alerte nouvelle.
- **Tests** : 365 exécutés et 44 sautés avant comme après le déplacement (poste Windows) ; 68 tests ajoutés ensuite pour la couverture.
- **Couverture** : SonarCloud donnait 79,2 % sur les 29 fichiers déplacés qu'il mesure, soit 34 unités sous le seuil. Dix classes de test indépendantes de la plateforme sont ajoutées, chacune dans le package de la classe testée. Gain mesuré sous Linux (WSL Ubuntu, Temurin 24, sans bubblewrap) : 207 unités lignes + conditions ; estimation prudente en CI : environ 199. Les journaux Maven de cette mesure Linux n'ont pas été conservés ; la valeur qui fait foi est celle de SonarCloud sur la PR.
- **Relecture de sécurité** (agent `minos-security-reviewer`, sur le diff indexé) : aucune ligne de production ne change hors `package`, imports et `public` ; ressources et SPI inchangés ; liste de tolérance des E/S privées non élargie. Trois constats traités : la Javadoc « package-private on purpose » de `ProcessSandboxCapableIndexerExecutor` est réécrite ; les ouvertures de test sont refermées (ci-dessus) ; la précondition « racine possédée par MINOS » est écrite sur `ProviderWorkspaceFiles.deleteTree`, `ProviderResidueReclamation.reclaim` et `RunDirectoryRetention.prune`, dont le contrôle de racine est lexical et faisait confiance à un appelant du même package. Aucun appelant externe n'utilise aujourd'hui un membre rendu public.
- **Hors lot, à décider par le propriétaire** : (1) faire tenir par un gate, et non par une convention, la liste des types de `process`, `sandbox` et `workspace` importables hors du module ; (2) `ProviderProcessEnvironment.sanitize` copie les variables déclarées sans leur appliquer les motifs de secret (antérieur à ce changement, à rattacher à `epingler-et-isoler-l-installation-des-providers`).

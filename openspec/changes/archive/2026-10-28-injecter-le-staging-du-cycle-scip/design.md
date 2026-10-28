# Design

## Context

Le cycle d'indexation du moteur (`IndexingRunExecutor`) délègue la mise en place et la promotion d'un snapshot à deux ports, `SnapshotStager` et `SnapshotPromoter` (`IndexingRuntimePorts.java:41`, `:91`). Pour les providers SCIP, `ScipProjectSnapshotLifecycle` (`minos-provider-scip`) les implémente : il importe chaque artefact SCIP dans un magasin de fournisseur, fusionne les snapshots normalisés, publie le snapshot fusionné dans un magasin de mise en place, puis, à la promotion, le relit et le publie dans le magasin actif. `minos-bootstrap` le construit dans `DefaultMinosApplicationComposer.snapshotLifecycle` avec le magasin actif du backend choisi.

## État vérifié au HEAD `f7c6cb13`

### Les créations de magasins

| Ligne | Rôle | Durée de vie |
|---|---|---|
| `ScipProjectSnapshotLifecycle.java:41` | magasin actif par défaut du constructeur `(Path)` : `symbol-snapshots/` | celle du `MINOS_HOME` |
| `:82` | magasin de normalisation d'un fournisseur : `staged-snapshots/<run>/providers/<indexeur>/<portée>` | un appel de `stage`, nettoyé par `cleanupProviderWorkspaces` |
| `:112` | magasin du snapshot mis en place : `staged-snapshots/<run>/project`, écrit | jusqu'à la promotion, y compris d'un autre processus |
| `:131` | même magasin, relu à la promotion | supprimé après la publication (`deleteRecursively(stagedRunRoot)`) |

Le constructeur à trois arguments (`:45-65`) reçoit déjà le magasin actif (`CodeKnowledgeSnapshotStore`) ; c'est celui de la production (`DefaultMinosApplicationComposer.java:89`). Le constructeur `(Path)` ne sert qu'aux tests : 6 constructions dans `minos-provider-scip` (`ScipProjectSnapshotLifecycleTest` 4, `ScipProjectSnapshotAuthorityTest` 1, `ScipRuntimeStoragePrivacyTest` 1) et une dans `minos-bootstrap` (`SemanticDocumentKeysReachabilityTest.java:63`).

`ScipSymbolSnapshotImporter.importSnapshot` reçoit le port `CodeKnowledgeSnapshotStore` (`ScipSymbolSnapshotImporter.java:83-86`) : l'importeur ne connaît pas le magasin fichier.

### La reprise lit le snapshot mis en place

`IndexingResumePlanner.java:93` reprend une exécution arrêtée en phase `PROMOTION` qui a un `stagedSnapshotId`, et `IndexingRunExecutor.java:171` appelle alors `resumeStagedPromotion`, donc `promote`, éventuellement dans un autre processus. Le snapshot mis en place doit donc être durable : il l'est, sur fichiers, sous `MINOS_HOME/staged-snapshots`, avec les deux backends.

### Ce qui cite le cycle

- `scripts/remediation/check-post-mne.py:123`, `:247-248` : exige `cleanupProviderWorkspaces` et `deleteRecursively(stagedRunRoot)` dans `ScipProjectSnapshotLifecycle.java`.
- `LocalStorageRetentionPreparedSnapshotTest` (`minos-storage`) reproduit l'arbre `staged-snapshots/` écrit par `stage` (`:124-125`).
- `docs/developer/indexing-and-storage.md:15-16`, `:151`, `:208` : diagramme de séquence et arbre de `MINOS_HOME`.

### Les dépendances du provider

En production, `minos-provider-scip` ne nomme `minos-storage` que dans `ScipProjectSnapshotLifecycle.java:21` (A13). En test, 5 classes l'utilisent : `ScipArtifactOpeningTest`, `ScipPersistentSnapshotExperiment`, `ScipSymbolSnapshotImporterBehaviorTest`, `ScipSymbolSnapshotImporterIntegrityTest`, `ScipProjectSnapshotLifecycleTest`. Le garde compte toutes les portées dans le graphe (`minos_dependencies`, `check-module-boundaries.py:180-195`).

### Le couple runtime d'A13

| Type de `minos-runtime-local` | Fichiers du provider | Exporté par A10 |
|---|---|---|
| `process.CommandLocator` | 10 (3 gestionnaires d'installation, 7 fabriques de plans) | oui |
| `process.IndexerProcessPlan`, `process.IndexerProcessPlanFactory` | 4 et 4 | oui |
| `process.BoundedProcessOutput`, `process.InstallationProcessEnvironment` | 3 et 3 | oui |
| `process.ProcessIndexerExecutor`, `sandbox.WorkerSandboxBackend`, `sandbox.WorkerSandboxBackends` | 1 chacun (`StrongOwnedProcessExecutors`) | oui |
| `StrongProcessOwnershipIndexerExecutor` | 1 (`StrongOwnedProcessExecutors`) | racine du module, hors des packages gardés par A10 |

Ces types servent à construire les plans de processus des indexeurs SCIP, à installer les outils (environnement de processus filtré, sorties bornées) et à exécuter les indexeurs sous propriété forte et dans le bac à sable. `ALLOWED_DEPENDENCIES` autorise `minos-provider-scip` → `minos-runtime-local`.

## Goals / Non-Goals

**Goals**

- Le provider SCIP n'instancie plus aucun magasin de snapshots ; la composition les lui donne.
- A13 n'a plus d'entrée stockage, et chaque couple restant porte une raison écrite que le garde exige.
- Rien ne change sur disque ni dans la reprise.

**Non-Goals**

- Déplacer le staging dans PostgreSQL, normaliser en mémoire, résorber le couple runtime.

## Decisions

### D1 — Un port fonctionnel ouvre les magasins de travail (Q1)

`com.minos.store.StagingSnapshotStores` (moteur) :

```java
@FunctionalInterface
public interface StagingSnapshotStores {
    CodeKnowledgeSnapshotStore open(Path root) throws IOException;
}
```

Le cycle l'appelle pour le magasin de chaque fournisseur et pour le magasin du snapshot mis en place, sous les mêmes répertoires qu'au HEAD. `minos-bootstrap` passe `FileSymbolSnapshotStore::new` : aucune classe nouvelle dans `minos-storage`.

Écartées : une classe d'implémentation dans `minos-storage`, qui n'ajouterait qu'un renvoi au constructeur ; un `Function<Path, …>` du JDK, qui ne peut pas lever `IOException` et ne se documente pas.

### D2 — Le cycle reçoit tout par son constructeur

Un seul constructeur : `ScipProjectSnapshotLifecycle(Path minosHome, CodeKnowledgeSnapshotStore activeStore, StagingSnapshotStores stagingStores, List<IndexerDescriptor> descriptors)`. Le constructeur `(Path)` et le constructeur à trois arguments disparaissent ; les 7 constructions de test passent `FileSymbolSnapshotStore::new` et un magasin actif sous `symbol-snapshots/`, comme le faisait le constructeur `(Path)`. `cleanupProviderWorkspaces` et `deleteRecursively(stagedRunRoot)` ne changent pas (`check-post-mne.py`).

### D3 — `minos-storage` passe en portée `test` dans le provider (Q2)

Le POM de `minos-provider-scip` déclare `minos-storage` en `<scope>test</scope>`. L'arête reste dans `ALLOWED_DEPENDENCIES` et `ModuleArchitectureTest.ALLOWED` (le garde compte toutes les portées) ; A13, qui ne lit que la production, n'a plus d'entrée stockage.

Le garde n'impose aujourd'hui aucune portée entre adaptateurs (seule la règle surface → `minos-bootstrap` en impose une, `SURFACE_TO_BOOTSTRAP_SCOPES`, `check-module-boundaries.py:52`). Une table `TEST_ONLY_DEPENDENCIES = {("minos-provider-scip", "minos-storage")}`, lue par `check_hexagonal_boundaries`, refuse toute autre portée que `test` pour ce couple : le retour d'une dépendance de compilation échoue dans le garde, avant même qu'une source nomme le stockage.

Écartée : réécrire ces tests avec des doubles (5 classes au HEAD, 7 après D2), qui n'exerceraient plus le vrai codec.

### D4 — Chaque couple d'A13 porte une raison écrite (Q3)

Le garde ajoute `ADAPTER_COUPLING_REASONS: dict[tuple[str, str], str]`. `check_adapter_types_named_by_adapters` refuse :

- un couple de `ADAPTER_TYPES_NAMED_BY_ADAPTER` sans raison, ou avec une raison vide ;
- une raison pour un couple absent de la table.

La raison du couple `minos-provider-scip` → `minos-runtime-local` : le provider construit les plans de processus de ses indexeurs et les exécute sous la propriété forte et le bac à sable du runtime local (ADR 0068, ADR 0070) ; il ne nomme que des types exportés par A10, plus le lanceur à propriété forte. Le résumé du garde imprime le nombre de couples justifiés.

Écartée : une raison par type ; les 9 types servent la même responsabilité, et une raison par couple suffit à rendre toute nouvelle arête entre adaptateurs visible en revue.

## Risques et coût

- **Comportement** : le cycle écrit les mêmes fichiers par le même constructeur `FileSymbolSnapshotStore(Path)`, appelé à travers le port. `ScipProjectSnapshotLifecycleTest`, `ScipProjectSnapshotAuthorityTest`, `ScipRuntimeStoragePrivacyTest` et `LocalStorageRetentionPreparedSnapshotTest` le prouvent sans changer d'assertion.
- **Tests du provider avec un double** : un test du cycle avec un `StagingSnapshotStores` qui enregistre les répertoires ouverts prouve que le cycle n'ouvre que sous `staged-snapshots/<run>` ; il est rouge tant que le cycle crée ses magasins lui-même.
- **Couverture** : seul le cycle change de code ; SonarCloud le mesure dans `minos-provider-scip`.
- **Coût** : un port d'une méthode (environ 15 lignes avec la Javadoc), environ 20 lignes changées dans le cycle, un POM, une table et une règle du garde avec leurs auto-tests, 7 constructions de test, 3 documents. Une PR, en commits séparés : rouges, port et cycle, POM et garde, documentation.

## Plateformes

Rien de propre à une plateforme : le chemin du travail de mise en place, sa confidentialité (`PrivateLocalStorage.ensurePrivateDirectory`) et son nettoyage ne changent pas. `ScipRuntimeStoragePrivacyTest` le vérifie sur les deux systèmes de la CI.

## Direction des dépendances

`minos-provider-scip` → `minos-engine` pour le port (arête existante). `minos-bootstrap` → `minos-storage` et `minos-provider-scip` (arêtes existantes) pour la référence de constructeur. Aucune arête nouvelle ; l'arête de compilation `minos-provider-scip` → `minos-storage` disparaît.

## Recouvrements

- `lire-le-registre-sans-ecrire` (actif, 19/23) cite `ScipProjectSnapshotLifecycle.java:51-64`, hors de son périmètre : une PR qui le touche se rebase sur ce changement.
- `decouper-les-classes-de-plus-de-800-lignes` (actif, 0/19) découpe `ManagedScipProviderRuntimeManager`, ce qui peut changer les comptes du couple runtime d'A13 : les deux changements se fusionnent l'un après l'autre, pas en parallèle.
- Le scénario « Nouveau type du backend local nommé par PostgreSQL », gardé dans l'exigence d'A13 parce qu'un `MODIFIED` ne peut pas le retirer, décrit la règle des packages du stockage, que l'exigence « Les backends de stockage ne se nomment pas l'un l'autre » porte aussi.

## Décisions du propriétaire (2026-10-28)

- **Q1** : port injecté, implémenté par les fichiers du backend local, pour les deux backends (D1).
- **Q2** : `minos-storage` en portée `test` dans le provider (D3).
- **Q3** : raison écrite par couple d'A13, sans résorber le couple runtime (D4).

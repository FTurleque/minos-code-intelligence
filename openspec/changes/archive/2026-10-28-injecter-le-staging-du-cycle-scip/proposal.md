# Proposal

## Why

Le constat **AUD-ARC-08** (audit du 10 octobre 2026) relève que le provider SCIP instancie lui-même le magasin de snapshots du backend local : son travail de mise en place reste sur fichiers et lié à une classe concrète de `minos-storage`, quel que soit le backend choisi. SH-02 et SH-03 ont traité l'autre moitié du constat (PostgreSQL → backend local). La tâche **SH-06** du plan de stockage hexagonal traite celle-ci ; c'est le troisième lot de la phase 4 du chantier de dette figée, dont le critère de sortie est « A13 vide, ou seulement des entrées justifiées » (`SUIVI.md`, compteurs de départ des phases).

Au HEAD `f7c6cb13` :

- **Le couplage stockage tient en un fichier.** `ScipProjectSnapshotLifecycle` crée 4 `FileSymbolSnapshotStore` (`ScipProjectSnapshotLifecycle.java:41`, `:82`, `:112`, `:131`) : le magasin actif par défaut, un magasin de normalisation par fournisseur, et le magasin du snapshot mis en place, écrit puis relu à la promotion. C'est la seule entrée stockage d'A13 (`check-module-boundaries.py:600-602`, `storage.local.store.FileSymbolSnapshotStore: 1`).
- **Les ports existent déjà.** La classe implémente `SnapshotStager` et `SnapshotPromoter` du moteur, et `minos-bootstrap` lui injecte le magasin actif du backend choisi (`DefaultMinosApplicationComposer.java:89`). Seuls les magasins de travail restent concrets.
- **Le snapshot mis en place survit au processus.** Une reprise en phase `PROMOTION` le relit plus tard (`IndexingResumePlanner.java:93`, `IndexingRunExecutor.java:171`) : il vit sous `MINOS_HOME/staged-snapshots`, avec les deux backends.
- **A13 compte aussi 9 types de `minos-runtime-local`**, nommés par 12 fichiers du provider (28 couples fichier-type) : lancement et propriété des processus d'indexation, plans d'indexeur, bac à sable. Ils font tous partie des types que `minos-runtime-local` exporte (règle A10, `RUNTIME_LOCAL_EXPORTED_TYPES`), sauf `StrongProcessOwnershipIndexerExecutor`, à la racine de `com.minos.runtime.local`. Aucune justification n'est écrite à côté de la table.

## What Changes

- **Un port du moteur, `StagingSnapshotStores`** (`com.minos.store`), ouvre un magasin de snapshots de travail sous un répertoire donné. C'est une interface fonctionnelle (décision Q1).
- **`ScipProjectSnapshotLifecycle` reçoit ce port** avec le magasin actif, et n'instancie plus aucun magasin. Ses constructeurs qui créaient un `FileSymbolSnapshotStore` disparaissent. Le comportement ne change pas : mêmes répertoires (`staged-snapshots/<run>/providers/…`, `staged-snapshots/<run>/project`), mêmes octets, même nettoyage, même reprise.
- **`minos-bootstrap` injecte `FileSymbolSnapshotStore::new`** pour les deux backends : le travail de mise en place reste sur fichiers sous `MINOS_HOME`, comme au HEAD.
- **`minos-provider-scip` ne dépend plus de `minos-storage` qu'en portée `test`** (décision Q2) : les tests du provider gardent le vrai magasin fichier (5 classes au HEAD, 7 après le changement, qui passent `FileSymbolSnapshotStore::new` au cycle). A13 n'a plus d'entrée stockage.
- **A13 justifie chaque couple d'adaptateurs** (décision Q3) : une table `ADAPTER_COUPLING_REASONS` du garde donne la raison écrite du seul couple restant, `minos-provider-scip` → `minos-runtime-local` (propriété et confinement des processus d'indexation SCIP, types exportés par A10). Un couple listé sans raison, ou une raison sans couple, fait échouer le garde.
- **Gates, tests et documentation** suivent : `ModuleArchitectureTest`, tests du cycle et de la composition, `docs/developer/indexing-and-storage.md`, arc42 § 5, ADR 0068.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : l'exigence d'A13 ne parle plus du cliquet retiré par SH-03 et exige une raison écrite par couple ; le provider SCIP ne nomme plus le stockage en production.
- `persistance-snapshots` : le travail de mise en place du cycle SCIP passe par un port injecté, sans changer ses répertoires ni sa reprise.

## Hors périmètre

- **Mettre en place dans le backend choisi** (tables PostgreSQL de staging) : migration de schéma et reprise à repenser ; hors SH-06.
- **Résorber le couple `minos-provider-scip` → `minos-runtime-local`** par des ports d'exécution : il touche au confinement et demande une revue de sécurité dédiée. Il est justifié par écrit, pas retiré.
- **Normaliser en mémoire** les snapshots de fournisseur : le passage par le codec valide aujourd'hui chaque snapshot ; il est gardé.
- **SH-12** (qualification de toute la migration).

## Impact

- **Modules du reactor** : `minos-engine` (un port), `minos-provider-scip` (cycle, POM, tests), `minos-bootstrap` (composition, un test), `minos-app` (`ModuleArchitectureTest` si une table change). Aucune arête n'est ajoutée ; l'arête `minos-provider-scip` → `minos-storage` passe de `compile` à `test`.
- **Surfaces publiques** : aucune. `MinosApplicationComposer.snapshotLifecycle` garde sa signature. `ScipProjectSnapshotLifecycle` est une classe interne d'adaptateur ; ses constructeurs changent.
- **ADR** : aucun nouvel ADR. L'ADR 0068 reçoit une note de mise en œuvre (A13, port de staging).
- **Données** : aucune. Les répertoires et les octets écrits sous `MINOS_HOME` ne changent pas.
- **Coût** : un port d'une méthode, une classe de production modifiée (environ 20 lignes), un POM, une table et une règle du garde avec leurs auto-tests, 7 constructions de test à adapter, 3 documents.

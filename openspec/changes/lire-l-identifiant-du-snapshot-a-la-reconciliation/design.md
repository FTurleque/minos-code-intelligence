# Design

## Context

Suite de `lire-l-identite-du-snapshot-actif-sans-le-charger` (AUD-PERF-01, archivé le 2026-10-15). HEAD analysé : `ae7b02b0` (`develop`). Abréviations : `APP` = `minos-application/src/main/java/com/minos/application`, `CLI` = `minos-cli/src/main/java/com/minos/cli/operations`.

## État vérifié au HEAD

### Port

- `minos-engine/src/main/java/com/minos/store/CodeKnowledgeSnapshotStore.java:36-38` : `activeSnapshotId` en méthode `default` (chargement puis identifiant).
- Redéfinie par lecture du seul pointeur actif : `FileSymbolSnapshotStore.java:187-191` (`activeSnapshotRepository.read`) et `PostgresCodeKnowledgeSnapshotStore.java:345-348` (`knowledge_active` joint à `knowledge_snapshots`, sans lire symboles ni relations).

### Réconciliateur

- `APP/ProjectIndexStateReconciler.java:79` et `:81` : `loadActive` avant et après la lecture de l'état ; `:83` ne compare que les identifiants (`sameSnapshot`, `:212-214`) ; `:90` n'en lit que `snapshotId()`.
- `:144` : troisième chargement à la vérification d'une réparation (`verifiedRepair`).
- `:180-186` : `loadActive` = `snapshotStore.loadActiveKnowledge`, échec runtime traduit en `IOException`.
- `:227-236` : record public `Reconciliation(Optional<CodeKnowledgeSnapshot> activeSnapshot, Optional<ProjectIndexState> projectState, boolean repaired)`, construit en `:87`, `:92`, `:133`, `:136`, `:146`.
- Coût par appel : `observeStatus` / `observe` cohérent = **2** chargements complets ; réparation (`reconcile`) = 2 hors bail + 2 sous bail + 1 de vérification = **5** quand l'état est en retard, 2 sinon. (Le relevé du sprint 4 disait « trois en réparation » : il comptait la seule passe sous bail.)

### Consommateurs de `Reconciliation`

| Site | Ce qui est lu | Identifiant seul ? |
|---|---|---|
| `APP/ProjectInspectionService.java:180-187` (`withIndexState`, statut et inspection) | `activeSnapshot().map(snapshotId)` `:182`, `active.isPresent()` `:186`, `projectState()` | **Oui** |
| `CLI/LocalAutonomousIndexOperations.java:355-366` (`alignedIndexState`) | `projectState()` seulement | **Oui** (le snapshot n'est pas lu) |
| `minos-bootstrap/.../ProjectIndexStateReconcilerTest.java:101`, `:212`, `:246` | `activeSnapshot().map(snapshotId)` | Oui (tests) |
| `ProjectStateReadUnderConcurrentWriteTest.java:71` | `projectState()` | Oui (test) |

Aucun autre consommateur dans le dépôt (recherche de `Reconciliation`, `observeStatus(`, `.observe(`, `reconcile(` ; les `.observe(` de `ArchitectureTechnologyService` sont une autre méthode). `ProjectIndexStateReconciler` n'appartient pas à l'API Java publiée (`minos-api`, `docs/developer/public-surfaces.md`), mais c'est un type public d'un module distribué : la consigne de l'ADR 0056 s'applique.

### Deux autres contrôles d'identité

- `APP/MinosApplicationAssembler.java:163-171` : validateur de liaison hébergée, `loadActiveKnowledge` puis comparaison de `snapshotId()` à l'identifiant lié.
- `CLI/LocalAutonomousIndexOperations.java:238-244` (`recoverPromotedRunIfNeeded`, appelé `:185` après chaque exécution) : `loadActiveKnowledge` puis comparaison à `stagedSnapshotId`.

### Gates littéraux

- `scripts/quality/check-hosted-control-plane-consistency.py:104-107` : l'assembleur doit contenir `home.resolve("hosted-control-plane")`, `hostedTenantKeyProvider`, `new HostedControlPlaneService`, `composer.hostedControlPlaneStore(` — conservés.
- `check-runtime-dynamic-consistency.py:101-102` (assembleur), `check-polyglot-provider-consistency.py:104`, `check-remote-distributed-consistency.py:61,130` et `scripts/remediation/check-post-mne.py:108,269-276` (CLI : `application.retentionService().compact(`, `compactAfterSuccess(`) : chaînes non touchées par ce changement.
- `check-remote-distributed-consistency.py:198` cite `loadActiveKnowledge` dans un **test** (`LocalRemoteIndexOperationsIntegrationTest`), non modifié.
- Aucun scope JaCoCo ne nomme les classes touchées.

**Non vérifié** : aucune durée ; aucun build lancé à la rédaction.

## Goals / Non-Goals

**Goals** : une lecture de statut et une réparation ne chargent plus le snapshot actif ; la liaison hébergée et la reprise d'un run promu non plus ; le contrat Java existant reste source- et binaire-compatible.

**Non-Goals** : retirer `Reconciliation` ; changer une sortie publique ; refondre le port (ADR 0056, SH-04).

## Decisions

### D1. Le réconciliateur ne manipule que des identifiants

`loadActive` devient une lecture de `activeSnapshotId` (même traduction des échecs runtime en `IOException`). La boucle bornée, l'encadrement de la lecture d'état par deux lectures de l'identité actif et la vérification après réparation sont **inchangés** : ils ne comparaient déjà que des identifiants. Les deux stores réels lisent le même pointeur que le chargement complet (tests de contrat existants `ActiveSnapshotIdTest` et `PostgresCodeKnowledgeSnapshotStoreTest:117-124`).

### D2. Migration additive : nouveau record, anciens contrats dépréciés

- Ajout : `public record ReconciledState(Optional<String> activeSnapshotId, Optional<ProjectIndexState> projectState, boolean repaired)` et `reconcileState(UUID)`, `observeState(UUID)`, `observeStatusState(UUID)`, de même sémantique que les méthodes actuelles.
- Conservé et `@Deprecated(since = "1.3.0")` (sans `forRemoval`) : `Reconciliation`, `reconcile`, `observe`, `observeStatus`. Chacune exécute la nouvelle lecture puis charge le snapshot **une fois** et vérifie que son identifiant est celui de l'état réconcilié ; si une promotion concurrente les a séparés, elle recommence dans la même borne de tentatives, puis échoue en `IOException` (même message qu'aujourd'hui). Résultat identique à aujourd'hui, coût ramené de 2 (ou 5) chargements à 1.
- Écartés : changer le type du composant `activeSnapshot` (constructeur et descripteurs binaires changent, interdit par l'ADR 0056) ; ajouter un composant (le constructeur canonique change) ; un accesseur paresseux (un record n'a pas d'état hors de ses composants).

### D3. Les appelants de production passent au nouveau contrat

`ProjectInspectionService.withIndexState` → `observeStatusState` ; `LocalAutonomousIndexOperations.alignedIndexState` → `reconcileState` / `observeState`. Plus aucun appel de production aux méthodes dépréciées.

### D4. Contrôles d'identité sans chargement

- Assembleur : le validateur de liaison hébergée devient une méthode statique de visibilité package, `requireExactActiveSnapshot(CodeKnowledgeSnapshotStore, UUID, String)`, qui lit `activeSnapshotId` ; messages et refus inchangés (aucun snapshot actif → `IOException` ; autre identifiant → `IOException`).
- CLI : `recoverPromotedRunIfNeeded` compare `activeSnapshotId` à `stagedSnapshotId` ; la décision est extraite en méthode statique de visibilité package pour être testée sans application complète.

### D5. La preuve est un décompte

Faux `CodeKnowledgeSnapshotStore` à compteurs (chargements complets d'un côté, lectures d'identifiant de l'autre) et faux `IndexStateStore` en mémoire, dans les modules des classes testées (`minos-application`, `minos-cli`) : couverture du nouveau code mesurée module par module. Attendus : statut cohérent 0 chargement, statut en retard 0, réparation 0, liaison hébergée 0, reprise 0, ancien contrat ≤ 1. **Rouge au HEAD** : statut 2, réparation 5 (mesurés par l'ancien contrat, seul existant au HEAD).

## Refus (fail-closed) conservés

- État persistant qui référence un snapshot alors que le store n'en a pas : `IOException` (`requireNoPersistedActiveSnapshot`).
- Lecture du store qui échoue : `IOException` qui nomme le projet.
- Identité active instable au-delà de la borne : `IOException`.
- Liaison hébergée sans snapshot actif ou sur un autre snapshot : `IOException`.

## Changement de comportement assumé

Un statut ne décode plus le corps du snapshot actif : un fichier de snapshot dont le corps est corrompu mais dont le pointeur est lisible ne fait plus échouer `minos_index_status` / `project inspect`. Le statut rend ce que le pointeur désigne ; toute requête qui charge le snapshot échoue toujours fermée sur la somme de contrôle (ADR 0006, `persistance-snapshots`). Un pointeur illisible fait toujours échouer le statut. Même raisonnement pour la liaison hébergée : la requête qui suit charge et vérifie le snapshot. Test : `ProjectIndexStateReconcilerTest.aStatusReportsThePointedSnapshotWhileItsDamagedBodyStillFailsTheLoad` (`minos-bootstrap`, store fichier réel).

## Direction des dépendances

Aucune arête nouvelle : `minos-application` et `minos-cli` appellent un port déjà présent dans `minos-engine`. `check-module-boundaries.py` reste vert.

## Qualification des capacités

- Statut et réconciliation sans chargement du snapshot : **qualifiée** par D5 (décompte), pour les deux stores (la lecture légère de chacun est déjà couverte par un test de contrat).
- Durée : **non mesurée**.

## Windows et Linux

Aucun comportement propre à une plateforme ; tests unitaires sans E/S de plateforme.

## Recouvrements avec les changements actifs

- `donner-un-contrat-d-erreur-au-port-d-etat-d-index` (AUD-QUA-03) cite les `catch (RuntimeException)` de `ProjectIndexStateReconciler` par numéro de ligne (`:154`, `:183`, `:191`, `:199`, `:207`) : ces numéros se décalent ici. Le `catch` du store de snapshots (`:183`, hors de son périmètre) devient deux (`activeSnapshotId` et `loadActiveKnowledge`), toujours hors de son périmètre. Ses tâches relisent les lignes au HEAD de leur implémentation.
- `unifier-l-orchestration-d-indexation` (AUD-ARC-02, tâche 2.2) déplace le flux de la CLI dans l'application, dont `recoverPromotedRunIfNeeded` et l'appel au réconciliateur (`:355-358`) : il reprendra `promotedSnapshotId` et `reconcileState` / `observeState`, et devra déplacer `PromotedSnapshotIdentityTest` dans `minos-application` avec la méthode (couverture SonarCloud par module).

- `hacher-l-arbre-source-une-fois-par-indexation` et `synchroniser-l-index-semantique-par-differentiel` citent des lignes de `LocalAutonomousIndexOperations` : simple dérive de numéros.

## Risks / Trade-offs

- Les méthodes dépréciées n'ont plus d'appelant de production : elles restent pour la compatibilité, couvertes par un test ; leur retrait est une décision versionnée ultérieure.
- La perte de la vérification d'intégrité au statut (voir plus haut) est le prix du gain ; elle ne touche pas les requêtes.

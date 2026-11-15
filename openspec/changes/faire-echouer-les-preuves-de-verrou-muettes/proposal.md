# Proposal

## Why

Deux constats de l'audit du 2026-11-15 (sprint 1, sévérité faible) portent sur des tests de verrou de `minos-storage` qui peuvent rester verts sans rien prouver.

- **AUD-TST-05** : le test inter-JVM du verrou de synchronisation sémantique (`FileSemanticSyncConsistencyTest`) se **saute** par `assumeTrue` quand le processus détenteur du verrou ne signale pas `READY`, et jette la sortie d'erreur de ce processus. Une régression du lanceur (classpath, classe renommée, arrêt au démarrage) rend muette la seule preuve inter-processus de ce verrou, CI comprise, sans trace de la cause.
- **AUD-TST-07** : les tests « une fermeture depuis un autre fil n'empoisonne pas le bail » vérifient que la fermeture étrangère lève une exception, mais pas que le bail est **toujours tenu** ensuite ; six `assertTrue(true)` tiennent lieu d'affirmation. Une fermeture qui libérerait le verrou avant de lever l'exception laisserait ces tests verts, et deux indexations concurrentes d'un même projet redeviendraient possibles.

Les deux constats touchent les mêmes sources de test du même module et la même famille de garanties : ils sont traités ensemble.

## What Changes

- Le test inter-JVM échoue, avec la sortie d'erreur bornée et le code de sortie du processus fils dans son message, quand le détenteur ne démarre pas ; aucun `assumeTrue` ne garde plus ce test.
- Un test prouve ce comportement : un détenteur qui ne peut pas démarrer fait échouer la preuve et non la sauter.
- Après une fermeture refusée depuis un autre fil, chaque test de bail observe qu'un concurrent ne peut pas acquérir le bail (attente observée ou échéance courte), puis qu'il l'obtient une fois le propriétaire sorti.
- Les six `assertTrue(true)` de `minos-storage` sont remplacés par des affirmations sur le bail réacquis.

Aucun code de production ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `recherche-semantique` : une exigence ajoutée (la preuve inter-processus du verrou de synchronisation échoue quand son détenteur ne démarre pas).
- `persistance-snapshots` : une exigence ajoutée (une fermeture étrangère refusée laisse le bail tenu, prouvé par un concurrent). Aucune exigence existante n'est modifiée ; `persistance-snapshots › L'attente d'un verrou de mutation est prouvée par un signal observé` reste la règle de méthode que ces tests appliquent.

## Hors périmètre

- Les autres appels `assumeTrue` du dépôt (137 au HEAD) : ils énoncent des hypothèses d'environnement et sont comptés comme sautés.
- Les délais fixes et fenêtres d'horloge d'autres tests : AUD-TST-06, sprint 6.
- Le bail PostgreSQL (`ProjectMutationIndexStateStore`) : non cité par le constat ; ses tests relèvent d'un serveur réel.
- Toute modification de `InMemoryIndexStateStore`, `FileIndexStateStore`, `ProjectIndexLease`, `SnapshotProjectLease` ou `FileSemanticVectorStore`.
- Un gate qui interdirait `assertTrue(true)` dans tout le dépôt : le décompte est nul hors de ces deux classes ; à envisager avec le gate des tests de plateforme.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Trois `assumeTrue` gardent le test inter-JVM | [Existant vérifié] | `minos-storage/src/test/java/com/minos/storage/local/store/FileSemanticSyncConsistencyTest.java:239`, `:285`, `:288` |
| La sortie d'erreur du détenteur est jetée | [Existant vérifié] | `FileSemanticSyncConsistencyTest.java:292` (`Redirect.DISCARD`) |
| Six `assertTrue(true)`, tous dans `minos-storage` | [Existant vérifié] | `ProjectLeaseOwnershipTest.java:39`, `:55`, `:71`, `:92` ; `SnapshotProjectLeaseTest.java:67`, `:88` ; recherche sur tous les `src/test` : 6 lignes |
| Aucun test n'observe le bail entre la fermeture refusée et la fermeture du propriétaire | [Existant vérifié] | `ProjectLeaseOwnershipTest.java:33-40`, `:49-56`, `:63-72` ; `SnapshotProjectLeaseTest.java:52-68` |
| Au HEAD, la fermeture étrangère lève avant toute libération | [Existant vérifié] | `InMemoryIndexStateStore.java:66-70` ; `ProjectIndexLease.java:67-71` |
| Le détenteur hérite de l'environnement du test | [Existant vérifié] | `FileSemanticSyncConsistencyTest.java:289-293` (aucun appel à `environment()`) |
| Vider l'environnement du processus fils | [Proposition] | design, D3 |

## Impact

- **Modules du reactor** : `minos-storage` (sources de test seulement).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast` ; `./mvnw -B -ntp -pl minos-storage -am test` ; scopes JaCoCo `semantic-vector-store` et `storage-codec-and-state` (aucune baisse attendue : les tests ajoutent des chemins).
- **Plateformes** : Windows et Linux ; le verrou de fichier entre processus est exercé sur les deux par la CI.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/recherche-semantique.md`, `docs/architecture/diagrams/capacites/persistance-snapshots.md`, `docs/developer/testing.md` (un test de processus fils échoue quand son fils ne démarre pas).

## Questions ouvertes

Non bloquante : D3 du design (environnement du processus détenteur), recommandation donnée.

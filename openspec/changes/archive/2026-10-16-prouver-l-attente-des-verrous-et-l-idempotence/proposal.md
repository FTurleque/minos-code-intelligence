# Proposal

## Why

Deux constats du sprint 6 de l'audit du 10 octobre 2026 portent sur des tests de persistance qui **restent verts quand la garantie disparaît** :

- **AUD-TST-09** (faible, confirmé) : trois tests d'idempotence PostgreSQL n'ont **aucune assertion**. Ils appellent deux fois `publish` ou `migrate()` et ne relisent rien : une seconde publication qui dupliquerait des lignes, ou une migration qui rejouerait une étape, resterait verte.
- **AUD-TST-10** (faible, confirmé et plus large que l'audit) : **48** `Thread.sleep` dans **33** fichiers de test. Parmi eux, **13** sites prouvent l'attente d'un verrou par un délai fixe (100 à 300 ms) suivi d'une assertion négative — 6 sur `isDone()`, 6 sur un `AtomicBoolean`, 1 sur l'état du pool — et un quatorzième affirme `!isDone()` sans rien attendre. Trois de ces sites ne sont pas cités par l'audit. Sur un runner chargé, un verrou supprimé laisse ces tests verts : le concurrent n'a simplement pas encore tourné.

Les garanties visées (publication idempotente, migration rejouable, exclusion mutuelle des mutations d'un projet) sont au cœur de la persistance des snapshots, et la règle des tests du dépôt (`.claude/rules/tests-java.md`) interdit déjà le `Thread.sleep` de synchronisation.

## What Changes

- **Idempotence relue** : après le second appel, chaque test relit l'état persisté et le compare (nombre de lignes, contenu rechargé, version du schéma).
- **Attente prouvée par un signal** : le concurrent signale son entrée dans l'attente, par le moyen propre à chaque verrou (file d'attente d'un verrou de la JVM, `pg_locks` pour un verrou consultatif PostgreSQL, loquet posé juste avant l'appel bloquant pour un verrou de fichier), avant toute assertion négative.
- **Preuve de non-vacuité** : chaque test réécrit est vu **rouge** une fois, verrou retiré localement.
- **Rejeu** : chaque test de concurrence réécrit est rejoué 50 fois avant d'être déclaré stable.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : trois exigences ajoutées (seconde publication identique sans effet sur l'état persisté ; migration rejouée laissant le schéma à la version courante ; attente d'un verrou de mutation prouvée par un signal observé).

## Hors périmètre

- Les 35 autres `Thread.sleep` des tests (simulation de durée, attente active bornée) : inventoriés en tâche 1.1, non réécrits ici.
- Un gate qui interdirait `Thread.sleep` dans les tests : un gate ancré sur une chaîne contredirait l'ADR 0043 ; non proposé.
- Tout changement du code de production des verrous ou des stores : si une réécriture révèle un défaut réel, il est consigné et traité à part.
- Les planchers de couverture (changement `borner-la-couverture-globale-et-du-coeur`).

## Impact

- **Modules du reactor** (tests seulement) : `minos-storage-postgresql`, `minos-storage-local`, `minos-integration-git`, `minos-engine`, `minos-bootstrap`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python .claude/scripts/run_gates.py --fast` ; `python scripts/quality/check-jacoco.py` sur un rapport de `verify` (scopes `m30-postgresql-pgvector`, `persistence-cache-indexes`, `semantic-vector-store`, `m25-remote-distributed-indexing`, `m27-team-hosted-control-plane`, `critical-orchestration`, `resume-orchestration`) : la couverture ne doit pas baisser.
- **Plateformes** : Ubuntu 24.04 et Windows Server 2022. Les tests PostgreSQL ne s'exécutent qu'avec Docker (Linux en CI) ; les verrous de fichier se comportent différemment sur les deux systèmes et sont rejoués sur les deux.

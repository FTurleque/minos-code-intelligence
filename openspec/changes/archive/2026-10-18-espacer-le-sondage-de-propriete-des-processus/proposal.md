# Proposal

## Why

**AUD-PERF-08** (moyenne, sprint 8 de l'audit du 10 octobre 2026 ; reprise de Q16). Le suivi de propriété d'un processus provider interroge `ProcessHandle.descendants()` toutes les **10 ms** pendant toute l'exécution : `ProcessOwnershipTracker.java:27` (`OWNERSHIP_POLL_MILLIS = 10L`), boucle `:96-104`. Un suivi est créé par processus provider (`ProcessIndexerExecutor.java:130`). `descendants()` énumère la table des processus du système : jusqu'à cent énumérations par seconde pendant chaque indexation, qui dure des minutes.

La même boucle est recopiée dans le plugin IntelliJ : `MinosProcessSupervisor.java:36` (même constante), boucle `:213-223`, pour chaque commande MINOS lancée par l'IDE (`MinosCliClient.java:149`) — donc dans le processus de l'IDE.

En production, une frontière noyau (cgroup, Job Object) tient déjà les descendants : ce sondage est une défense en profondeur. Son coût n'a **pas été mesuré**.

## What Changes

- **Mesure d'abord** : coût d'une énumération sous Windows et sous Linux, et relevé des tests qui dépendent de la cadence.
- **Sondage espacé** dans `minos-runtime-local` et dans le plugin : cadence initiale courte, puis période de régime au moins cinq fois plus longue. Le sondage n'est pas supprimé.
- **Deux implémentations, alignées** : même période, même test de cadence de chaque côté ; aucune factorisation (le plugin ne dépend d'aucun artefact `com.minos:*` et reste en Java 21).
- **Test de cadence** : un `Process` factice compte les appels de `descendants()`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : une exigence ajoutée (le suivi par sondage est espacé sans perdre un descendant observé ni masquer un échec de nettoyage).
- `client-intellij` : une exigence ajoutée (le superviseur du plugin sonde à la même cadence bornée, sans dépendance au noyau).

## Hors périmètre

- Supprimer le sondage quand une frontière noyau existe : il reste la défense en profondeur voulue (`StrongProcessOwnershipIndexerExecutor.java:149-152`).
- Factoriser le code entre `minos-runtime-local` et `minos-intellij`.
- Les autres appels de `descendants()` (arrêt d'un arbre de processus, installation d'outils), bornés et hors boucle.
- La parité du lanceur Job Object du plugin (changement actif `garantir-la-parite-du-lanceur-job-object`) et la couverture du plugin (`mesurer-la-couverture-du-plugin-intellij`).

## Impact

- **Modules du reactor** : `minos-runtime-local` (`process/ProcessOwnershipTracker`). **Hors reactor** : `minos-intellij` (`protocol/MinosProcessSupervisor`), Gradle et Java 21.
- **Surfaces publiques** : aucune sortie CLI, MCP ou API Java ne change. Plugin IntelliJ : aucun changement visible ; `docs/user/intellij-plugin.md:24` décrit déjà le sondage comme défense en profondeur.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python .claude/scripts/run_gates.py --fast`, `python scripts/architecture/check-module-boundaries.py` (plugin sans dépendance `com.minos:*`). `ProcessOwnershipTracker` n'appartient à aucun scope de `scripts/quality/check-jacoco.py` ; SonarCloud juge le nouveau code de `minos-runtime-local`. `MinosProcessSupervisorTest` est exclu de PIT (`minos-intellij/build.gradle.kts:101`).
- **Plateformes** : Windows et Linux, pour les deux implémentations (le plugin est qualifié par le check `IntelliJ plugin (gate)`).

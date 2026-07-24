# M14 — Exécution : indexation autonome et installation PROD

Statut : **EN COURS — 0/7**

Issue : **#42 — M14 — Indexation autonome et installation PROD reproductible**

## Objectif produit

M14 transforme MINOS d'un moteur local nécessitant un artefact SCIP préparé manuellement en un produit installable qui sait exécuter le bon provider et publier un snapshot en une commande :

```text
minos index <project>
```

Le parcours cible est :

```text
project add
   ↓
project discovery
   ↓
provider negotiation
   ↓
runtime diagnosis
   ↓
NONE / FULL / INCREMENTAL
   ↓
provider execution
   ↓
SCIP validation + normalization
   ↓
project staging
   ↓
atomic promotion
   ↓
READY
```

L'utilisateur ne manipule plus `index.scip` dans le parcours normal.

## Décisions de base

1. **Runtime natif principal** : la CLI, l'indexation et le MCP local utilisent les chemins et toolchains réels du poste.
2. **Docker MCP reste optionnel et durci** : il continue à servir la Code Intelligence en lecture seule, mais ne devient pas un conteneur de build universel.
3. **Aucun incrémental inventé** : tant que le provider ne porte pas `INCREMENTAL_INDEXING`, un changement déclenche `FULL`; aucune modification déclenche `NONE`.
4. **Aucune préparation destructive implicite** : MINOS ne lance jamais `npm install`, `mvn clean`, `gradle` de préparation ou autre mutation de dépendances sans contrat provider explicite.
5. **Import SCIP manuel conservé** : il devient un chemin explicite de secours/diagnostic distinct de l'indexation autonome.
6. **Promotion projet atomique** : tous les providers sélectionnés doivent réussir et être normalisés avant activation du nouveau snapshot.
7. **Une installation utilisateur ne compile pas MINOS** : les releases doivent fournir un artefact exécutable/versionné avec runtime Java embarqué.

---

## Progression

| Étape | Objet | État | Critère de sortie |
|---|---|---|---|
| M14-S1 | Runtime providers + exécution de processus | ⬜ | exécuteur générique, diagnostics, logs de run, état persistant |
| M14-S2 | Provider TypeScript autonome | ⬜ | runtime géré + executor `scip-typescript`, sans installation globale |
| M14-S3 | Provider Java autonome | ⬜ | runtime/executor `scip-java`, diagnostic JDK/build, Windows qualifié |
| M14-S4 | Normalisation/staging multi-provider | ⬜ | aucun provider ne publie directement ; assemblage puis promotion unique |
| M14-S5 | `minos index <project>` autonome | ⬜ | discovery → negotiation → plan → execute → promote via CLI |
| M14-S6 | Installation native PROD | ⬜ | distribution Windows versionnée, runtime Java embarqué, `minos doctor` |
| M14-S7 | Release + Docker alignés | ⬜ | Docker construit depuis le même artefact de release ; docs PROD finales |

L'état de ce tableau doit être mis à jour dans chaque PR M14.

---

# M14-S1 — Runtime providers et exécution

## Livrables

- `ProviderRuntimeManager` et modèles de diagnostic fournisseur-indépendants ;
- `ProcessIndexerExecutor` derrière `IndexingRuntimePorts.IndexerExecutor` ;
- workspace de run sous `<MINOS_HOME>/runs/<runId>/` ;
- capture stdout/stderr, commande effective, exit code, durée et artefact ;
- timeout et destruction de l'arbre de processus ;
- masquage minimal des variables sensibles ;
- `FileIndexStateStore` persistant pour ne pas perdre `READY/STALE/FAILED` entre processus.

## Porte

Un faux provider de test doit pouvoir produire un artefact via un vrai processus enfant, avec diagnostic reproductible et aucun type SCIP dans `orchestration`.

---

# M14-S2 — TypeScript autonome

## Livrables

- runtime géré sous `<MINOS_HOME>/tools/scip-typescript/<version>/` ;
- installation transactionnelle via npm ;
- version verrouillée et vérifiée ;
- détection `node`, `npm`, `tsconfig.json` / configuration supportée ;
- executor qui lance `scip-typescript index` dans la racine projet ;
- aucun `npm install` implicite des dépendances du projet ;
- artefact copié dans le run avant ingestion.

## Porte

Fixture TypeScript réelle : installation provider, indexation FULL, artefact SCIP lisible, import normalisé, second run sans changement → `NONE`.

---

# M14-S3 — Java autonome

## Livrables

- runtime `scip-java` versionné ;
- diagnostic du JDK projet et du build Maven qualifié ;
- priorité au Maven Wrapper du projet ;
- reprise des adaptations Windows démontrées en M0, sans dépendance au checkout MINOS ;
- conservation des logs de compilation/indexation ;
- aucun shard intermédiaire promu comme index final.

## Porte

Fixture Maven Java réelle sous Windows : `minos index` produit et promeut un snapshot ; échec de build conserve l'ancien snapshot en `STALE`.

---

# M14-S4 — Staging projet multi-provider

## Refactor

Le chemin actuel :

```text
SCIP -> normalize -> FileSymbolSnapshotStore.publish
```

devient :

```text
SCIP -> normalize -> NormalizedProviderSnapshot
                         ↓
               ProjectSnapshotAssembler
                         ↓
                  staged snapshot
                         ↓
                 atomic promotion
```

## Invariants

- provenance provider conservée ;
- collisions détectées ;
- aucun fait contradictoire fusionné silencieusement ;
- ordre déterministe ;
- activation unique après succès de tous les providers.

---

# M14-S5 — CLI autonome

## Contrat cible

```text
minos index <project>
minos index <project> --provider <id>
minos index <project> --force-full
minos index <project> --dry-run
minos index <project> --format json
```

Le chemin manuel devient :

```text
minos import-scip <project> --file <index.scip> --provider <id>
```

Une compatibilité `minos index --scip` peut être maintenue temporairement avec avertissement.

## `--dry-run`

Doit expliquer sans exécuter :

- projet/racine ;
- langages/builds détectés ;
- provider sélectionné et pourquoi ;
- runtime/provider disponible ou bloqué ;
- changements détectés ;
- plan `NONE/FULL/INCREMENTAL` et raison ;
- commande qui serait exécutée.

---

# M14-S6 — Installation native PROD

## Distribution cible Windows

```text
minos-<version>-windows-x64.zip
  bin/minos.exe (ou launcher .cmd lors du premier incrément)
  runtime/       Java 24 réduit/embarqué
  lib/minos.jar
  VERSION
```

Installation recommandée :

```text
C:\Program Files\MINOS\       programme immutable
%LOCALAPPDATA%\MINOS\         données mutables
  data/
  tools/
  cache/
  runs/
  backups/
```

Le lancement de MINOS ne dépend pas du `JAVA_HOME` utilisateur.

## Diagnostic

```text
minos doctor
minos doctor --format json
minos tools list
minos tools install <provider>
minos tools verify
```

`doctor` doit distinguer : runtime MINOS, prérequis du projet, providers gérés, build tools et Docker optionnel.

---

# M14-S7 — Release et Docker

## Livrables

- version de release non `SNAPSHOT` pour l'artefact distribuable ;
- ZIP Windows ;
- SHA-256 ;
- image Docker construite depuis l'artefact distribué, pas depuis un build différent ;
- métadonnées version/commit cohérentes entre CLI, MCP et image ;
- documentation : installation utilisateur / installation développeur / providers / Docker MCP.

## Docker

Le mode Docker conserve :

```text
network_mode: none
read_only: true
projects: read-only
MCP STDIO: read-only
```

Il reste une surface de consommation, pas le moteur de compilation des projets.

---

## Validation globale M14

M14 ne peut être déclaré terminé que si les scénarios suivants sont reproductibles :

1. installation MINOS depuis un artefact de release sans checkout source ;
2. `minos doctor` donne un diagnostic actionnable ;
3. enregistrement d'un projet local ;
4. installation/vérification d'un provider géré ;
5. `minos index <project>` produit un snapshot sans `--scip` ;
6. second run inchangé → `NONE` ;
7. changement avec provider non incrémental → `FULL` ;
8. échec de refresh → ancien snapshot toujours lisible et état `STALE` ;
9. requêtes CLI et MCP lisent exactement le snapshot actif ;
10. Docker MCP reste sans réseau et read-only ;
11. installation/upgrade conserve ou sauvegarde les données ;
12. `mvnw clean verify` sous Java 24 passe sur le SHA final.

## Hors périmètre M14

- service HTTP distant ;
- authentification réseau ;
- indexation distribuée ;
- daemon de filesystem/watch permanent ;
- installation automatique des dépendances métier d'un projet ;
- activation de `INCREMENTAL` sans qualification provider dédiée.

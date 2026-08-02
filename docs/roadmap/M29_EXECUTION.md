# M29 — Autonomous Docker Runtime & Native Parity

Statut : **EN COURS — S1/S2 qualifiés exact-head ; S3 implémenté, gate Docker réel à exécuter**  
Issue : **#107 — M29 — Autonomous Docker Runtime & Native Parity**  
Branche : **`m29-autonomous-docker-runtime`**  
Baseline : **`db33cae87b37f9c2c36e536c96a4ccb6e24df3e5` (`fix/v1.0.1-release-hardening`)**

## Objectif produit

Faire du runtime Docker MINOS un **backend autonome de premier rang**, fonctionnellement équivalent au runtime natif pour :

- administration ;
- découverte et enregistrement de projets/workspaces ;
- providers et indexation ;
- snapshots structurés ;
- persistance et récupération ;
- vector store sémantique ;
- recherche structurée, sémantique et hybride ;
- architecture, impact, related tests et ProgramGraph ;
- MCP ;
- intégrations Copilot / Claude / Codex ;
- install / upgrade / switching / uninstall.

À la fin de M29, choisir **Natif Windows** ou **Docker isolé** doit changer le lieu d'exécution, pas les capacités métier disponibles ni les résultats attendus.

## Baseline autoritative au démarrage

Audit du 2 août 2026 :

```text
main                             1adbc45339efe37cd26d1937025bfa69d7b57811
develop                          1adbc45339efe37cd26d1937025bfa69d7b57811
fix/v1.0.1-release-hardening     db33cae87b37f9c2c36e536c96a4ccb6e24df3e5
#106                             OPEN
#107                             OPEN
v1.0.1 tag/release               ABSENT / NON PUBLIÉE
branche M29 avant démarrage      ABSENTE
PR M29                           ABSENTE
```

La branche 1.0.1 est 72 commits devant `main` et contient les prérequis installer/MCP/ownership nécessaires à M29. M29 part donc de son HEAD courant, mais **ne peut pas être intégré en contournant la résolution de 1.0.1**.

Audit des triggers avant création de branche : les workflows présents sur cette baseline sont déclenchés par `pull_request`, `workflow_dispatch` ou `release`; le one-shot `release-v1.0.0.yml` avec trigger push n'existe plus sur la baseline 1.0.1. Un push sur la branche de travail M29 ne déclenche donc pas de GitHub Action dans cet état. Aucune PR/CI ne doit être déclenchée sans autorisation explicite.

## État de départ Docker

Le Docker release pré-M29 savait :

- construire une image MINOS à partir du JAR de release ;
- démarrer un conteneur durci ;
- monter les projets en lecture seule sous `/workspace/projects` ;
- persister un home Docker séparé sous `/var/lib/minos` ;
- fonctionner avec `network_mode: none` ;
- exposer une session MCP STDIO via `docker exec -i`.

Mais il n'était **pas autonome ni équivalent au natif** :

- les clients IA étaient configurés vers le MCP natif ;
- le home Docker pré-M29 était distinct de `%LOCALAPPDATA%\MINOS\data` ;
- le registre projet pré-M29 persistait des chemins physiques absolus ;
- l'image Docker n'embarquait pas tous les runtimes/providers nécessaires à l'indexation autonome ;
- les gates Docker historiques ne prouvaient pas une parité métier complète native/Docker.

## Données et vector store

MINOS possède déjà un **vector store sémantique persistant v2** :

```text
index-v2.bin
float32 vector components
```

Les snapshots structurés restent la source d'autorité et les résultats vectoriels restent `HEURISTIC`.

M29 **ne crée pas une nouvelle base vectorielle externe** par défaut. Il doit :

- rendre les stores existants portables et cohérents côté Docker ;
- préserver providerId/modelId/dimensions/stableKey/checksum ;
- reconstruire ou migrer de façon déterministe lorsque nécessaire ;
- conserver le scan exact actuel tant qu'une mesure ne justifie pas ANN ;
- ne pas introduire HNSW/Lucene/vector DB tierce sans gate `measure before optimize`.

## Principes non négociables

1. **Docker autonome** : aucun index natif préalable requis.
2. **Parité métier** : pas de sous-ensemble Docker présenté comme équivalent.
3. **Identités stables** : UUID projet/workspace indépendants du runtime.
4. **Chemins portables** : le chemin physique n'est pas une identité portable.
5. **Snapshots autoritatifs** : le vectoriel ne remplace pas le modèle structuré.
6. **Runtime offline** : préparation providers pendant build/install ; pas de téléchargement implicite en RUN.
7. **Sécurité Docker conservée** : `network_mode: none`, projets read-only pour le MCP et l'admin, filesystem read-only quand possible, `cap_drop: ALL`, `no-new-privileges`.
8. **MCP read-only** pour les agents ; administration/indexation via un plan explicite séparé.
9. **Clients IA backend-agnostic** : Copilot/Claude/Codex ne connaissent pas le détail du backend.
10. **Claim de parité interdit sans preuve comparative exact-head**.

## Architecture cible

```text
Copilot / Claude / Codex
          |
          v
     minos.exe mcp
          |
    backend selection
       /       \
      /         \
 native         docker
   |              |
MCP Java     docker exec -i
                 |
             MCP Java
```

Le plan Docker possède désormais en plus un plan d'administration/indexation éphémère :

```text
host operator
     |
     v
prod-mcp-release.ps1 -Action Admin
     |
     v
minos-admin (ephemeral)
     |
MinosLauncher
```

## Avancement

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | ✅ PASS exact-head `c7a4e944...` |
| M29-S2 | Project identity, path mapping & portable persistence | ✅ PASS exact-head `c7a4e944...` |
| M29-S3 | Autonomous Docker administration plane | 🟨 implémenté — gate Maven/Docker exact-head requis |
| M29-S4 | Provider-complete Docker image | ⬜ |
| M29-S5 | Autonomous indexing & vector lifecycle | ⬜ |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

`🟨` ne signifie pas PASS. Une étape n'est cochée ✅ qu'après preuve exact-head.

---

## M29-S1 — Backend contract & ADR

### Contrat implémenté

- enum explicite `native | docker` ;
- configuration versionnée `<MINOS_HOME>/runtime/backend.properties` ;
- `formatVersion=1` ;
- migration d'un home pré-M29 absent de configuration vers `native` **explicite** ;
- version/backend/propriété inconnus = fail-closed ;
- écriture atomique ;
- routage de `minos mcp` avant `MinosApplication.open(...)` ;
- backend natif = MCP Java in-process ;
- backend Docker = probe daemon, probe conteneur, puis `docker exec -i` ;
- timeout de probe borné ;
- interruption avec terminaison du process enfant ;
- aucun fallback Docker -> natif ;
- ADR-0037 accepté pour ce contrat ; ADR-0021 partiellement superseded.

### Preuve exact-head

Qualification Windows reçue le 2 août 2026 sur :

```text
HEAD c7a4e94414f4e2b6e3a2a23beacd303ca740387e
```

Résultats :

```text
mvnw.cmd clean verify      BUILD SUCCESS
13/13 modules             SUCCESS
McpBackendRouterTest      6/6 PASS
suite totale              417 PASS, 0 failure, 0 error, 0 skipped
check-current-docs.py     SUCCESS
```

Le défaut de compilation initial sur le contrat `NativeMcpRunner` a été corrigé avant cette qualification ; le test de propagation checked failure fait partie des 6 cas S1.

**Disposition S1 : ✅ PASS pour le contrat backend et sa qualification locale.** Les handshakes comparatifs natif/Docker complets restent couverts par les gates S3/S6/S8 et ne constituent pas encore une claim de parité.

---

## M29-S2 — Project identity, path mapping & portable persistence

### Contrat implémenté

Un mapping runtime typé/versionné a été introduit :

```text
hostRoot      ↔ containerRoot
N:\workspace-dev ↔ /workspace/projects
```

Le mapping physique vit sous :

```text
<MINOS_HOME>/runtime/project-paths.properties
```

Le registre projet, lorsqu'un mapping est actif, persiste :

```text
rootRelativePath=<racine-relative-portable>
```

et non plus un `rootPath` absolu comme identité portable.

Un ancien enregistrement `rootPath=<absolu>` :

1. conserve son UUID projet et son workspaceId ;
2. est résolu contre hostRoot ou containerRoot ;
3. reçoit une sauvegarde `.m29-v1.bak` ;
4. est remplacé atomiquement par `rootRelativePath` ;
5. peut être relu idempotemment.

Le runtime courant est explicite via `minos.runtime.location` / `MINOS_RUNTIME_LOCATION` (`native` par défaut, `docker` dans le conteneur).

### Preuve exact-head

Sur le même HEAD `c7a4e944...` :

```text
ProjectPathMappingTest    4/4 PASS
Minos Application         161/161 PASS
Storage                    42/42 PASS
build reactor              BUILD SUCCESS
```

Ces tests couvrent mapping lexical Windows/Linux, rejet hors racine, même fichier registre lu selon le runtime, conservation projectId/workspaceId, migration legacy idempotente et backup.

**Disposition S2 : ✅ PASS pour le contrat de portabilité implémenté.** La preuve process native↔Docker et la persistance réelle après recreate restent des preuves d'intégration S3/S5/S8.

---

## M29-S3 — Autonomous Docker administration plane

### Implémentation présente

Le Compose de production sépare désormais trois services :

```text
minos-mcp        persistent query plane
minos-admin      ephemeral administration/indexing plane
minos-bootstrap  ephemeral path-mapping bootstrap
```

`minos-mcp` :

- `/var/lib/minos` read-only ;
- `/workspace/projects` read-only ;
- filesystem read-only ;
- `network_mode: none` ;
- `cap_drop: ALL` ;
- `no-new-privileges:true` ;
- session MCP via `docker exec -i`.

`minos-admin` :

- exécute `com.minos.cli.MinosLauncher` ;
- `/var/lib/minos` writable explicitement ;
- projets toujours read-only ;
- filesystem conteneur read-only + tmpfs borné ;
- même `network_mode: none`, `cap_drop: ALL`, `no-new-privileges:true` ;
- conteneur éphémère via `docker compose run --rm --no-deps`.

`minos-bootstrap` :

- exécute `DockerRuntimeBootstrap` avant les opérations métier ;
- crée `project-paths.properties` via le store Java versionné ;
- est idempotent ;
- refuse de remplacer implicitement un mapping existant différent.

Le workflow packagé ajoute :

```text
-Action Admin -MinosArguments <...>
```

et valide pendant `Install`/`Validate` :

```text
docker compose config
Java image
bootstrap mapping
MinosLauncher --help dans minos-admin
```

Un wrapper source `docker/scripts/minos-docker.ps1` simplifie l'usage opérateur.

### Surface admin requise

Le plan Docker peut maintenant appeler le CLI stable pour :

```text
minos doctor
minos tools list
minos tools verify
minos project add
minos project list
minos project inspect
minos index
minos index-status
minos semantic status
minos hybrid status
minos mcp
```

M29 ajoute `semantic status` et `hybrid status` au CLI. Le premier expose l'état du vector store (`DISABLED|NO_ACTIVE_SNAPSHOT|MISSING|STALE|READY`). Le second expose `NO_ACTIVE_SNAPSHOT|READY_STRUCTURED_FALLBACK|READY_WITH_SEMANTIC` sans transformer le signal vectoriel `HEURISTIC` en fait structurel.

Documentation opérateur : `docs/user/docker-runtime.md`.

### Tests ajoutés

- `DockerRuntimeBootstrapTest` : création, idempotence, refus de remplacement implicite, usage invalide ;
- `RetrievalStatusCommandTest` : semantic READY, hybrid structured fallback, no-active-snapshot, aide stateless ;
- `M29DockerAdministrationContractTest` : séparation query/admin, data RO/RW, projets RO, sécurité Docker, bootstrap et workflow packagé.

### Gate S3 restant

Le code S3 n'est **pas encore PASS**. Il faut exécuter sur son HEAD exact :

```text
mvnw.cmd clean verify
check-current-docs.py
docker version
docker compose config
Install / Validate
projet neuf -> Docker seulement -> project add -> index -> READY
semantic status / hybrid status
Start -> MCP initialize/tools/list
restart/recreate -> état persistant
```

Le daemon Docker `desktop-linux` était arrêté lors de la dernière preuve reçue ; le CLI Docker 29.6.2 était présent mais le pipe `dockerDesktopLinuxEngine` était absent. Ce blocage est environnemental et interdit de déclarer S3 PASS tant qu'une preuve Docker réelle n'est pas fournie.

---

## M29-S4 — Provider-complete Docker image

Inventaire à qualifier contre le catalogue réel M24 :

- Java/Kotlin — `scip-java 0.13.1` ;
- TypeScript — `scip-typescript 0.4.0` ;
- Python — `scip-python 0.6.6` ;
- C/C++ — `scip-clang 0.4.0` ;
- C# — `scip-dotnet 0.2.14` ;
- Go — `scip-go 0.2.7` ;
- Rust — `rust-analyzer-scip 0.3.2989`, release `2026-07-27`, commit `12c3381`.

L'audit initial confirme que plusieurs managers actuels installent encore des artefacts à la demande (`npm`, `dotnet tool install`, `go install`, Coursier). M29 doit déplacer ces téléchargements vers BUILD/préparation contrôlée, enregistrer versions/provenance/checksums/licences/SBOM, puis exécuter offline en RUN.

Provider absent/non qualifié dans l'image = capability non revendiquée.

---

## M29-S5 — Autonomous indexing & vector lifecycle

À implémenter/qualifier : discovery, fingerprints, invalidation, `NONE|FULL|INCREMENTAL` selon capability, staging/promotion atomique, snapshot précédent conservé sur échec, recovery, vector store v2, semantic/hybrid et restart/upgrade.

Gate : premier run FULL→SUCCEEDED/READY ; second run NONE si provider qualifié ; échec provider conserve ancien snapshot ; recovery revient READY.

---

## M29-S6 — Backend-agnostic MCP client integration

Clients : Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop, Codex CLI/Desktop.

Tous continuent à cibler `app\minos.exe mcp`. Le backend est choisi par MINOS. Les protections 1.0.1 (probe réel, faux shim Copilot rejeté, backups, ownership, CLI paths, Codex TOML géré, uninstall sélectif) sont préservées.

Gate par client et backend : initialize → initialized → tools/list → requête MINOS réelle → réponse valide → shutdown/cleanup.

---

## M29-S7 — Installer, switching & lifecycle

Wizard cible après parité prouvée :

```text
Mode MCP
( ) MCP natif Windows — recommandé
( ) MCP Docker — isolation renforcée
( ) Ne pas configurer le MCP maintenant

Clients IA
[ ] Copilot JetBrains
[ ] Copilot CLI
[ ] Claude Code
[ ] Claude Desktop
[ ] Codex
```

Le choix backend est exclusif. Docker Desktop/daemon et racine projets sont validés avant activation.

Switch transactionnel : prepare → validate → handshake → switch config atomique → retire old backend si approprié. Échec = configuration active inchangée/rollback.

Qualifier install, upgrade, reinstall/repair, switch dans les deux sens, uninstall conservation données (défaut) et purge explicite.

---

## M29-S8 — Native/Docker parity qualification

Même fixture/corpus, rapport machine-readable comparant au minimum :

- registry/workspaces/index state/snapshot identity ;
- symboles, occurrences, relations, implementations, references ;
- architecture, impact, related tests, ProgramGraph, source retrieval ;
- structured/semantic/hybrid/hybrid context ;
- vector store ;
- MCP tools/list et réponses représentatives ;
- restart/recovery ;
- timings, mémoire, espace disque.

Rapport : fixture, backend, provider set, snapshot id, vector model identity, result digest, différences autorisées, mesures, PASS/FAIL.

Gate final :

```text
native result == docker result
```

aux seules différences explicitement autorisées de chemin/provenance/runtime et métriques environnementales bornées près.

Aucun claim de parité avant PASS.

---

## Relation avec 1.0.1 et #98

1.0.1 reste non publiée au démarrage M29. M29 dépend de ses prérequis installer/MCP mais ne modifie pas l'historique 1.0.x et ne peut être intégré en contournant la résolution de #106.

#98 reste indépendante : autonomie/parité Docker MCP ≠ sandbox OS réelle des workers distants.

## Gate bloquant courant

S1/S2 ont une preuve locale exact-head et sont PASS. S3 est désormais implémenté mais n'a encore aucune preuve Maven/Docker sur son nouveau HEAD.

Le dernier diagnostic Docker fourni est :

```text
Docker CLI 29.6.2 présent
context desktop-linux
Docker daemon indisponible : dockerDesktopLinuxEngine pipe absent
```

Conformément à la méthode M29 :

```text
S3 sans preuve exact-head Maven + Docker réel -> pas de PASS -> pas de S4
```

La prochaine action est donc une qualification locale exact-head avec Docker Desktop démarré. Aucune PR/CI ne doit être créée ou déclenchée pour contourner ce gate.

## Définition de terminé

M29 est terminé uniquement lorsque :

1. Docker indexe un projet neuf sans état natif ;
2. Docker restaure son état après restart ;
3. registre/snapshots/vector store sont cohérents et portables ;
4. le mapping de chemins est qualifié ;
5. tous les providers revendiqués fonctionnent sans réseau en RUN ;
6. tous les clients MCP supportés peuvent utiliser Docker ;
7. le même point d'entrée client route proprement vers les deux backends ;
8. les résultats natif/Docker passent le rapport de parité ;
9. install/switch/update/uninstall sont qualifiés ;
10. docs, ADR et guides sont alignés ;
11. aucune claim de parité n'est publiée avant preuve exact-head.

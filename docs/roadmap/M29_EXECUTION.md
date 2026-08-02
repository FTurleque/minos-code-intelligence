# M29 — Autonomous Docker Runtime & Native Parity

Statut : **EN COURS — implémentation S1/S2 présente, qualification locale exact-head requise avant S3**  
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

Le Docker release pré-M29 sait :

- construire une image MINOS à partir du JAR de release ;
- démarrer un conteneur durci ;
- monter les projets en lecture seule sous `/workspace/projects` ;
- persister un home Docker séparé sous `/var/lib/minos` ;
- fonctionner avec `network_mode: none` ;
- exposer une session MCP STDIO via `docker exec -i`.

Mais il n'est **pas encore autonome ni équivalent au natif** :

- les clients IA sont configurés vers le MCP natif ;
- le home Docker pré-M29 est distinct de `%LOCALAPPDATA%\MINOS\data` ;
- le registre projet pré-M29 persiste des chemins physiques absolus ;
- l'image Docker n'embarque pas encore tous les runtimes/providers nécessaires à l'indexation autonome ;
- les gates Docker historiques ne prouvent pas une parité métier complète native/Docker.

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
7. **Sécurité Docker conservée** : `network_mode: none`, projets read-only pour le MCP, filesystem read-only quand possible, `cap_drop: ALL`, `no-new-privileges`.
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

## Avancement

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | 🟨 implémenté — gate local non exécuté |
| M29-S2 | Project identity, path mapping & portable persistence | 🟨 implémenté partiellement — gate local non exécuté |
| M29-S3 | Autonomous Docker administration plane | ⬜ bloqué par gate S1/S2 |
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

### Tests ajoutés

- migration pré-M29 vers native ;
- configuration invalide/inconnue ;
- Docker indisponible sans appel natif ;
- ordre daemon/container/STDIO ;
- backend natif sans appel Docker.

### Gate restant

- compiler/tests JDK 24 exact-head ;
- session MCP native réelle ;
- session MCP Docker réelle ;
- Docker daemon arrêté = erreur explicite ;
- exact HEAD/worktree clean.

Tant que ces preuves ne sont pas exécutées, S1 reste 🟨.

---

## M29-S2 — Project identity, path mapping & portable persistence

### Implémentation présente

Un mapping runtime typé/versionné a été introduit :

```text
hostRoot      ↔ containerRoot
N:\workspace-dev ↔ /workspace/projects
```

Le mapping physique vit sous :

```text
<MINOS_HOME>/runtime/project-paths.properties
```

Le registre projet, lorsqu'un mapping est actif, persiste désormais :

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

Le runtime courant est explicite via `minos.runtime.location` / `MINOS_RUNTIME_LOCATION` (`native` par défaut, `docker` dans le conteneur cible).

### Tests ajoutés

- mapping lexical Windows `N:\...` ↔ Linux `/workspace/...` ;
- rejet d'un chemin hors racine ;
- même fichier registre lu en natif et Docker ;
- même `projectId` et même workspace association ;
- migration legacy idempotente ;
- backup de rollback ;
- absence de chemin hôte absolu dans l'enregistrement portable.

### Travail/gates encore requis avant ✅

- exécuter les tests exact-head sous JDK 24 ;
- qualifier les surfaces source/Git/architecture/impact/ProgramGraph/runtime observations avec racine résolue ;
- qualifier snapshots/index-state/vector store sur le home partagé ;
- vérifier migration réelle d'un home 1.0.1 ;
- vérifier rollback depuis `.m29-v1.bak` ;
- prouver même projectId/workspaceId dans des processus natif et Docker réels.

---

## M29-S3 — Autonomous Docker administration plane

Docker doit pouvoir exécuter seul :

```text
minos doctor
minos tools list
minos tools verify
minos project add
minos project list
minos project inspect
minos index
minos index-status
minos semantic/hybrid status
minos mcp
```

Le plan d'administration/indexation peut écrire explicitement `/var/lib/minos`; le serveur MCP destiné aux agents reste query-only et durci.

Gate : projet neuf -> Docker seulement -> `project add` -> `index` -> `READY`, puis restart/recreate avec registre, snapshots, index state et vector store persistants.

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

Le code S1/S2 est présent sur la branche, mais aucune preuve Maven/JDK24/Docker exact-head n'a encore été exécutée sur le SHA courant. Conformément à la méthode M29 :

```text
pas de preuve → pas de PASS → pas de passage à S3
```

La prochaine action autorisée est une qualification locale exact-head sur une machine disposant du worktree, de Java 24/Maven et de Docker Desktop. Aucune PR/CI ne doit être créée ou déclenchée pour contourner ce gate.

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

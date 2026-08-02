# Feuille de route — MINOS

Statut au **2 août 2026** : **C0 → M28 terminés et intégrés sur `main`; MINOS 1.0.0 publié; maintenance Windows 1.0.1 en préparation et non publiée; M29 en cours avec S1/S2 qualifiés, S3/S4 PASS exact-head `3df1b40...`, S5 implémenté sur un HEAD plus récent et en attente de qualification exact-head, sans claim de parité.**

L'état courant est dans [`STATUS.md`](STATUS.md). Les preuves détaillées restent sous [`roadmap/`](roadmap/), les décisions durables sous [`adr/`](adr/README.md) et les preuves historiques sous [`history/milestones/`](history/milestones/README.md).

## Principes de roadmap

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les capacités provider absentes ne sont jamais extrapolées ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier ;
- les claims remote/hosted/sandbox restent fail-closed lorsqu'ils ne sont pas prouvés ;
- une release publiée est immuable ;
- le runtime packagé doit être testé, pas seulement le JAR ;
- un backend Docker n'est équivalent au natif qu'après qualification de parité métier, données, providers, MCP et lifecycle.

## Trajectoire livrée C0 → M28

| Jalon | Résultat principal | État |
|---|---|---|
| C0 | cadrage fonctionnel et architectural | ✅ livré |
| M0 | faisabilité SCIP/Glean/backend local | ✅ livré |
| M1 | discovery projets, modules et lifecycle d'indexation | ✅ livré |
| M2 | symboles normalisés et identités stables | ✅ livré |
| M3 | références, appels, implémentations et dépendances | ✅ livré |
| M4 | recherche structurée et contexte compact | ✅ livré |
| M5 | tests liés et dérivations explicables | ✅ livré |
| M6 | intelligence d'architecture | ✅ livré |
| M7 | indexation incrémentale et fingerprints | ✅ livré |
| M8 | analyse d'impact | ✅ livré |
| M9 | CLI stabilisée | ✅ livré |
| M10 | serveur MCP STDIO read-only | ✅ livré |
| M11 | API Java publique | ✅ livré |
| M12 | multi-dépôts et intelligence Git | ✅ livré |
| M13 | export NEXUS | ✅ livré |
| M14 | indexation autonome + installation PROD Windows | ✅ livré |
| M15 | reactor multi-module | ✅ livré |
| M16 | scalabilité/performance | ✅ livré |
| M17 | plateforme discovery/providers | ✅ livré |
| M18 | client/plugin IntelliJ | ✅ livré |
| M19 | ProgramGraph / CFG / data-flow / sécurité | ✅ livré |
| M20 | recherche sémantique et hybride | ✅ livré |
| M21 | Production Integrity | ✅ #73 closed/completed |
| M22 | Advanced Provider Intelligence | ✅ livré |
| M23 | Semantic Retrieval 2.0 | ✅ livré |
| M24 | Polyglot Expansion C/C++, C#, Go, Rust | ✅ livré |
| M25 | Remote & Distributed Indexing | ✅ livré avec contraintes sandbox explicites |
| M26 | Runtime & Dynamic Intelligence | ✅ livré |
| M27 | Team / Hosted Mode | ✅ livré |
| M28 | Production Convergence | ✅ #93 CLOSED / completed ; PR #102 merged |

## Ligne de production 1.x

### 1.0.0 — première stable

État : **PUBLIÉE**.

```text
main/tag v1.0.0 : 1adbc45339efe37cd26d1937025bfa69d7b57811
PR promotion     : #102 MERGED
M21              : #73 CLOSED / completed
M28              : #93 CLOSED / completed
```

Le défaut Windows post-publication lié à `java.xml` est traité par 1.0.1, sans altérer `v1.0.0`.

### 1.0.1 — Windows release hardening

État : **EN PRÉPARATION / NON PUBLIÉE** sur `fix/v1.0.1-release-hardening`.

Objectifs : runtime dérivé avec `jdeps`, module gate, handshakes MCP réels, setup isolé, détection clients, ownership/backups, désinstallation sélective, candidate locale puis autorisation explicite avant tag/release.

Tant que M29 n'est pas qualifié, **1.0.1 ne présente pas Docker comme fonctionnellement équivalent au natif**.

## M29 — Autonomous Docker Runtime & Native Parity

Issue : **#107**  
Statut : **EN COURS depuis le 2 août 2026 — branche `m29-autonomous-docker-runtime`; S1/S2 PASS ; S3/S4 PASS exact-head `3df1b40...`; S5 provider→module/build root + vector lifecycle implémenté et en attente de `run-s5.ps1`.**  
Baseline : **`db33cae87b37f9c2c36e536c96a4ccb6e24df3e5` (`fix/v1.0.1-release-hardening`)**.  
Roadmap opérationnelle : [`roadmap/M29_EXECUTION.md`](roadmap/M29_EXECUTION.md).

### Objectif

Faire de Docker un backend **autonome**, et non un conteneur MCP dépendant d'un état natif préparé ailleurs.

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
MCP Java     Docker MCP autonome
```

Le choix de backend ne doit changer que le lieu d'exécution.

### Données et vector store

Le store existant reste :

```text
index-v2.bin
float32 vector components
exact scan
HEURISTIC semantic signal
```

M29 ne crée pas de nouvelle base vectorielle externe par défaut et n'introduit ni ANN ni HNSW. Les snapshots structurés restent autoritatifs.

### Sous-étapes

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | ✅ PASS exact-head `c7a4e944...` |
| M29-S2 | Project identity, path mapping & portable persistence | ✅ PASS exact-head `c7a4e944...` |
| M29-S3 | Autonomous Docker administration plane | ✅ PASS exact-head `3df1b40...` |
| M29-S4 | Provider-complete Docker image | ✅ PASS exact-head `3df1b40...` |
| M29-S5 | Autonomous indexing & vector lifecycle | 🟨 implémenté ; qualification exact-head requise |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

### S1/S2 — fondations qualifiées

S1 prouve le backend `native|docker`, le fail-closed et le point d'entrée stable `minos.exe mcp`.

S2 prouve l'identité portable et le mapping `N:\workspace-dev ↔ /workspace/projects` sans utiliser le chemin physique comme identité métier.

### S3 — plan Docker autonome — ✅

Le runtime sépare :

```text
minos-mcp            query persistant
minos-admin          administration/indexation éphémère
minos-bootstrap      mapping
minos-tools-bootstrap
minos-provider-probe
minos-provider-tools volume
```

Le query plane reste `network_mode: none`, état/projets/tools read-only. L'admin plane peut écrire `/var/lib/minos` et résoudre uniquement les dépendances du projet ; les sources restent read-only.

Après correction du runtime Rust, du staging scip-java, de `workspace/mvnw`, du tmp Java noexec et du host PowerShell, la qualification finale est :

```text
HEAD 3df1b40ca0daf50779596f6e955d966ed5eb4973
controlled fixture     fixtures/java/java-multi-module
index                   SUCCEEDED
index-status            READY
fingerprintPromoted     true
MCP handshake #1        SUCCESS
recreate                SUCCESS
project/snapshot state  persisted
MCP handshake #2        SUCCESS
M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS
```

### S4 — image provider-complete — ✅

Le catalogue préparé contient Java/Kotlin, TypeScript, Python, C/C++, C#, Go et Rust, avec Apache Maven 3.9.16. Le runtime utilise `minos-provider-tools` et `tools verify --all`.

Preuve exact-head :

```text
HEAD 3df1b40ca0daf50779596f6e955d966ed5eb4973
PowerShell AST preflight               SUCCESS
13/13 modules Maven                    SUCCESS
check-current-docs.py                  SUCCESS
Docker image                           31/31 FINISHED
provider probe offline                 SUCCESS
7/7 providers                          READY
doctor.ready                           true
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

### S5 — autonomous indexing & vector lifecycle — 🟨

La correction de routage distingue désormais :

```text
registeredProjectRoot
→ discovered provider module/build root
→ projectRelativeRoot
→ provider executor
→ scoped artifact
→ atomic project staging/promotion
```

Pour un reactor réellement multi-module qualifié (ex. Maven/scip-java), le provider peut rester à la racine projet. Pour TypeScript sans manifest global, il s'exécute sur chaque module réellement découvert. Les chemins SCIP relatifs au module sont préfixés vers la racine projet avant création des file IDs et identités structurelles.

Le test `IndexingLifecycleScopedExecutionTest` prouve aussi qu'un échec d'un scope imbriqué laisse le snapshot précédemment promu actif. L'incrémental multi-scope reste fail-closed tant qu'il n'est pas qualifié ; `NONE|FULL|INCREMENTAL` reste piloté par les capabilities existantes.

Fixture de promotion :

```text
fixtures/polyglot/m29-scoped-modules
root        Maven / Java
ui/app      NPM / TypeScript
ui/lib      NPM / TypeScript
root TS manifest absent par construction
```

Le workflow Docker persiste `MINOS_SEMANTIC_PROVIDER` dans `.env` et `installation.json` format 5. Les modes packagés S5 sont `disabled|local-hash`. `local-hash` est zéro-réseau et sert uniquement à qualifier le plumbing sémantique ; il n'est pas présenté comme modèle appris.

`run-s5.ps1` exécute un gate exact-head intégré :

```text
S4 exact-head avec local-hash, même image/data root
→ discovery JAVA+TYPESCRIPT / MAVEN+NPM
→ scip-java à la racine
→ scip-typescript sur ui/app et ui/lib
→ index READY + fingerprint promotion
→ semantic READY / minos-local-hash / 384 dimensions
→ index-v2.bin non vide
→ hybrid READY_WITH_SEMANTIC + limitation HEURISTIC
→ second index NONE / NO_CHANGES
→ forced FULL + nouveau snapshot + vector realignment
→ recreate query plane
→ semantic/hybrid toujours READY en read-only
→ worktree inchangé
```

Aucun PASS S5 n'est déclaré avant le marqueur :

```text
M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS
```

### S6 — clients MCP

Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop, Codex CLI/Desktop continuent tous à utiliser `minos.exe mcp` ; MINOS sélectionne le backend.

### S7 — installer / switching

Le switching natif↔Docker doit être transactionnel : prepare → validate → handshake → commit config → retrait ancien backend ; rollback en cas d'échec.

### S8 — parité

Même corpus, même configuration métier, rapport machine-readable.

Gate final :

```text
native result == docker result
```

aux seules différences explicitement permises de chemin/provenance/runtime près.

## Reliquat produit explicite — #98

#98 reste **OPEN**. La sandbox OS réelle des workers distants est indépendante de M29.

## Séquence de travail courante

1. tirer le HEAD courant de `m29-autonomous-docker-runtime` ;
2. exécuter `check-current-docs.py` ;
3. exécuter `run-s5.ps1 -ExpectedHead <HEAD> -ProjectsRoot N:\workspace-dev` ;
4. le runner réexécute S4 exact-head avec `local-hash`, conserve cette installation et exécute S5 sur la même image ;
5. si et seulement si le marqueur S5 SUCCESS est obtenu, réconcilier S5 puis démarrer S6 ;
6. aucune PR/CI/merge M29 sans autorisation explicite ;
7. aucune claim de parité avant S8.

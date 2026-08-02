# Feuille de route — MINOS

Statut au **3 août 2026** : **C0 → M28 terminés et intégrés sur `main`; MINOS 1.0.0 publié; maintenance Windows 1.0.1 en préparation et non publiée; M29 en cours avec S1–S7 PASS (S7 exact-head `50b462f...`), S8 à qualifier.**

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
Statut : **EN COURS depuis le 2 août 2026 — branche `m29-autonomous-docker-runtime`; S1/S2 PASS ; S3/S4 PASS exact-head `3df1b40...`; S5 PASS exact-head `0959fb9...`; S6 PASS exact-head `f7ef0e3...`; S7 PASS exact-head `50b462f...`; S8 à qualifier.**  
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
| M29-S5 | Autonomous indexing & vector lifecycle | ✅ PASS exact-head `0959fb9...` |
| M29-S6 | Backend-agnostic MCP client integration | ✅ PASS exact-head `f7ef0e3...` |
| M29-S7 | Installer, switching & lifecycle | ✅ PASS exact-head `50b462f...` |
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

Qualification finale :

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

### S5 — autonomous indexing & vector lifecycle — ✅

Le routage distingue :

```text
registeredProjectRoot
→ discovered provider module/build root
→ projectRelativeRoot
→ provider executor
→ scoped artifact
→ atomic project staging/promotion
```

Fixture qualifiée :

```text
fixtures/polyglot/m29-scoped-modules
root        Maven / Java
ui/app      NPM / TypeScript
ui/lib      NPM / TypeScript
root TS manifest absent par construction
```

Preuve exact-head `0959fb9f64e2ecf61e20281f29c694e86d67c62b` :

```text
13/13 modules Maven                    SUCCESS
S4 provider-complete                   SUCCESS
JAVA + TYPESCRIPT / MAVEN + NPM        détectés
scip-java                              root
scip-typescript                        ui/app + ui/lib
FULL index                             SUCCEEDED / READY
semantic                               READY / minos-local-hash / 384 / 19 docs
index-v2.bin                           présent / non vide
hybrid                                 READY_WITH_SEMANTIC / HEURISTIC
second index                           NONE / NO_CHANGES
forced FULL                            fresh snapshot + semantic realignment
query recreate                         semantic/hybrid READY
M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS
```

Le store reste `index-v2.bin`, float32 exact scan ; aucune base vectorielle externe n'est introduite.

### S6 — clients MCP backend-agnostic — ✅

Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop et Codex CLI/Desktop gardent tous le même contrat :

```text
command = <installation>\app\minos.exe
args    = mcp
env     = MINOS_HOME=<dataRoot>
```

Le choix backend est lu dans `<MINOS_HOME>/runtime/backend.properties` par `McpBackendRouter`. Les clients n'embarquent ni `docker exec`, ni nom de conteneur, ni Compose.

Qualification exact-head `f7ef0e3dbe820253decd83a1dc27bf2651ef6de9` :

```text
13/13 modules Maven                                 SUCCESS
check-current-docs.py                               SUCCESS
MCP client integration                              SUCCESS
MCP client preflight                                SUCCESS
Codex Desktop lifecycle                             SUCCESS
backend-routing native -> docker                    SUCCESS
client configs                                      byte-identical
installer template                                  SUCCESS
M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS
```

Rapport :

```text
target/m29/s6-qualification-f7ef0e3dbe820253decd83a1dc27bf2651ef6de9.json
```

### S7 — installer / switching — ✅ PASS exact-head `50b462f...`

Le switching natif↔Docker est implémenté comme une transaction :

```text
prepare -> validate -> handshake -> commit backend.properties -> retire old backend
```

`scripts/install/switch-mcp-backend.ps1` ne commit jamais le nouveau choix avant que `scripts/install/probe-mcp-backend.ps1` ait validé `initialize` puis `tools/list` via le point d'entrée stable. Un échec restaure la configuration précédente. Un vrai upgrade Docker sauvegarde aussi le runtime/marker précédent et le redémarre si le candidat échoue.

Un runtime Docker géré correspondant exactement à la version/commit, racines et identité container/Compose courants est réutilisé avec `Start + Validate + handshake`, sans seconde construction d'image.

Le setup expose trois modes exclusifs :

```text
MCP natif Windows — recommandé
MCP Docker — isolation renforcée
Ne pas configurer maintenant
```

Le backend existant est présélectionné lors d'un upgrade. Les clients IA sont communs aux deux backends. Docker explicitement choisi mais indisponible bloque le wizard ; aucun fallback silencieux vers le natif.

Le ZIP `install.ps1` utilise le même switcher, possède des backups collision-safe et restaure le payload précédent si l'installation/validation échoue.

Gate exact-head :

```text
scripts/m29/run-s7.ps1
```

Le runner exige :

```text
Maven + docs + AST                       SUCCESS
fault-injected lifecycle verifier        SUCCESS
backend-agnostic client routing          SUCCESS
installer template contract              SUCCESS
Windows distribution                     SUCCESS
Inno smoke setup compile                 SUCCESS
native-only install                      SUCCESS
native upgrade                           SUCCESS
Docker-only install                      SUCCESS
Docker -> native                         SUCCESS
native -> Docker reuse                   SUCCESS
runtime uninstall preserves data         SUCCESS
explicit isolated purge                  SUCCESS
```

Marqueur requis :

```text
M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS
```

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
2. exécuter `run-s7.ps1 -ExpectedHead <HEAD>` ;
3. ne promouvoir S7 qu'avec le marqueur exact-head SUCCESS et le rapport `target/m29/s7-qualification-<HEAD>.json` ;
4. démarrer ensuite S8 ;
5. aucune PR/CI/merge M29 sans autorisation explicite ;
6. aucun claim de parité avant S8.

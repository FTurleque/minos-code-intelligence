# Feuille de route — MINOS

Statut au **2 août 2026** : **C0 → M28 terminés et intégrés sur `main`; MINOS 1.0.0 publié; maintenance Windows 1.0.1 en préparation et non publiée; M29 en cours avec S1/S2 qualifiés, S4 provider-complete PASS exact-head `0f5668f...`, S3 en requalification sur une fixture Java Maven contrôlée, et le défaut provider→module root du monorepo polyglotte classé S5, sans claim de parité.**

L'état courant est dans [`STATUS.md`](STATUS.md). Les preuves détaillées restent sous [`roadmap/`](roadmap/), les décisions durables sous [`adr/`](adr/README.md) et les preuves historiques sous [`history/milestones/`](history/milestones/README.md).

## Principes de roadmap

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les capacités provider absentes ne sont jamais extrapolées ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier ;
- les décisions de backend sont guidées par la mesure ;
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
Statut : **EN COURS depuis le 2 août 2026 — branche `m29-autonomous-docker-runtime`; S1/S2 PASS ; S4 PASS exact-head `0f5668f...`; S3 runner corrigé pour une fixture Java Maven contrôlée ; routage provider→module root polyglotte transféré explicitement à S5.**  
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
```

M29 ne crée pas de nouvelle base vectorielle externe par défaut. Les snapshots structurés restent autoritatifs et le signal sémantique reste `HEURISTIC`.

### Sous-étapes

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | ✅ PASS exact-head `c7a4e944...` |
| M29-S2 | Project identity, path mapping & portable persistence | ✅ PASS exact-head `c7a4e944...` |
| M29-S3 | Autonomous Docker administration plane | 🟨 runner corrigé, requalification requise |
| M29-S4 | Provider-complete Docker image | ✅ PASS exact-head `0f5668f...` ; HEAD courant à requalifier |
| M29-S5 | Autonomous indexing & vector lifecycle | ⬜ inclut provider→module root polyglotte |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

### S1/S2 — fondations qualifiées

S1 prouve le backend `native|docker`, le fail-closed et le point d'entrée stable `minos.exe mcp`.

S2 prouve l'identité portable et le mapping `N:\workspace-dev ↔ /workspace/projects` sans utiliser le chemin physique comme identité métier.

### S3 — plan Docker autonome

Le runtime sépare :

```text
minos-mcp            query persistant
minos-admin          administration/indexation éphémère
minos-bootstrap      mapping
minos-tools-bootstrap
minos-provider-probe
minos-provider-tools volume
```

Le query plane reste `network_mode: none`, état et projets read-only. L'admin plane peut écrire `/var/lib/minos` et résoudre uniquement les dépendances du projet ; les sources restent read-only.

Les défauts réellement atteints et corrigés ont successivement été : runtime Rust absent, écriture `target/scip-targetroot` dans les sources, wrapper `workspace/mvnw` host-dépendant, temp Java/JNA sous tmpfs noexec.

S4 `0f5668f...` confirme Maven 3.9.16, `/run/minos-native` et les providers offline.

Le S3 sur ce même HEAD a révélé que la fixture historique — le monorepo MINOS entier — déclenche un défaut différent : `scip-typescript` reçoit la racine du projet au lieu de sa racine de module et ne trouve pas `tsconfig.json`/`package.json`.

Pour qualifier strictement S3, `run-s3.ps1` utilise désormais :

```text
minos-code-intelligence/fixtures/java/java-multi-module
```

Le gate S3 exige `index → READY`, `semantic status`, `hybrid status`, MCP initialize/tools-list et recreate/persistance.

### S4 — image provider-complete

Le catalogue préparé contient Java/Kotlin, TypeScript, Python, C/C++, C#, Go et Rust, avec Apache Maven 3.9.16.

Le runtime utilise `minos-provider-tools` et `tools verify --all`.

Preuve exact-head :

```text
HEAD 0f5668f8ea10303a5df4cffd0e79376a21979fbd
13/13 modules Maven SUCCESS
433 unit tests + 1 smoke IT PASS
check-current-docs.py SUCCESS
Docker image 31/31 FINISHED
provider probe offline SUCCESS
7/7 providers READY
doctor.ready=true
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

Le HEAD courant a avancé avec le runner et les docs ; `run-s4.ps1` doit donc être rejoué avant S3.

### S5 — autonomous indexing & vector lifecycle

S5 couvre : discovery, fingerprints, invalidation, `NONE|FULL|INCREMENTAL`, staging/promotion atomique, recovery, vector store, semantic/hybrid, restart/upgrade.

Le nouveau gate explicite est le routage :

```text
provider negotiation
→ module/build root correspondant
→ provider executor
→ promotion projet cohérente
```

pour les monorepos polyglottes.

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
2. exécuter `run-s4.ps1` exact-head ;
3. si S4 PASS, exécuter `run-s3.ps1` sur exactement le même HEAD ;
4. exiger la fixture Java contrôlée, `READY`, semantic/hybrid, MCP et recreate ;
5. seulement ensuite démarrer S5 et corriger le routage provider→module root ;
6. poursuivre S6/S7/S8 ;
7. aucune PR/CI/merge M29 sans autorisation explicite ;
8. aucune claim de parité avant S8.
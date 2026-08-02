# Feuille de route — MINOS

Statut au **2 août 2026** : **C0 → M28 terminés et intégrés sur `main`; MINOS 1.0.0 publié; maintenance Windows 1.0.1 en préparation et non publiée; M29 en cours avec S1/S2 qualifiés, S3 ayant atteint le vrai lifecycle Docker et S4 provider-complete implémenté en attente de qualification exact-head, sans claim de parité.**

L'état courant est dans [`STATUS.md`](STATUS.md). Les preuves d'exécution détaillées restent sous [`roadmap/`](roadmap/), les décisions durables sous [`adr/`](adr/README.md) et les preuves historiques sous [`history/milestones/`](history/milestones/README.md).

## Principes de roadmap

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les capacités provider absentes ne sont jamais extrapolées ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier, pas des implémentations métier parallèles ;
- les décisions de backend sont guidées par la mesure ;
- les claims remote/hosted/sandbox restent fail-closed lorsqu'ils ne sont pas prouvés ;
- une release est immuable : un défaut publié reçoit une version corrective, jamais un retag silencieux ;
- pour Windows, le binaire packagé et son runtime embarqué doivent être testés, pas seulement le JAR sur un JDK complet ;
- un backend Docker n'est déclaré équivalent au natif qu'après une qualification de parité métier, données, providers, MCP et lifecycle.

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
| M8 | analyse d'impact bornée et explicable | ✅ livré |
| M9 | CLI stabilisée | ✅ livré |
| M10 | serveur MCP STDIO read-only | ✅ livré |
| M11 | API Java publique versionnée | ✅ livré |
| M12 | multi-dépôts et intelligence Git | ✅ livré |
| M13 | export NEXUS versionné | ✅ livré |
| M14 | indexation autonome + installation PROD Windows | ✅ livré |
| M15 | reactor multi-module et industrialisation core | ✅ livré |
| M16 | qualification scalabilité/performance | ✅ livré |
| M17 | plateforme discovery/providers | ✅ livré |
| M18 | client/plugin IntelliJ | ✅ livré |
| M19 | ProgramGraph / CFG / data-flow / sécurité | ✅ livré |
| M20 | recherche sémantique et hybride | ✅ livré |
| M21 | Production Integrity & Surface Convergence | ✅ #73 closed/completed |
| M22 | Advanced Provider Intelligence | ✅ livré |
| M23 | Semantic Retrieval 2.0 | ✅ livré |
| M24 | Polyglot Expansion C/C++, C#, Go, Rust | ✅ livré |
| M25 | Remote & Distributed Indexing | ✅ livré avec contraintes sandbox explicites |
| M26 | Runtime & Dynamic Intelligence | ✅ livré avec observations partielles |
| M27 | Team / Hosted Mode embarqué | ✅ livré avec frontière no-SaaS |
| M28 | Production Convergence & Architectural Hardening | ✅ #93 closed/completed ; PR #102 merged |

Les roadmaps détaillées M15→M28 restent disponibles dans `docs/roadmap/` et ne doivent pas être réinterprétées comme état courant lorsqu'elles décrivent une étape historique de leur exécution.

## Ligne de production 1.x

### 1.0.0 — première stable

État : **PUBLIÉE**.

```text
main/tag v1.0.0 : 1adbc45339efe37cd26d1937025bfa69d7b57811
PR promotion     : #102 MERGED
M21              : #73 CLOSED / completed
M28              : #93 CLOSED / completed
```

Voir [`releases/1.0.0.md`](releases/1.0.0.md).

Un défaut post-publication du packaging Windows a été identifié : le runtime `jpackage` embarqué était sous-spécifié et pouvait manquer `java.xml`, provoquant un `NoClassDefFoundError: org/w3c/dom/Node` au bootstrap du serveur MCP. La correction n'altère pas `v1.0.0`; elle est portée par 1.0.1.

### 1.0.1 — Windows release hardening

État : **EN PRÉPARATION / NON PUBLIÉE** sur `fix/v1.0.1-release-hardening`.

Objectifs obligatoires avant publication :

1. dériver les modules du runtime avec `jdeps` depuis le JAR final ;
2. vérifier le runtime produit avec `java --list-modules` et interdire la régression `java.xml` ;
3. lancer un vrai handshake MCP sur la distribution portable ;
4. lancer le même handshake sur une installation setup isolée ;
5. empêcher les smoke tests de toucher une installation MINOS réelle ;
6. restaurer l'UX d'installation avec détection des clients MCP ;
7. capability-prober Copilot CLI, Claude Code et Codex CLI ;
8. prendre en charge Codex Desktop via sa configuration utilisateur lorsque ce mode est détecté ;
9. préserver, sauvegarder et désinstaller uniquement les configurations appartenant à MINOS ;
10. générer localement `MINOS-1.0.1-windows-x64-setup.exe` ;
11. faire valider visuellement le setup et tester la connexion MCP réelle dans Copilot avant toute publication ;
12. ne créer `v1.0.1` qu'après autorisation explicite de publication.

Tant que M29 n'est pas qualifié, **1.0.1 ne doit pas présenter Docker comme un backend fonctionnellement équivalent au natif**. Le natif reste le parcours MCP intégré/recommandé ; Docker reste un runtime isolé avancé dont la parité complète est un objectif M29 non encore acquis.

Voir [`releases/1.0.1.md`](releases/1.0.1.md) et [`user/production-installation.md`](user/production-installation.md).

## M29 — Autonomous Docker Runtime & Native Parity

Issue : **#107**  
Statut : **EN COURS depuis le 2 août 2026 — branche `m29-autonomous-docker-runtime`; S1/S2 PASS exact-head ; S3 atteint le vrai index Docker puis révèle le blocage provider ; S4 implémenté et non encore qualifié.**  
Baseline : **`db33cae87b37f9c2c36e536c96a4ccb6e24df3e5` (`fix/v1.0.1-release-hardening`)**.  
Roadmap opérationnelle : [`roadmap/M29_EXECUTION.md`](roadmap/M29_EXECUTION.md).

### Objectif

Faire de Docker un backend **autonome**, et non un simple conteneur MCP dépendant d'un état préparé ailleurs.

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

Le choix de backend ne doit changer que le lieu d'exécution. Les capacités métier, les stores, les tools MCP et les résultats attendus doivent être équivalents aux différences de chemin/provenance explicitement autorisées près.

### Données et vector store

MINOS possède déjà un vector store sémantique persistant v2 :

```text
index-v2.bin
float32 vector components
```

M29 réutilise ce store et les snapshots structurés existants. Il ne crée pas une nouvelle base vectorielle externe par défaut.

Le travail porte sur identité portable, mapping host/container, registre/index-state/snapshots cohérents, vector store déterministe et provider/model/dimensions/stableKey/checksum préservés. Aucune introduction ANN/HNSW/Lucene/vector DB tierce sans nouvelle mesure.

### Sous-étapes

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | ✅ PASS exact-head `c7a4e944...` |
| M29-S2 | Project identity, path mapping & portable persistence | ✅ PASS exact-head `c7a4e944...` |
| M29-S3 | Autonomous Docker administration plane | 🟨 plan réel prouvé jusqu'au `index`; gate final bloqué par S4 |
| M29-S4 | Provider-complete Docker image | 🟨 implémenté — qualification `run-s4.ps1` requise |
| M29-S5 | Autonomous indexing & vector lifecycle | ⬜ |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

`🟨` signifie code présent ou preuve partielle, **pas PASS**.

### S1/S2 — preuve enregistrée

Qualification Windows du 2 août 2026 :

```text
HEAD                         c7a4e94414f4e2b6e3a2a23beacd303ca740387e
mvnw.cmd clean verify        BUILD SUCCESS
13/13 modules                SUCCESS
suite totale                 417 PASS
McpBackendRouterTest         6/6 PASS
ProjectPathMappingTest       4/4 PASS
check-current-docs.py        SUCCESS
```

### S3 — preuve Docker réelle et frontière S4

Le Compose sépare `minos-mcp`, `minos-admin` et `minos-bootstrap`, avec `network_mode: none`, projets read-only, filesystem read-only, `cap_drop: ALL`, `no-new-privileges:true` et tmpfs borné. Le plan admin peut écrire l'état MINOS mais pas les sources.

Sur `b780feb7d27bd34952d1952f8d80b06755980684`, Maven/docs, Docker server/Compose, Install/Validate, mapping, `project list`, `project add` et `project inspect` ont passé. Le vrai `index` a ensuite échoué :

```text
provider runtime is not ready: rust-analyzer-scip
missing Rust runtime requirements: cargo, rustc, rust-analyzer
```

Cette preuve valide l'accès au lifecycle réel mais ne permet pas de cocher S3 : `index → READY`, MCP et recreate restent requis après S4.

### S4 — image provider-complete implémentée

Le catalogue Docker préparé est :

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989 / release 2026-07-27 / commit 12c3381
```

L'image prépare les téléchargements/toolchains pendant BUILD, produit `provider-inventory.json` et `provider-binary-sha256.txt`, puis le runtime reste `network_mode: none`. Les outils sont initialisés dans un volume Linux `minos-provider-tools` et montés read-only dans `minos-mcp` et `minos-admin`.

La CLI `tools verify --all` rend le gate capability-honest : un provider annoncé mais non `READY` fait échouer S4. Le runner exact-head `scripts/m29/run-s4.ps1` vérifie les sept providers offline ainsi que l'inventaire et les checksums.

Les process plans n'écrivent plus `index.scip` dans les sources read-only : Java, TypeScript, C/C++, C#, Go et Rust utilisent le run directory MINOS ; Python était déjà conforme. Rust redirige aussi `CARGO_TARGET_DIR` hors de la racine projet.

### Définition de parité

Docker ne sera considéré équivalent que s'il peut, **sans état natif préalable** :

```text
project add
→ provider/runtime qualification
→ index
→ READY
→ snapshots
→ vector store
→ structured/semantic/hybrid queries
→ MCP initialize/tools/list
→ requêtes réelles Copilot/Claude/Codex
→ restart/recovery
```

La qualification finale exécutera les mêmes fixtures en natif et Docker et comparera registre, snapshots, symboles, relations, source retrieval, architecture, impact, ProgramGraph, recherche sémantique/hybride, vector store, tools MCP, réponses représentatives et performances.

Gate final :

```text
native result == docker result
```

aux seules différences explicitement permises de chemin, provenance et runtime près.

### Installer cible après parité

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

La page clients devient commune aux backends. `minos.exe mcp` reste le point d'entrée stable et route vers le backend choisi. Les changements native↔Docker doivent être transactionnels avec rollback si le nouveau backend n'est pas qualifié.

## Reliquat produit explicite

### #98 — sandbox OS worker réelle

État : **OPEN**.

Le worker distant natif ne revendique toujours pas une sandbox OS pour code non fiable. `DENY` reste fail-closed lorsqu'aucun backend OS qualifié ne peut le garantir. M29 est indépendant de #98 : l'autonomie/parité Docker MCP ne constitue pas une sandbox OS des workers distants.

## Séquence de travail

1. maintenir la dépendance explicite sur le candidat 1.0.1 sans lui faire revendiquer une parité Docker inexistante ;
2. S1/S2 sont qualifiés ;
3. qualifier S4 exact-head avec `run-s4.ps1` ;
4. si S4 passe, relancer immédiatement `run-s3.ps1` sur le même HEAD jusqu'à `READY` + MCP + recreate ;
5. seulement ensuite conduire S5 puis S6/S7/S8 ;
6. ne déclarer M29 terminé qu'après le rapport de parité exact-head S8 ;
7. ne créer aucune PR/CI M29 sans autorisation explicite du mainteneur.

Aucune `v1.0.1` n'est considérée publiée tant que le tag et la GitHub Release n'existent pas après les gates manuels/automatisés autorisés.

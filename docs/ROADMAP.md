# Feuille de route — MINOS

Statut au **2 août 2026** : **C0 → M28 terminés et intégrés sur `main`; MINOS 1.0.0 publié; maintenance Windows 1.0.1 en préparation et non publiée; M29 en cours avec S1/S2 qualifiés et S3 implémenté en attente de gate Docker réel, sans claim de parité.**

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
Statut : **EN COURS depuis le 2 août 2026 — branche `m29-autonomous-docker-runtime`; S1/S2 PASS exact-head, S3 implémenté en attente de qualification.**  
Baseline : **`db33cae87b37f9c2c36e536c96a4ccb6e24df3e5` (`fix/v1.0.1-release-hardening`)**.  
Roadmap opérationnelle : [`roadmap/M29_EXECUTION.md`](roadmap/M29_EXECUTION.md).

### Objectif

Faire de Docker un backend **autonome**, et non un simple conteneur MCP dépendant d'un état préparé ailleurs.

À terme :

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

Le travail porte sur :

- identité projet/workspace indépendante du runtime ;
- mapping explicite chemins Windows ↔ chemins conteneur ;
- portabilité/migration du registre et de l'index state ;
- snapshots structurés cohérents ;
- vector store reconstruit/migré de façon déterministe ;
- provider/model/dimensions/stableKey/checksum préservés ;
- aucune introduction ANN/HNSW/Lucene/vector DB tierce sans nouvelle mesure.

### Sous-étapes

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | ✅ PASS exact-head `c7a4e944...` |
| M29-S2 | Project identity, path mapping & portable persistence | ✅ PASS exact-head `c7a4e944...` |
| M29-S3 | Autonomous Docker administration plane | 🟨 implémenté — qualification Maven/Docker requise |
| M29-S4 | Provider-complete Docker image | ⬜ |
| M29-S5 | Autonomous indexing & vector lifecycle | ⬜ |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

`🟨` signifie code présent, **pas PASS**.

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

S1 et S2 sont donc acquises pour leurs contrats implémentés. Les preuves d'intégration process native↔Docker restent dans les gates S3/S5/S8 et ne valent pas encore claim de parité.

### S3 — plan d'administration Docker implémenté

Le Compose M29 sépare :

```text
minos-mcp        query-only persistant
minos-admin      administration/indexation éphémère
minos-bootstrap  mapping host/container éphémère
```

Le plan MCP monte `/var/lib/minos` et les projets read-only. Le plan admin garde les projets read-only mais peut écrire `/var/lib/minos`. Les deux restent `network_mode: none`, filesystem read-only, `cap_drop: ALL`, `no-new-privileges:true` avec tmpfs borné.

Le bootstrap crée le mapping versionné avant l'administration et refuse un remplacement implicite conflictuel.

Le workflow Docker packagé expose :

```powershell
prod-mcp-release.ps1 -Action Admin -MinosArguments @(...)
```

Il peut appeler le CLI stable pour `doctor`, `tools list/verify`, `project add/list/inspect`, `index`, `index-status`, `semantic status` et `hybrid status`. Le MCP reste attaché au plan query-only.

Guide : [`user/docker-runtime.md`](user/docker-runtime.md).

S3 reste 🟨 car le nouveau HEAD n'a pas encore passé sa qualification Maven/Docker réelle. Le dernier hôte observé avait Docker CLI 29.6.2 mais le daemon `desktop-linux` arrêté.

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

### Runtime providers Docker

L'audit M29 du catalogue réel identifie :

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989 / release 2026-07-27 / commit 12c3381
```

Plusieurs managers actuels installent encore les providers à la demande. S4 devra déplacer les téléchargements en BUILD/préparation, tracer versions/provenance/checksums/licences/SBOM puis qualifier l'exécution sans réseau :

```text
réseau contrôlé pendant préparation
→ providers/runtimes vérifiés
→ image finale
→ network_mode: none en RUN
```

Aucun téléchargement implicite de provider n'est autorisé pendant l'indexation isolée.

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
2. S1/S2 sont qualifiés ; qualifier maintenant S3 sur le SHA exact avec Docker réel ;
3. seulement après PASS S3, démarrer S4 puis S5 ;
4. rendre Docker réellement autonome/provider-complete avant de modifier l'UX pour le présenter comme alternative équivalente ;
5. ne déclarer M29 terminé qu'après le rapport de parité exact-head S8 ;
6. ne créer aucune PR/CI M29 sans autorisation explicite du mainteneur.

Aucune `v1.0.1` n'est considérée publiée tant que le tag et la GitHub Release n'existent pas après les gates manuels/automatisés autorisés.

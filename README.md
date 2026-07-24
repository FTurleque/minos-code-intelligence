# MINOS

**MINOS** est un moteur d’intelligence du code (*Code Intelligence Engine*) conçu pour construire une compréhension structurée, persistante, interrogeable et explicable de projets logiciels.

MINOS fonctionne **localement**, reste **agnostique du langage**, indépendant des fournisseurs d’IA et découplé des moteurs d’indexation ou de stockage utilisés en interne.

MINOS n’est ni un chatbot, ni un LLM, ni un simple moteur de recherche textuelle.

Il vise notamment à répondre à des questions comme :

- Où est défini ce symbole ?
- Qui l’utilise, l’appelle, l’étend ou l’implémente ?
- De quoi dépend-il ?
- Qu’est-ce qui dépend de lui ?
- Quels tests lui sont liés ?
- Quelle est la topologie générale du projet ?
- Quels modules sont structurellement centraux dans le graphe observé ?
- Quelles technologies sont réellement détectées ?
- Le projet doit-il être réindexé entièrement ou une portée incrémentale est-elle prouvable ?
- Quels éléments et tests peuvent potentiellement être impactés par une modification, et par quel chemin ?
- Quels dépôts appartiennent au même workspace et quelles relations inter-dépôts sont réellement prouvables ?
- Quels fichiers et zones ont été modifiés récemment dans Git, à quelle fréquence et par combien d’auteurs distincts ?

## Position dans l’écosystème

```text
                       JARVIS
                    Orchestration
                         │
            ┌────────────┴────────────┐
            │                         │
            ▼                         ▼
          NEXUS                     MINOS
   Context Intelligence       Code Intelligence
            │                         │
            └────────────┬────────────┘
                         ▼
                 ALFRED / BRAINIAC
                  Agents / profils IA
```

MINOS reste autonome et ne dépend fonctionnellement ni de JARVIS, ni de NEXUS, ni d’Alfred, ni de Brainiac.

Voir [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md).

## Phase actuelle

Les jalons **C0 à M10 sont terminés, validés et livrés**.

M10 — Serveur MCP — a été fusionné via PR #32 au commit :

```text
eb042852a936ad2e62e337ee35ed8a349096e794
```

Porte finale M10 :

```text
152 sources main
77 sources test
210 / 210 tests PASS
BUILD SUCCESS
```

### M11 — API publique — VALIDÉ

M11 est intégralement implémenté et **validé localement sous Java 24** sur le head exact :

```text
fae552e8e6f2aa66c327fb80485f5bad448d7520
```

Porte acquise :

```text
154 sources main
79 sources test
214 / 214 tests PASS
BUILD SUCCESS
```

PR #34 : **Ready for review**, fusion non effectuée tant qu’elle n’est pas explicitement autorisée.

M11 introduit un contrat Java public versionné :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

La surface publique couvre projets, import SCIP explicite, symboles, usages, relations, architecture, contexte module et analyse d’impact sans exposer les modèles internes.

Replay acquis :

```text
M11 public API: version=1, project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, modules=3, impact=2, tests=1
```

Voir [`docs/m11/API.md`](docs/m11/API.md) et [`docs/m11/DECISION_M11.md`](docs/m11/DECISION_M11.md).

### M12 — Multi-dépôts et intelligence Git — IMPLÉMENTÉ

M12 est intégralement implémenté sur la branche :

```text
m12/multi-repo-git
```

Suivi : issue #35. PR Draft empilée : #36, temporairement basée sur `m11/public-api` jusqu’à fusion autorisée de M11.

M12 ajoute une API publique additive :

```text
com.minos.api.MinosMultiRepositoryApi
com.minos.api.LocalMinosMultiRepositoryApi
MULTI_REPOSITORY_CONTRACT_VERSION = 1
```

Capacités :

```text
workspaces multi-projets
résolution cross-repository sur identité fournisseur exacte et unique
inspection Git locale Java pure via JGit
historique borné
changements récents
fréquence de modification par fichier
nombre d’auteurs distincts
zones d’activité
limitations explicites
```

Principe de résolution cross-repository :

> **aucune relation n’est promue par simple nom ou `qualifiedName` ; seule une identité fournisseur exacte, unique et traçable constitue une preuve suffisante.**

Principe Git :

> **l’activité observée décrit l’historique Git ; elle n’est jamais assimilée automatiquement à une criticité métier ou à une centralité architecturale.**

Replay attendu :

```text
M12 multi-repo Git: workspace=<uuid>, projects=1, git-commits=1, files=1, exact-cross-repo=0
```

Porte locale finale encore à acquérir sur le head exact M12 :

```text
158 sources main attendues
83 sources test attendues
221 tests attendus
BUILD SUCCESS attendu
```

Voir [`docs/m12/MULTI_REPO_GIT.md`](docs/m12/MULTI_REPO_GIT.md) et [`docs/m12/DECISION_M12.md`](docs/m12/DECISION_M12.md).

## Serveur MCP local

M10 reste disponible en parallèle avec **15 tools read-only** via STDIO :

```text
minos_project_structure
minos_index_status
minos_search_code
minos_find_symbols
minos_find_usages
minos_find_implementations
minos_find_callers
minos_find_callees
minos_dependencies
minos_dependents
minos_related_tests
minos_symbol_context
minos_module_context
minos_architecture
minos_impact
```

Après :

```powershell
.\mvnw.cmd clean package
```

le build produit notamment :

```text
target/minos-code-intelligence-0.1.0-SNAPSHOT-all.jar
```

Le serveur MCP STDIO se lance avec :

```powershell
java -cp .\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar com.minos.mcp.MinosMcpServer
```

Le home MINOS est résolu dans cet ordre :

```text
-Dminos.home=<path>
MINOS_HOME=<path>
~/.minos
```

## Stack technique

```text
Langage        Java 24
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper 3.3.4 / Maven 3.9.16
SCIP           scip-java-bindings 0.9.0
MCP SDK        Java MCP SDK 2.0.0
MCP transport  STDIO local
API M11        Java in-process, contrat v1
API M12        Java in-process additive, contrat multi-repo v1
Git M12        Eclipse JGit 7.6.0.202603022253-r
Framework      Aucun framework serveur dans le cœur
```

## Fondation technique

```text
Repository / Workspace
        │
        ├── Git Intelligence (M12)
        │
        ▼
Project Discovery / Registry
        │
        ├── Workspace Intelligence (M12)
        │
        ▼
Fingerprint / Invalidation
        │
        ▼
Indexer Registry / Negotiation
        │
        ▼
Incremental Planner
  NONE / FULL / INCREMENTAL
        │
        ▼
Indexing Lifecycle / Atomic Promotion
        │
        ▼
MINOS Normalization
        │
        ▼
CodeKnowledgeStore
        │
        ├── InMemory
        ├── Lightweight local
        └── Glean optionnel
        │
        ▼
MINOS Query Services
        │
        ├── Symbol Intelligence
        ├── Relationship Intelligence
        ├── Compact Context
        ├── Related Tests
        ├── Architecture Intelligence
        └── Impact Analysis
        │
        ├───────────────┬───────────────┬────────────────────┐
        ▼               ▼               ▼                    ▼
   Stable CLI       MCP STDIO      Public Java API     Multi-Repo API
                    15 tools          DTO v1           Git + Workspace
```

Principe structurant :

> **MINOS-first, Glean-optional.**

SCIP est privilégié lorsqu’un fournisseur suffisamment fiable existe. Les contrats métier MINOS ne doivent pas dépendre des types SCIP, Glean, JGit ou d’un backend particulier.

## Principes d’architecture

- faits, dérivations et heuristiques sont distingués explicitement ;
- toute dérivation importante conserve provenance et preuves ;
- les résultats publics sont compacts et déterministes ;
- le code source complet n’est retourné que sur demande explicite ;
- les limitations d’un fournisseur ne sont jamais transformées en garanties ;
- une portée incrémentale n’est jamais exécutée sans capacité fournisseur qualifiée ;
- un doute d’invalidation provoque un fallback complet ;
- une analyse d’impact décrit des impacts **potentiels observables**, jamais une certitude runtime ;
- l’absence de chemin observé ne prouve pas l’absence d’impact ;
- la CLI, MCP et les API publiques restent des couches d’exposition ;
- stdout du serveur STDIO est réservé au protocole MCP ;
- M11 conserve une frontière DTO sans exposer les modèles ou adaptateurs internes ;
- M12 conserve une frontière DTO sans exposer JGit ni les services multi-repo internes ;
- une relation cross-repository requiert une preuve d’identité exacte et unique ;
- les métriques Git ne constituent pas une mesure automatique d’importance architecturale.

## Prochain jalon

Après validation et livraison de M12, **M13 — Intégration NEXUS** permettra à NEXUS de consommer les faits, relations, preuves et vues compactes de MINOS sans rendre MINOS dépendant de NEXUS.

## Documents de référence

- [`docs/STATUS.md`](docs/STATUS.md) — état opérationnel et porte active ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges ;
- [`docs/MVP.md`](docs/MVP.md) — MVP ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement ;
- [`docs/m8/IMPACT_ANALYSIS.md`](docs/m8/IMPACT_ANALYSIS.md) — conception M8 ;
- [`docs/m9/CLI.md`](docs/m9/CLI.md) — contrat CLI M9 ;
- [`docs/m10/MCP_SERVER.md`](docs/m10/MCP_SERVER.md) — serveur et tools MCP ;
- [`docs/m11/API.md`](docs/m11/API.md) — contrat API M11 ;
- [`docs/m11/DECISION_M11.md`](docs/m11/DECISION_M11.md) — décision M11 ;
- [`docs/m12/MULTI_REPO_GIT.md`](docs/m12/MULTI_REPO_GIT.md) — conception M12 ;
- [`docs/m12/DECISION_M12.md`](docs/m12/DECISION_M12.md) — décision M12 ;
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant d’ajouter une infrastructure ou une sémantique qui ne serait pas nécessaire à la prochaine porte de décision.

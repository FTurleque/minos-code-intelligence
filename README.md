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

**M11 — API est maintenant intégralement implémenté** sur `m11/public-api` et attend sa porte locale finale.

M11 introduit un contrat Java public versionné :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

La surface publique couvre :

```text
projets
index SCIP explicite
symboles
usages
relations
architecture
contexte module
analyse d'impact
```

Les signatures publiques n’exposent que des types JDK et des DTO `com.minos.api`. Les consommateurs ne dépendent donc ni de SCIP/Glean, ni des stores, ni de la CLI, ni du MCP, ni des modèles métier internes.

Le replay M11 attendu sur la fixture réelle TypeScript est :

```text
M11 public API: version=1, project=<uuid>, snapshot=<snapshot>, modules=3, impact=2, tests=1
```

Voir [`docs/m11/API.md`](docs/m11/API.md) et [`docs/m11/DECISION_M11.md`](docs/m11/DECISION_M11.md).

### Serveur MCP local

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
MCP SDK        Java MCP SDK 2.0.0
MCP transport  STDIO local
API M11        Java in-process, contrat v1
Framework      Aucun framework serveur dans le cœur
```

## Fondation technique

```text
Repository / Workspace
        │
        ▼
Project Discovery / Registry
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
        ├─────────────────┬─────────────────┐
        ▼                 ▼                 ▼
   Stable CLI         MCP STDIO       Public Java API
                       15 tools          DTO v1
```

Principe structurant :

> **MINOS-first, Glean-optional.**

SCIP est privilégié lorsqu’un fournisseur suffisamment fiable existe. Les contrats métier MINOS ne doivent pas dépendre des types SCIP, Glean ou d’un backend particulier.

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
- la CLI reste une couche d’exposition et ne réimplémente pas l’intelligence métier ;
- le serveur MCP reste une couche d’exposition read-only et ne réimplémente pas l’intelligence métier ;
- stdout du serveur STDIO est réservé au protocole MCP ;
- l’API publique M11 conserve une frontière DTO sans exposer les modèles ou adaptateurs internes.

## Prochain jalon — M12 après clôture M11

M12 vise le **multi-dépôts et l’intelligence Git** : résolution inter-dépôts, relations cross-repository, historique Git, fréquence de modification, changements récents et zones d’activité, sous réserve des preuves nécessaires.

## Documents de référence

- [`docs/STATUS.md`](docs/STATUS.md) — état opérationnel et porte active ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges ;
- [`docs/MVP.md`](docs/MVP.md) — MVP ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement ;
- [`docs/m7/DECISION_M7.md`](docs/m7/DECISION_M7.md) — décision M7 ;
- [`docs/m8/IMPACT_ANALYSIS.md`](docs/m8/IMPACT_ANALYSIS.md) — conception M8 ;
- [`docs/m8/DECISION_M8.md`](docs/m8/DECISION_M8.md) — décision M8 ;
- [`docs/m9/CLI.md`](docs/m9/CLI.md) — contrat CLI M9 ;
- [`docs/m9/DECISION_M9.md`](docs/m9/DECISION_M9.md) — décision M9 ;
- [`docs/m10/MCP_SERVER.md`](docs/m10/MCP_SERVER.md) — serveur et tools MCP ;
- [`docs/m10/DECISION_M10.md`](docs/m10/DECISION_M10.md) — décision M10 ;
- [`docs/m11/API.md`](docs/m11/API.md) — contrat API M11 ;
- [`docs/m11/DECISION_M11.md`](docs/m11/DECISION_M11.md) — décision M11 ;
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant d’ajouter une infrastructure ou une sémantique qui ne serait pas nécessaire à la prochaine porte de décision.

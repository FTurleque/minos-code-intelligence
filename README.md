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

Les jalons **C0 à M9 sont terminés, validés et livrés**.

M9 — CLI stabilisée — a été fusionné via PR #30 au commit :

```text
22afe31339dc3a75dc51c491a725330c6d433ecc
```

Porte finale M9 :

```text
150 sources main
75 sources test
207 / 207 tests PASS
BUILD SUCCESS
```

**M10 — Serveur MCP est maintenant intégralement implémenté** sur `m10/mcp-server` et attend sa porte locale finale.

M10 expose **15 tools read-only** via un serveur local STDIO :

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

Le serveur utilise le **SDK Java MCP officiel 2.0.0**, sans framework web. Les handlers ne recalculent pas l’intelligence métier : ils traduisent les arguments MCP vers la surface JSON M9 et délèguent au cœur MINOS.

### Lancement MCP local

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

Voir [`docs/m10/MCP_SERVER.md`](docs/m10/MCP_SERVER.md) et [`docs/m10/DECISION_M10.md`](docs/m10/DECISION_M10.md).

## Stack technique

```text
Langage        Java 24
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper 3.3.4 / Maven 3.9.16
MCP SDK        Java MCP SDK 2.0.0
MCP transport  STDIO local
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
        ├───────────────┐
        ▼               ▼
   Stable CLI       MCP STDIO
                    15 tools
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
- stdout du serveur STDIO est réservé au protocole MCP.

## Prochain jalon — M11 après clôture M10

M11 vise une **API externe** permettant à d’autres systèmes de consommer MINOS via des DTO stables, sans coupler les consommateurs au protocole MCP ni aux adaptateurs internes.

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
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant d’ajouter une infrastructure ou une sémantique qui ne serait pas nécessaire à la prochaine porte de décision.

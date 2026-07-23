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

Les jalons **C0 à M8 sont terminés, validés et livrés**.

M8 — Analyse d’impact — a été fusionné via PR #28 au commit :

```text
8147db5c246c7bad92c9b6ab21be81084dc64f59
```

Porte finale M8 :

```text
143 sources main
72 sources test
203 / 203 tests PASS
BUILD SUCCESS
```

**M9 — CLI stabilisée est maintenant intégralement implémenté** sur `m9/stable-cli` et attend sa porte locale finale.

La surface M9 comprend :

```text
minos project add
minos project list
minos project inspect
minos inspect
minos index
minos index-status
minos search
minos find-symbol
minos get-source
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
minos architecture
minos impact
```

Toutes les vues stabilisées sont scriptables en `text` ou `json`, avec codes de sortie documentés et erreurs sur `stderr`.

### Frontière de `minos index`

MINOS possède les contrats de lifecycle/indexeurs, mais le dépôt ne contient pas encore de runner de production lançant automatiquement `scip-java` ou `scip-typescript`.

M9 expose donc le chemin réellement qualifié :

```text
artefact SCIP existant
        │
        ▼
ScipSymbolSnapshotImporter
        │
        ▼
normalisation MINOS
        │
        ▼
FileSymbolSnapshotStore
```

La CLI ne transforme pas une absence d’infrastructure en capacité fictive.

Voir [`docs/m9/CLI.md`](docs/m9/CLI.md) et [`docs/m9/DECISION_M9.md`](docs/m9/DECISION_M9.md).

## Stack technique

```text
Langage        Java 24
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper 3.3.4 / Maven 3.9.16
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
        ▼
Stable CLI
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
- la CLI reste une couche d’exposition et ne réimplémente pas l’intelligence métier.

## Prochain jalon — M10 après clôture M9

M10 vise un **serveur MCP** exposant aux agents IA des outils spécialisés et compacts. La logique métier doit rester dans les services MINOS, pas dans les handlers MCP.

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
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant d’ajouter une infrastructure ou une sémantique qui ne serait pas nécessaire à la prochaine porte de décision.

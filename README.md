# MINOS

**MINOS** est un moteur d’intelligence du code (*Code Intelligence Engine*) conçu pour construire une compréhension structurée, persistante, interrogeable et explicable de projets logiciels.

MINOS est pensé pour fonctionner **localement**, être **agnostique du langage**, indépendant des fournisseurs d’IA et découplé des moteurs d’indexation ou de stockage utilisés en interne.

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

Cette vue décrit les responsabilités fonctionnelles de l’écosystème. MINOS reste autonome et ne dépend fonctionnellement ni de JARVIS, ni de NEXUS, ni d’Alfred, ni de Brainiac.

```text
CODEBASE / WORKSPACE
        │
        ▼
      MINOS
 Code Intelligence
« Je comprends le code »
        │
        ▼
      NEXUS
Context Intelligence
« Je sélectionne le bon contexte »
        │
        ▼
 AGENT / LLM / IDE
```

Voir [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md).

## Phase actuelle

Les jalons **C0 à M7 sont terminés, validés et livrés**.

M7 — Indexation incrémentale — a été clôturé via PR #26 au commit :

```text
c66382705880158b9ccac63b5662b81bf2d8d255
```

Porte finale M7 :

```text
134 sources main
69 sources test
196 / 196 tests PASS
BUILD SUCCESS
```

**M8 — Analyse d’impact est maintenant implémenté** sur une branche dédiée et attend sa porte locale finale.

M8 fournit :

- impacts directs et indirects ;
- traversée inverse des relations de dépendance observées ;
- chemins explicatifs complets ;
- profondeur et résultats bornés ;
- gestion déterministe des cycles et chemins concurrents ;
- confiance conservatrice par minimum des confiances du chemin ;
- tests potentiellement impactés via `RELATED_TEST` M5 ;
- chemin de preuve test conservé séparément du meilleur chemin général ;
- limites explicites pour dispatch dynamique, réflexion, configuration runtime, relations non résolues et entités externes ;
- `ProjectImpactQuery` / `LocalProjectImpactQuery`.

La décision M8 est préparée dans [`docs/m8/DECISION_M8.md`](docs/m8/DECISION_M8.md).

Le tableau de bord [`docs/STATUS.md`](docs/STATUS.md) indique la porte active et la [`roadmap`](docs/ROADMAP.md) décrit M9 à M13.

## Stack technique

```text
Langage        Java 24
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper 3.3.4 / Maven 3.9.16
Framework      Aucun framework serveur dans le cœur
```

La version Java suit la toolchain de référence de l’environnement de développement. Une montée de version doit être coordonnée plutôt qu’imposée uniquement à MINOS.

Le choix d’un framework pour une future API ou couche MCP reste différé jusqu’au besoin réel.

## Fondation technique

```text
Repository
    │
    ▼
Project Discovery / Registry
    │
    ▼
Fingerprint / Invalidation
    │
    ▼
IndexerRegistry
    │
    ├── SCIP Providers      ← chemin privilégié
    ├── Native Providers
    └── Specialized Providers
    │
    ▼
Incremental Planner
    │
    ├── NONE
    ├── FULL
    └── INCREMENTAL         ← seulement si capacité prouvée
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
    ├── InMemory            ← tests
    ├── Lightweight         ← chemin par défaut
    └── Glean               ← option avancée
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
- une analyse d’impact décrit des **impacts potentiels observables**, jamais une certitude runtime ;
- l’absence de chemin observé ne prouve pas l’absence d’impact ;
- les rôles architecturaux ne sont pas inventés à partir de conventions seules.

## Prochain jalon — M9 après clôture M8

M9 vise la **stabilisation de la CLI**, notamment l’exposition cohérente des capacités déjà construites : indexation, recherche, symboles, relations, tests liés, architecture et impact.

## Documents de référence

- [`docs/STATUS.md`](docs/STATUS.md) — état opérationnel et porte active ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges ;
- [`docs/MVP.md`](docs/MVP.md) — MVP ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement ;
- [`docs/architecture/overview.md`](docs/architecture/overview.md) — architecture générale ;
- [`docs/m6/DECISION_M6.md`](docs/m6/DECISION_M6.md) — décision M6 ;
- [`docs/m7/DECISION_M7.md`](docs/m7/DECISION_M7.md) — décision M7 ;
- [`docs/m8/IMPACT_ANALYSIS.md`](docs/m8/IMPACT_ANALYSIS.md) — conception M8 ;
- [`docs/m8/DECISION_M8.md`](docs/m8/DECISION_M8.md) — décision M8 ;
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant d’ajouter une infrastructure ou une sémantique qui ne serait pas nécessaire à la prochaine porte de décision.

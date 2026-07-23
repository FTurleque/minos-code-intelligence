# MINOS

**MINOS** est un moteur d’intelligence du code (*Code Intelligence Engine*) conçu
pour construire une compréhension structurée, persistante, interrogeable et
explicable de projets logiciels.

MINOS est pensé pour fonctionner **localement**, être **agnostique du langage**,
indépendant des fournisseurs d’IA et découplé des moteurs d’indexation ou de
stockage utilisés en interne.

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
- Quels éléments peuvent être impactés par une modification ?

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

Cette vue décrit les responsabilités fonctionnelles de l’écosystème. MINOS reste
autonome et ne dépend fonctionnellement ni de JARVIS, ni de NEXUS, ni d’Alfred,
ni de Brainiac.

Le flux de connaissance peut également être représenté ainsi :

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

Voir [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) pour la description détaillée.

## Phase actuelle

Les jalons **C0 à M6 sont terminés et livrés**.

**M7 — Indexation incrémentale est fonctionnellement complet** et attend sa porte
locale finale sur le head exact de la PR de clôture. Après cette porte et sa
fusion, **M8 — Analyse d’impact** devient le prochain jalon.

M7 fournit désormais :

- empreintes déterministes fichiers/projet/build ;
- `ProjectChangeSet` ajouté/modifié/supprimé/identique ;
- snapshots d’empreintes persistants associés aux snapshots d’index ;
- invalidation `NONE / PARTIAL_CANDIDATE / FULL_REQUIRED` ;
- capacité fournisseur distincte `INCREMENTAL_INDEXING` ;
- plan `NONE / FULL / INCREMENTAL` ;
- fallback projet complet si une seule sélection ne prouve pas sa capacité ;
- périmètre `changedFiles` uniquement pour une exécution incrémentale ;
- baseline fingerprint avancée uniquement si le workspace reste stable pendant le run.

Les versions actuellement épinglées :

```text
scip-java       0.13.1
scip-typescript 0.4.0
```

ne sont pas déclarées `INCREMENTAL_INDEXING`, car cette capacité n’a pas été
qualifiée pendant M0. MINOS retombe donc volontairement en `FULL` avec ces
fournisseurs au lieu d’inventer une garantie.

La décision de clôture M7 est préparée dans
[`docs/m7/DECISION_M7.md`](docs/m7/DECISION_M7.md).

Le tableau de bord [`docs/STATUS.md`](docs/STATUS.md) indique la porte active et
la [`roadmap`](docs/ROADMAP.md) décrit M8 à M13.

## Stack technique

```text
Langage        Java 24
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper 3.3.4 / Maven 3.9.16
Framework      Aucun framework serveur dans le cœur
```

La version Java suit la toolchain de référence de l’environnement de
développement. Une montée de version doit être coordonnée plutôt qu’imposée
uniquement à MINOS.

Le choix d’un framework pour une future API ou couche MCP reste différé jusqu’au
besoin réel.

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
    └── Architecture Intelligence
```

Principe structurant :

> **MINOS-first, Glean-optional.**

SCIP est privilégié lorsqu’un fournisseur suffisamment fiable existe. Les
contrats métier MINOS ne doivent pas dépendre des types SCIP, Glean ou d’un
backend particulier.

## Principes d’architecture

- faits, dérivations et heuristiques sont distingués explicitement ;
- toute dérivation importante conserve provenance et preuves ;
- les résultats publics sont compacts et déterministes ;
- le code source complet n’est retourné que sur demande explicite ;
- les limitations d’un fournisseur ne sont jamais transformées en garanties ;
- une portée incrémentale n’est jamais exécutée sans capacité fournisseur qualifiée ;
- un doute d’invalidation provoque un fallback complet ;
- les rôles architecturaux ne sont pas inventés à partir de conventions seules.

## Prochain jalon — M8

Après clôture de M7, M8 vise l’**analyse d’impact** :

- impact direct ;
- impact indirect ;
- chemins explicatifs ;
- score de confiance ;
- profondeur bornée ;
- tests potentiellement impactés ;
- limites explicites liées au comportement dynamique.

## Documents de référence

- [`docs/STATUS.md`](docs/STATUS.md) — état opérationnel et porte active ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges ;
- [`docs/MVP.md`](docs/MVP.md) — MVP ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement ;
- [`docs/architecture/overview.md`](docs/architecture/overview.md) — architecture générale ;
- [`docs/m6/DECISION_M6.md`](docs/m6/DECISION_M6.md) — décision M6 ;
- [`docs/m7/FINGERPRINTS_AND_CHANGESET.md`](docs/m7/FINGERPRINTS_AND_CHANGESET.md) — M7.1 ;
- [`docs/m7/FINGERPRINT_SNAPSHOTS.md`](docs/m7/FINGERPRINT_SNAPSHOTS.md) — M7.2 ;
- [`docs/m7/CONSERVATIVE_INVALIDATION.md`](docs/m7/CONSERVATIVE_INVALIDATION.md) — M7.3 ;
- [`docs/m7/INCREMENTAL_EXECUTION.md`](docs/m7/INCREMENTAL_EXECUTION.md) — M7.4 ;
- [`docs/m7/DECISION_M7.md`](docs/m7/DECISION_M7.md) — décision M7 ;
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant
d’ajouter une infrastructure ou une sémantique qui ne serait pas nécessaire à
la prochaine porte de décision.

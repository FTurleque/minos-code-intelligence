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

Les jalons **C0 à M6 sont terminés**. M6 — Intelligence d’architecture — est
validé localement et livré ; **M7 — Indexation incrémentale** est le prochain
jalon de la roadmap.

La dernière porte fonctionnelle M6 a validé :

```text
116 sources main
58 sources test
162 / 162 tests PASS
BUILD SUCCESS
```

M6 fournit désormais :

- topologie modules / namespaces ;
- graphe inter-modules explicable ;
- concentration des dépendances ;
- calibration des distributions ;
- centralité relative séparée en entrant / sortant ;
- technologies factuelles `JAVA`, `TYPESCRIPT`, `MAVEN`, `NPM` ;
- vue composée `ArchitectureIntelligenceView` ;
- `ProjectArchitectureQuery.getArchitectureOverview(...)` ;
- `ProjectArchitectureQuery.getModuleContext(...)`.

Le replay réel TypeScript multi-module valide notamment :

```text
packages/app -> packages/api
api : incomingRank=1, technologies=[TYPESCRIPT]
app : outgoingRank=1, technologies=[TYPESCRIPT]
root: rank=0/0, technologies=[NPM]
```

La décision de clôture est documentée dans
[`docs/m6/DECISION_M6.md`](docs/m6/DECISION_M6.md).

Le tableau de bord [`docs/STATUS.md`](docs/STATUS.md) indique la porte active et
la [`roadmap`](docs/ROADMAP.md) décrit M7 à M13.

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
IndexerRegistry
    │
    ├── SCIP Providers      ← chemin privilégié
    ├── Native Providers
    └── Specialized Providers
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
- les rôles architecturaux ne sont pas inventés à partir de conventions seules.

## Prochain jalon — M7

M7 vise l’**indexation incrémentale** :

- empreintes de fichiers ;
- empreintes projet/build ;
- détection ajout/modification/suppression ;
- snapshots d’index ;
- règles d’invalidation ;
- capacités incrémentales des fournisseurs ;
- fallback sûr vers une indexation complète.

La porte M7 doit répondre à la question suivante :

> MINOS sait-il prouver qu’une réindexation partielle est sûre et revenir à une indexation complète lorsqu’il ne peut pas le prouver ?

## Documents de référence

- [`docs/STATUS.md`](docs/STATUS.md) — état opérationnel et porte active ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges ;
- [`docs/MVP.md`](docs/MVP.md) — MVP ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement ;
- [`docs/architecture/overview.md`](docs/architecture/overview.md) — architecture générale ;
- [`docs/m5/DECISION_M5.md`](docs/m5/DECISION_M5.md) — décision M5 ;
- [`docs/m6/DECISION_M6.md`](docs/m6/DECISION_M6.md) — décision M6 ;
- [`docs/m6/ARCHITECTURE_VIEW_AND_MODULE_CONTEXT.md`](docs/m6/ARCHITECTURE_VIEW_AND_MODULE_CONTEXT.md) — vue M6 composée ;
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant
d’ajouter une infrastructure ou une sémantique qui ne serait pas nécessaire à
la prochaine porte de décision.

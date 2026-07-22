# MINOS

**MINOS** est un moteur d'intelligence du code (*Code Intelligence Engine*) conçu pour construire une compréhension structurée, persistante, interrogeable et explicable de projets logiciels.

MINOS est pensé pour fonctionner **localement**, être **agnostique du langage**, indépendant des fournisseurs d'IA et découplé des moteurs d'indexation ou de stockage utilisés en interne.

MINOS n'est ni un chatbot, ni un LLM, ni un simple moteur de recherche textuelle.

Son rôle est notamment de répondre à des questions comme :

- Où est défini ce symbole ?
- Qui l'utilise, l'appelle, l'étend ou l'implémente ?
- De quoi dépend-il ?
- Qu'est-ce qui dépend de lui ?
- Quels tests lui sont liés ?
- Quels éléments peuvent être impactés par une modification ?
- Quelle est la topologie générale du projet ?

## Position dans l'écosystème

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

Cette vue décrit les responsabilités fonctionnelles de l'écosystème. MINOS reste autonome et ne dépend fonctionnellement ni de JARVIS, ni de NEXUS, ni d'Alfred, ni de Brainiac.

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

Le projet est désormais en phase :

> **M0 — Faisabilité technique**

La phase **C0 — Cadrage fonctionnel et architectural est clôturée**.

C0 a validé :

- le cahier des charges ;
- le MVP strict ;
- le modèle de domaine minimal pour M0 ;
- le modèle des fournisseurs et capacités ;
- la stratégie de tests et de métriques ;
- **ADR-0001** — cœur agnostique du langage et de l'indexeur ;
- **ADR-0002** — SCIP comme protocole sémantique privilégié, non obligatoire ;
- **ADR-0003** — `CodeKnowledgeStore` comme frontière MINOS et principe **MINOS-first, Glean-optional** ;
- **ADR-0004** — **Java 25 LTS + Maven 3.9.x + Maven Wrapper + cœur sans framework serveur**.

M0 doit maintenant **tester les hypothèses par des expérimentations mesurables**, sans construire prématurément le produit complet.

## Stack M0

```text
Langage        Java 25 LTS
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper
Framework      Aucun framework serveur dans le cœur
```

Le choix d'un framework pour une future API ou couche MCP reste différé jusqu'au besoin réel.

## Fondation technique M0

```text
Repository
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
    ├── Lightweight         ← baseline M0
    └── Glean               ← backend avancé candidat
    │
    ▼
MINOS Query Services
```

Principe :

> **MINOS-first, Glean-optional.**

SCIP est privilégié lorsqu'un fournisseur suffisamment fiable existe. Glean doit démontrer pendant M0 une valeur suffisante pour justifier son coût opérationnel ; MINOS doit pouvoir fonctionner sans lui.

## Écosystèmes de validation M0

- **Java** — premier écosystème ;
- **TypeScript** — second écosystème ;
- **Python** — repli expérimental si nécessaire.

Dépôt Java réel principal :

```text
FTurleque/ariane-chatbot
```

## Expérimentations M0

```text
A — Qualifier scip-java
B — Baseline SCIP → MINOS sans Glean
C — SCIP → Glean → MINOS
D — Reproduire le pipeline avec TypeScript
E — Comparer backend léger et Glean
```

Les mêmes contrats MINOS et les mêmes jeux de données doivent être utilisés pour comparer les chemins techniques.

## Documents de référence

- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges validé ;
- [`docs/MVP.md`](docs/MVP.md) — MVP strict validé ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement dans l'écosystème ;
- [`docs/architecture/overview.md`](docs/architecture/overview.md) — architecture générale ;
- [`docs/architecture/MODELE_DOMAINE.md`](docs/architecture/MODELE_DOMAINE.md) — modèle de domaine validé pour M0 ;
- [`docs/architecture/INDEXEURS_CAPACITES.md`](docs/architecture/INDEXEURS_CAPACITES.md) — fournisseurs et capacités validés pour M0 ;
- [`docs/METRIQUES_VALIDATION.md`](docs/METRIQUES_VALIDATION.md) — métriques et seuils ;
- [`docs/M0_PLAN_EXPERIMENTATIONS.md`](docs/M0_PLAN_EXPERIMENTATIONS.md) — protocole M0 ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/adr/`](docs/adr/) — décisions d'architecture ;
- [`docs/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md`](docs/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md) — comparatif des fondations.

## Règle de développement M0

> **Mesurer avant d'industrialiser.**

M0 doit produire des preuves techniques, des profils de qualité et des décisions documentées. Toute infrastructure non nécessaire à une expérimentation doit être différée.

# MINOS

**MINOS** est un moteur d'intelligence du code (*Code Intelligence Engine*) conçu pour construire une compréhension structurée, persistante, interrogeable et explicable de projets logiciels.

MINOS est pensé pour fonctionner **localement**, être **agnostique du langage**, indépendant des fournisseurs d'IA et découplé des moteurs d'indexation ou de stockage utilisés en interne.

MINOS n'est ni un chatbot, ni un LLM, ni un simple moteur de recherche textuelle.

Son rôle est de répondre à des questions comme :

- Où est défini ce symbole ?
- Qui l'utilise, l'appelle, l'étend ou l'implémente ?
- De quoi dépend-il ?
- Qu'est-ce qui dépend de lui ?
- Quels tests lui sont liés ?
- Quels éléments peuvent être impactés par une modification ?
- Quelle est la topologie générale du projet ?

## Position dans l'écosystème

MINOS s'inscrit dans un écosystème plus large composé notamment de JARVIS, NEXUS, Alfred et Brainiac.

Vue d'écosystème candidate :

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

Cette vue décrit les responsabilités fonctionnelles de l'écosystème. MINOS doit néanmoins rester autonome et ne dépendre fonctionnellement ni de JARVIS, ni de NEXUS, ni d'Alfred, ni de Brainiac.

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

Les responsabilités sont volontairement séparées :

- **MINOS** comprend et structure le code, les symboles, les relations, les dépendances et les preuves associées.
- **NEXUS** sélectionne et classe les informations à injecter dans le contexte d'une IA pour une tâche donnée.
- **JARVIS** est envisagé comme couche d'orchestration de l'écosystème.
- **Alfred** et **Brainiac** représentent des agents ou profils spécialisés pouvant consommer les capacités disponibles.

MINOS doit fonctionner indépendamment de NEXUS et des autres consommateurs.

Voir [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) pour la description détaillée de cette répartition.

## Phase actuelle

Le projet est actuellement en phase :

> **C0 — Cadrage fonctionnel et architectural**

Le **cahier des charges MINOS est validé**. C0 reste ouvert jusqu'à validation du périmètre final du MVP, de la stack initiale, des seuils mesurables et des derniers éléments de préparation de M0.

Aucune implémentation fonctionnelle importante ne doit commencer avant la clôture de C0.

Aucun choix définitif de version Java, de système de build ou de framework serveur n'est engagé à ce stade.

La règle actuelle est :

> **Documenter d'abord, décider ensuite, implémenter en dernier.**

### Décisions déjà validées

- cahier des charges fonctionnel ;
- séparation MINOS / NEXUS / JARVIS / agents ;
- local-first ;
- résultats compacts et explicables ;
- cœur agnostique du langage ;
- cœur agnostique de l'indexeur ;
- **ADR-0001** — cœur agnostique du langage et de l'indexeur ;
- **ADR-0002** — SCIP comme protocole sémantique privilégié, mais non obligatoire ;
- **ADR-0003** — `CodeKnowledgeStore` comme frontière MINOS ; Glean reste un backend avancé optionnel ;
- chemin de fonctionnement MINOS sans Glean obligatoire pour M0.

### Décisions encore ouvertes

- forme finale du modèle de domaine minimal ;
- backend léger de référence ;
- niveau d'adoption de Glean après mesures M0 ;
- périmètre final du MVP ;
- stack initiale ;
- seuils définitifs de qualité et de performance.

### Écosystèmes M0 retenus

- **Java** comme écosystème principal ;
- **TypeScript** comme second écosystème de validation ;
- **Python** comme repli si un blocage de l'indexeur TypeScript empêche une validation représentative.

Le dépôt réel Java principal prévu pour M0 est `FTurleque/ariane-chatbot`.

## Documents de référence

La source de vérité fonctionnelle est :

- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — **validé** ;
- [`docs/VALIDATION_CAHIER_DES_CHARGES.md`](docs/VALIDATION_CAHIER_DES_CHARGES.md) — trace de validation.

Documents complémentaires :

- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — place de MINOS dans l'écosystème JARVIS / NEXUS / Alfred / Brainiac ;
- [`docs/architecture/overview.md`](docs/architecture/overview.md) — proposition d'architecture interne ;
- [`docs/architecture/MODELE_DOMAINE.md`](docs/architecture/MODELE_DOMAINE.md) — proposition de modèle de domaine minimal ;
- [`docs/architecture/INDEXEURS_CAPACITES.md`](docs/architecture/INDEXEURS_CAPACITES.md) — modèle de fournisseurs et de capacités ;
- [`docs/MVP.md`](docs/MVP.md) — définition proposée du MVP ;
- [`docs/PLAN.md`](docs/PLAN.md) — plan de travail ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/M0_PLAN_EXPERIMENTATIONS.md`](docs/M0_PLAN_EXPERIMENTATIONS.md) — protocole détaillé des expérimentations M0 ;
- [`docs/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md`](docs/research/COMPARATIF_FONDATIONS_CODE_INTELLIGENCE.md) — comparaison SCIP, Glean, Kythe, Joern et baseline légère ;
- [`docs/adr/`](docs/adr/) — décisions d'architecture ;
- [`docs/research/`](docs/research/) — études techniques ;
- [`docs/AUDIT_COHERENCE_C0.md`](docs/AUDIT_COHERENCE_C0.md) — audit d'alignement entre les échanges de cadrage et le dépôt.

## Fondation technique retenue pour M0

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
    │
    ├── CLI
    ├── MCP
    └── API
```

Le principe est désormais :

> **MINOS-first, Glean-optional.**

SCIP est retenu comme protocole d'interopérabilité sémantique privilégié lorsqu'un fournisseur suffisamment fiable existe. Glean sera évalué pour ses capacités avancées, mais MINOS doit démontrer pendant M0 qu'il peut fonctionner sans lui.

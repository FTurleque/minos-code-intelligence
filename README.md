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

Aucune implémentation fonctionnelle importante ne doit commencer avant validation du besoin, du périmètre du MVP, des décisions d'architecture et des critères de validation.

Aucun choix définitif de version Java, de système de build ou de framework serveur n'est engagé à ce stade.

La règle actuelle est :

> **Documenter d'abord, décider ensuite, implémenter en dernier.**

## Documents de référence

La source de vérité du cadrage est :

- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md)

Documents complémentaires :

- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — place de MINOS dans l'écosystème JARVIS / NEXUS / Alfred / Brainiac ;
- [`docs/architecture/overview.md`](docs/architecture/overview.md) — proposition d'architecture interne ;
- [`docs/MVP.md`](docs/MVP.md) — définition proposée du MVP ;
- [`docs/PLAN.md`](docs/PLAN.md) — plan de travail ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/adr/`](docs/adr/) — décisions d'architecture proposées ou validées ;
- [`docs/research/`](docs/research/) — études techniques ;
- [`docs/AUDIT_COHERENCE_C0.md`](docs/AUDIT_COHERENCE_C0.md) — audit d'alignement entre les échanges de cadrage et le dépôt.

## Orientation technique à étudier

L'orientation actuelle, encore à valider, est de :

- réutiliser fortement les indexeurs sémantiques existants ;
- privilégier SCIP comme protocole d'interopérabilité lorsque pertinent ;
- évaluer Glean comme backend principal de faits et de requêtes sur le code ;
- conserver un domaine MINOS indépendant de SCIP et de Glean ;
- exposer à terme les capacités via CLI, MCP et API.

Ces orientations sont des hypothèses de travail. Elles ne deviendront des décisions qu'après validation du cadrage C0 et, lorsque nécessaire, expérimentation pendant M0.

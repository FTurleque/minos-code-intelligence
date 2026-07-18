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

MINOS doit fonctionner indépendamment de NEXUS.

## Phase actuelle

Le projet est actuellement en phase :

> **C0 — Cadrage fonctionnel et architectural**

Aucune implémentation fonctionnelle importante ne doit commencer avant validation du besoin, du périmètre du MVP, des décisions d'architecture et des critères de validation.

La règle actuelle est :

> **Documenter d'abord, décider ensuite, implémenter en dernier.**

## Documents de référence

La source de vérité du cadrage est :

- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md)

Documents complémentaires :

- [`docs/architecture/overview.md`](docs/architecture/overview.md) — proposition d'architecture ;
- [`docs/PLAN.md`](docs/PLAN.md) — plan de travail ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/MVP.md`](docs/MVP.md) — définition du MVP ;
- [`docs/adr/`](docs/adr/) — décisions d'architecture proposées ou validées ;
- [`docs/research/`](docs/research/) — études techniques.

## Orientation technique à étudier

L'orientation actuelle, encore à valider, est de :

- réutiliser fortement les indexeurs sémantiques existants ;
- privilégier SCIP comme protocole d'interopérabilité lorsque pertinent ;
- évaluer Glean comme backend principal de faits et de requêtes sur le code ;
- conserver un domaine MINOS indépendant de SCIP et de Glean ;
- exposer à terme les capacités via CLI, MCP et API ;
- optimiser les réponses pour les agents IA en retournant des informations compactes plutôt que des fichiers complets.

## Principes directeurs

1. **Agnostique du langage** — aucun langage ne doit être codé en dur dans le cœur.
2. **Agnostique de l'indexeur** — SCIP est une option privilégiée, pas une obligation.
3. **Glean fortement réutilisé, mais non imposé au domaine** — l'infrastructure doit rester remplaçable derrière une abstraction MINOS.
4. **Fondé sur les preuves** — les heuristiques doivent exposer leur origine et leur niveau de confiance.
5. **Local-first** — aucune donnée envoyée vers le cloud par défaut.
6. **Efficace en tokens** — réponses compactes par défaut.
7. **Extensible** — nouveaux langages, indexeurs et moteurs d'analyse ajoutables sans réécrire le cœur.

## Licence

Aucune licence publique n'est encore choisie. Le dépôt est actuellement privé. La stratégie open source et les licences des dépendances structurantes seront étudiées avant toute publication.
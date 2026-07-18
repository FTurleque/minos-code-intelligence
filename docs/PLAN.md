# Plan de travail — MINOS

Statut : **Brouillon de cadrage**

Ce plan organise le travail de MINOS en commençant par une phase de définition complète du besoin avant toute implémentation fonctionnelle importante.

La source de vérité fonctionnelle est le [`CAHIER_DES_CHARGES.md`](CAHIER_DES_CHARGES.md).

## Principe de travail

> **Documenter d'abord, décider ensuite, implémenter en dernier.**

Les expérimentations techniques ne doivent être lancées qu'une fois leur objectif, leurs critères de réussite et les décisions qu'elles doivent éclairer clairement définis.

---

## C0 — Cadrage fonctionnel et architectural

### Objectif

Définir précisément ce que MINOS doit être, ce qu'il ne doit pas être, sa valeur propre, son périmètre initial et ses contraintes.

### Axe 1 — Vision et positionnement

À valider :

- définition de MINOS ;
- problème résolu ;
- utilisateurs et consommateurs ;
- frontière MINOS / NEXUS ;
- rôle dans l'écosystème JARVIS / Alfred / Brainiac ;
- valeur spécifique par rapport aux solutions existantes.

### Axe 2 — Cas d'usage

Définir et prioriser :

- recherche de symboles ;
- recherche d'usages ;
- implémentations ;
- appelants / appelés ;
- dépendances / dépendants ;
- tests associés ;
- architecture ;
- analyse d'impact ;
- contexte compact pour agents IA.

Pour chaque cas d'usage :

- entrée ;
- sortie attendue ;
- niveau de précision ;
- preuves requises ;
- gestion de l'incertitude ;
- priorité MVP ou future.

### Axe 3 — Périmètre MVP

Définir précisément :

- les fonctionnalités obligatoires ;
- les fonctionnalités différées ;
- les langages de validation ;
- les types de projets de validation ;
- les critères mesurables de réussite ;
- les critères d'arrêt ou de révision.

### Axe 4 — Modèle de domaine

Concevoir sans implémenter prématurément :

```text
Project
Workspace
Module
SourceFile
Symbol
SymbolLocation
Relationship
Evidence
IndexSnapshot
```

Points à trancher :

- identité stable des symboles ;
- méthodes surchargées ;
- symboles externes ;
- références non résolues ;
- provenance ;
- confiance ;
- relations factuelles et dérivées.

### Axe 5 — Stratégie d'indexation

Étudier :

- SCIP ;
- indexeurs SCIP disponibles ;
- indexeurs Glean natifs ;
- LSIF ;
- LSP ;
- AST / compilateurs ;
- CPG / Joern et moteurs spécialisés.

Objectif : définir un modèle `IndexerProvider` fondé sur les capacités.

### Axe 6 — Stratégie Glean

Décider si Glean doit être :

- le backend par défaut ;
- un backend de référence ;
- un backend réservé à certains projets ;
- remplacé par une autre solution.

À étudier :

- intégration locale ;
- Windows / Linux / macOS ;
- communication avec Java ;
- complexité opérationnelle ;
- performances ;
- stockage ;
- maintenance ;
- reconstruction ;
- licences.

### Axe 7 — Abstraction de stockage

Définir conceptuellement :

```text
CodeKnowledgeStore
```

Le contrat doit être dérivé des cas d'usage MINOS et non de l'API Glean.

### Axe 8 — Sécurité et local-first

Définir :

- comportement par défaut ;
- données autorisées à sortir du poste ;
- exclusions ;
- `.minosignore` ;
- gestion des dépôts privés ;
- secrets ;
- fonctionnement hors ligne.

### Axe 9 — Critères de validation

Définir avant les spikes :

- précision des symboles ;
- précision des références ;
- qualité des relations ;
- temps d'indexation ;
- latence des requêtes ;
- taille des index ;
- empreinte mémoire ;
- comportement en cas de dépendances manquantes ;
- critères d'acceptation de Glean ;
- critères d'acceptation des indexeurs SCIP.

### Livrables C0

- cahier des charges validé ;
- modèle de domaine proposé ;
- périmètre MVP validé ;
- architecture cible validée à haut niveau ;
- ADR structurantes acceptées ;
- matrice d'évaluation SCIP / Glean ;
- plan des expérimentations ;
- roadmap mise à jour.

### Condition de sortie C0

> Aucun développement fonctionnel significatif ne commence tant que les principaux éléments de cadrage ne sont pas validés.

---

## M0 — Faisabilité technique

M0 commence uniquement après validation de C0.

### Objectif

Vérifier par des expérimentations mesurables que les choix retenus pendant C0 sont techniquement viables.

### Expérimentations envisagées

#### SCIP sur un projet Java réel

Vérifier :

- classes ;
- interfaces ;
- méthodes ;
- surcharges ;
- définitions ;
- références ;
- implémentations ;
- multi-module Maven ;
- comportement sur Quarkus/CDI si pertinent.

#### SCIP sur un second écosystème

Objectif : prouver que le pipeline MINOS n'est pas Java-centric.

Le second langage sera choisi pendant C0 selon la qualité des indexeurs disponibles.

#### Glean

Vérifier :

- installation locale ;
- ingestion SCIP ;
- définition de symbole ;
- références ;
- implémentations ;
- relations disponibles ;
- coût de démarrage ;
- consommation disque ;
- complexité d'intégration avec Java.

#### Abstraction MINOS

Valider :

```text
CodeKnowledgeStore
IndexerProvider
IndexerRegistry
IndexerCapabilities
```

Aucun type Glean ou SCIP ne doit traverser les frontières publiques du domaine MINOS.

### Premier vertical slice envisagé

```text
Dépôt réel
    │
    ▼
Indexeur sémantique
    │
    ▼
SCIP ou autre format fournisseur
    │
    ▼
Ingestion
    │
    ▼
Backend de connaissance
    │
    ▼
Contrats MINOS
    │
    ├── find_symbol
    └── find_usages
```

### Décision de sortie M0

À l'issue des expérimentations :

```text
ADOPTER
ADOPTER_AVEC_CONTRAINTES
REVOIR
REMPLACER
```

La décision doit être documentée par des mesures et une ADR.

---

## Travaux explicitement différés

Pendant C0 et M0, ne pas dériver vers :

- API REST de production ;
- serveur MCP complet ;
- intégration NEXUS ;
- embeddings ;
- base vectorielle ;
- plugins IDE ;
- ingestion GitHub/GitLab distante ;
- analyse d'impact complète ;
- analyse runtime complète ;
- support exhaustif de tous les langages.
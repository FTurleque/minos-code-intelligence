# ADR-0003 — Réutiliser fortement Glean derrière une abstraction MINOS

- Statut : **Proposée — à valider pendant C0**
- Date : 19 juillet 2026

## Contexte

Glean fournit déjà un système spécialisé pour collecter, stocker, dériver et interroger des faits typés sur le code source.

Reconstruire immédiatement une plateforme équivalente dans MINOS dupliquerait une quantité importante de travail existant.

Cependant, coupler directement le domaine MINOS aux API Glean, aux requêtes Angle, aux détails de stockage ou à la topologie de déploiement rendrait Glean difficilement remplaçable.

MINOS doit pouvoir :

- évoluer indépendamment ;
- utiliser un backend mémoire pour les tests ;
- supporter éventuellement un backend plus léger ;
- intégrer plus tard d'autres moteurs spécialisés.

## Décision proposée

MINOS doit **réutiliser fortement Glean** comme backend privilégié à évaluer pour les faits et requêtes de Code Intelligence.

Glean doit être placé derrière une abstraction possédée par MINOS, provisoirement nommée :

```text
CodeKnowledgeStore
```

Sens de dépendance :

```text
Domaine / Services MINOS
          │
          ▼
  CodeKnowledgeStore
          │
          ▼
GleanCodeKnowledgeStore
          │
          ▼
        Glean
```

Le contrat `CodeKnowledgeStore` doit être défini à partir des cas d'usage MINOS et non comme une copie de l'API Glean.

L'adaptateur Glean peut utiliser en interne :

- Angle ;
- Thrift ;
- les schémas Glean ;
- d'autres détails spécifiques.

Ces éléments ne doivent pas fuiter vers :

- le domaine MINOS ;
- la CLI ;
- les outils MCP ;
- l'API ;
- les contrats d'intégration NEXUS.

## Responsabilités candidates de l'adaptateur Glean

- ingérer ou exposer les faits indexés ;
- résoudre les symboles et emplacements ;
- interroger les références et implémentations ;
- interroger les relations d'appel et de type lorsque disponibles ;
- exécuter des requêtes orientées graphe ;
- persister éventuellement des faits dérivés MINOS ;
- retourner des résultats normalisés MINOS.

## Faits spécifiques à MINOS

MINOS pourra définir des connaissances supplémentaires comme :

```text
RELATED_TEST
DEPENDS_ON
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
CONFIDENCE
EVIDENCE
```

Ces concepts appartiennent au modèle d'intelligence MINOS même s'ils sont physiquement stockés dans Glean.

## Avantages

- réutilisation d'une base spécialisée dans les faits de code ;
- évite de reconstruire prématurément une plateforme de graphe et de requêtes ;
- possibilité de réutiliser les chemins d'ingestion SCIP ;
- potentiel de requêtes avancées rapidement ;
- contrats publics MINOS indépendants de Glean ;
- possibilité d'un backend mémoire pour les tests.

## Inconvénients

- complexité opérationnelle supérieure à une base Java embarquée ;
- technologies hors de la stack Java principale ;
- nécessité d'un mapping précis entre faits Glean et concepts MINOS ;
- besoin potentiel de schémas ou dérivations spécifiques ;
- coût initial de conception d'une frontière remplaçable.

## Alternatives étudiées

### Reconstruire immédiatement un fact store complet dans SQLite

Non retenu comme direction par défaut à ce stade, car cela pourrait dupliquer des capacités déjà disponibles dans Glean.

SQLite peut néanmoins rester pertinent pour :

- le registre des projets ;
- les métadonnées locales ;
- les fixtures ;
- un backend léger futur.

### Exposer directement Glean aux consommateurs

Non retenu, car CLI, MCP, API et NEXUS deviendraient couplés aux concepts et au langage de requêtes de Glean.

### Maintenir plusieurs backends de production avec parité dès le départ

Non retenu pour le premier périmètre, car cela créerait une complexité excessive avant stabilisation du domaine.

## Formulation de la stratégie candidate

> **Glean-first, not Glean-locked.**

Cette formulation est une hypothèse d'architecture, pas encore une décision définitive.

## Validation requise

Avant acceptation définitive, il faudra démontrer que :

1. un dépôt représentatif peut être indexé et interrogé via Glean ;
2. des faits SCIP peuvent être ingérés et exploités ;
3. MINOS peut exposer ses propres contrats sans type Glean ;
4. une implémentation mémoire de `CodeKnowledgeStore` est possible ;
5. la complexité opérationnelle reste acceptable pour un outil local-first ;
6. la distribution est réaliste sur les environnements cibles, notamment Windows ;
7. le coût en mémoire, disque et démarrage est acceptable.

## Condition d'acceptation

Cette ADR ne pourra passer au statut **Acceptée** qu'après validation du cahier des charges et des expérimentations prévues en M0.
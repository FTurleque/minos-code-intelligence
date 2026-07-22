# Validation de C0 — MINOS

Date : **22 juillet 2026**

Statut : **C0 clôturée**

## Objet

La phase **C0 — Cadrage fonctionnel et architectural** est clôturée.

Le projet peut passer à **M0 — Faisabilité technique**.

## Éléments validés

C0 a validé :

- le cahier des charges MINOS ;
- la frontière MINOS / NEXUS ;
- la place fonctionnelle de MINOS dans l'écosystème JARVIS / NEXUS / Alfred / Brainiac ;
- le fonctionnement local-first ;
- l'agnosticisme du langage ;
- l'agnosticisme de l'indexeur ;
- le MVP strict ;
- le modèle de domaine minimal nécessaire à M0 ;
- le modèle des fournisseurs, capacités et niveaux de support ;
- la stratégie de tests, fixtures et vérité terrain ;
- les familles de métriques et leurs premiers seuils ;
- le protocole expérimental M0 ;
- Java comme premier écosystème de validation ;
- TypeScript comme second écosystème ;
- Python comme repli expérimental ;
- `FTurleque/ariane-chatbot` comme dépôt Java réel principal de M0.

## ADR acceptées

### ADR-0001

Cœur MINOS **agnostique du langage et de l'indexeur**.

### ADR-0002

**SCIP** est le protocole d'interopérabilité sémantique privilégié lorsqu'un fournisseur suffisamment fiable existe.

SCIP n'est pas obligatoire et ne constitue pas le domaine MINOS.

### ADR-0003

`CodeKnowledgeStore` constitue une frontière possédée par MINOS.

Le principe retenu est :

> **MINOS-first, Glean-optional.**

Glean reste un backend avancé candidat qui doit démontrer sa valeur pendant M0.

### ADR-0004

Stack initiale :

```text
Java 25 LTS
Apache Maven 3.9.x
Maven Wrapper
cœur sans framework serveur
```

## MVP strict validé

Le MVP doit notamment fournir :

- registre de projets locaux ;
- découverte du dépôt, des langages et des builds ;
- gestion `.gitignore` / `.minosignore` ;
- registre et capacités des fournisseurs ;
- modèle normalisé MINOS ;
- symboles et occurrences ;
- relations entre symboles, fichiers, modules et projets ;
- `find_symbol` ;
- `find_usages` ;
- `find_implementations` ;
- `find_dependencies` ;
- `find_dependents` ;
- recherche structurée minimale ;
- réponses compactes textuelles et JSON ;
- CLI minimale.

Les analyses avancées restent hors du MVP selon la roadmap.

## Objectif de M0

M0 doit répondre à la question :

> **MINOS peut-il construire une Code Intelligence précise, multi-langages, locale et compacte en réutilisant SCIP et éventuellement Glean, tout en restant indépendant de ces technologies ?**

M0 ne doit pas devenir une implémentation anticipée du produit complet.

## Règle de transition

À partir de cette clôture :

> **Mesurer avant d'industrialiser.**

Les décisions issues de M0 devront être fondées sur les résultats des fixtures, dépôts réels, mesures de précision, performances et complexité opérationnelle.

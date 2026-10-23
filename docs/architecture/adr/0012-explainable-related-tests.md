---
id: ADR-0012
title: "Conserver les tests liés comme dérivations explicables"
domain: data
rules: false
---

# ADR-0012 — Conserver les tests liés comme dérivations explicables

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M5 — Tests liés et dérivations explicables

## Context

La relation entre un symbole de production et un test peut provenir de signaux de qualité différente : référence, appel, convention de nommage ou proximité. Une heuristique ne doit pas devenir silencieusement un fait certain.

## Decision

MINOS représente `RELATED_TEST` comme une dérivation explicable.

- chaque résultat conserve score, raisons et preuves ;
- les signaux de référence/appel et les heuristiques restent distinguables ;
- les conventions de nommage ou de proximité ne sont jamais promues en certitude ;
- les dérivations sont persistables et interrogeables comme les autres faits enrichis.

## Do's and Don'ts

### Do

- Représenter `RELATED_TEST` comme une dérivation explicable qui conserve score, raisons et preuves.
- Garder distinguables les signaux de référence/appel et les heuristiques.
- Rendre les dérivations persistables et interrogeables comme les autres faits enrichis.

### Don't

- Ne jamais promouvoir une convention de nommage ou de proximité en certitude.
- Ne pas confondre un signal de référence/appel avec une heuristique.

## Consequences

Un consommateur peut exploiter les tests liés tout en comprenant la raison et la confiance de la relation. L’analyse d’impact peut réutiliser ces liens sans prétendre à une couverture runtime exhaustive.

## Compliance and Enforcement

- Tests `RelatedTestDerivationServiceTest` et `RelatedTestHeuristicsTest` (`minos-engine`) — score, raisons et preuves des tests liés.
- Revue : aucune heuristique présentée comme un fait certain.

## Preuves

Voir [`../../project/history/milestones/m5/DECISION_M5.md`](../../project/history/milestones/m5/DECISION_M5.md) et les rapports M5 associés.

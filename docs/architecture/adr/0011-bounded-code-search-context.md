---
id: ADR-0011
title: "Borner explicitement la recherche et le contexte de code"
domain: backend
rules: false
---

# ADR-0011 — Borner explicitement la recherche et le contexte de code

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M4 — Recherche et contexte compact

## Context

Une surface de Code Intelligence destinée aux humains et aux agents doit rester déterministe, compacte et contrôlable. Retourner implicitement des fichiers entiers ou des graphes non bornés rend le coût et la taille du contexte imprévisibles.

## Decision

MINOS sépare la recherche structurée du chargement explicite de source complète.

- les résultats, profondeurs et budgets de tokens sont bornés ;
- les troncatures et limitations sont signalées ;
- les réponses textuelles et JSON restent compactes et déterministes ;
- la recherche privilégie les extraits et faits pertinents ;
- la lecture d’un fichier complet est une opération explicite distincte.

## Do's and Don'ts

### Do

- Borner les résultats, les profondeurs et les budgets de tokens.
- Signaler les troncatures et les limitations.
- Faire de la lecture d'un fichier complet une opération explicite distincte.
- Conserver les mêmes bornes dans les surfaces CLI, API et MCP.

### Don't

- Ne pas renvoyer implicitement des fichiers entiers ou des graphes non bornés.
- Ne pas réimplémenter dans une surface une politique de bornes différente.

## Consequences

Les consommateurs peuvent budgéter le contexte et éviter les réponses volumineuses involontaires. Les surfaces CLI, API et MCP doivent conserver ces bornes au lieu de réimplémenter une politique différente.

## Compliance and Enforcement

- Test `CodeSearchServiceTest` (`minos-application`) — bornes et troncatures de la recherche.
- Revue : toute nouvelle requête ou surface est bornée, signale ses troncatures et réutilise la politique commune.

## Preuves

Voir [`../../project/history/milestones/m4/DECISION_M4.md`](../../project/history/milestones/m4/DECISION_M4.md) et les rapports M4 associés.

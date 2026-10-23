---
id: BE-0015
title: "Traiter l’analyse d’impact comme une estimation potentielle du graphe observé"
domain: backend
rules: false
---

# Traiter l’analyse d’impact comme une estimation potentielle du graphe observé

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M8 — Analyse d’impact

## Context

Le graphe statique observé par MINOS ne peut pas prouver tous les comportements runtime : dispatch dynamique, réflexion, configuration et chargements externes peuvent échapper aux fournisseurs d’indexation.

## Decision

L’analyse d’impact MINOS est déterministe, bornée et explicable, mais reste une **estimation potentielle** fondée sur le graphe disponible.

- les impacts directs et indirects conservent leurs chemins explicatifs ;
- profondeur et nombre de résultats sont bornés ;
- les cycles sont gérés explicitement ;
- les tests potentiellement impactés peuvent être dérivés ;
- les limitations runtime sont exposées ;
- aucun résultat n’est présenté comme preuve d’exhaustivité runtime.

## Do's and Don'ts

### Do

- Conserver les chemins explicatifs des impacts directs et indirects.
- Borner la profondeur et le nombre de résultats.
- Gérer les cycles explicitement et exposer les limitations runtime.

### Don't

- Ne présenter aucun résultat comme preuve d'exhaustivité runtime.
- Ne pas confondre absence de chemin statique et absence d'impact réel.

## Consequences

Les consommateurs peuvent utiliser l’impact pour prioriser inspection et tests sans confondre absence de chemin statique et absence d’impact réel.

## Compliance and Enforcement

- Validation d'entrée de `ImpactAnalysisRequest` (`minos-application`) — profondeur bornée de 1 à 32.
- Test `ImpactAnalysisServiceTest` (`minos-application`) — chemins, profondeur et cycles de l'analyse d'impact.
- Revue : résultats bornés et explicables, limitations runtime exposées.

## Preuves

Voir [`../../project/history/milestones/m8/DECISION_M8.md`](../../project/history/milestones/m8/DECISION_M8.md) et [`../../project/history/milestones/m8/IMPACT_ANALYSIS.md`](../../project/history/milestones/m8/IMPACT_ANALYSIS.md).

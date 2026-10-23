---
id: ADR-0009
title: "Modéliser les identités de symboles sans inventer de canonicité"
domain: data
rules: false
---

# ADR-0009 — Modéliser les identités de symboles sans inventer de canonicité

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M2 — Intelligence des symboles

## Context

MINOS reçoit des identités de symboles produites par des indexeurs dont la précision varie selon le langage et le fournisseur. Une identité fournisseur peut être exploitable sans être suffisamment stable ou complète pour être qualifiée de canonique.

## Decision

MINOS conserve une identité normalisée indépendante du fournisseur et qualifie explicitement sa qualité.

- `CANONICAL` n’est utilisé que lorsqu’une identité canonique est réellement démontrée ;
- les symboles projet peuvent utiliser un `STRUCTURAL_FALLBACK` ;
- les symboles externes peuvent utiliser un `PROVIDER_SCOPED_FALLBACK` ;
- aucun `qualifiedName`, kind, surcharge ou symbole externe n’est inventé pour compenser une lacune fournisseur ;
- les non-résolutions restent explicites et persistables.

## Do's and Don'ts

### Do

- Conserver une identité normalisée indépendante du fournisseur et qualifier explicitement sa qualité.
- Utiliser `STRUCTURAL_FALLBACK` pour les symboles projet et `PROVIDER_SCOPED_FALLBACK` pour les symboles externes lorsque la canonicité n'est pas démontrée.
- Garder les non-résolutions explicites et persistables.

### Don't

- Ne pas marquer `CANONICAL` une identité dont la canonicité n'est pas réellement démontrée.
- Ne pas inventer `qualifiedName`, kind, surcharge ou symbole externe pour compenser une lacune fournisseur.

## Consequences

La recherche et les snapshots peuvent rester stables sans faire passer une heuristique pour un fait. Les consommateurs doivent tenir compte de la qualité d’identité lorsqu’ils comparent ou corrèlent des symboles.

## Compliance and Enforcement

- Test `ScipSymbolNormalizerTest` (`minos-provider-scip`) — qualité d'identité attribuée par la normalisation SCIP.
- Revue : aucune identité marquée `CANONICAL` sans démonstration, aucune donnée de symbole inventée.

## Preuves

Voir [`../../project/history/milestones/m2/DECISION_M2.md`](../../project/history/milestones/m2/DECISION_M2.md) et les rapports M2 associés.

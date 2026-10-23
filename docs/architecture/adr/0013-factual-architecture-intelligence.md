---
id: ADR-0013
title: "Séparer les faits d’architecture de leur interprétation"
domain: architecture
rules: false
---

# ADR-0013 — Séparer les faits d’architecture de leur interprétation

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M6 — Intelligence d’architecture

## Context

MINOS calcule topologie, dépendances inter-modules, concentration, centralité et technologies détectées. Ces mesures décrivent le graphe observé mais ne constituent pas automatiquement des jugements d’architecture ou de criticité.

## Decision

MINOS expose une intelligence d’architecture factuelle et explicable.

- la topologie et les dépendances proviennent de faits persistés ;
- concentration et centralité restent des mesures, jamais une interprétation métier automatique ;
- centralités entrante et sortante sont distinctes et relatives ;
- les technologies exposées sont celles effectivement qualifiées par la découverte ;
- toutes les vues restent rattachées à un projet et un snapshot cohérents.

## Do's and Don'ts

### Do

- Tirer la topologie et les dépendances de faits persistés.
- Distinguer les centralités entrante et sortante, relatives.
- N'exposer que les technologies effectivement qualifiées par la découverte.
- Rattacher chaque vue à un projet et un snapshot cohérents.

### Don't

- Ne pas transformer concentration et centralité en interprétation métier automatique.
- Ne pas imposer de seuils arbitraires de « composant critique » ou de « mauvaise architecture ».

## Consequences

Les consommateurs peuvent interpréter ces mesures selon leur besoin sans que MINOS impose des seuils arbitraires de « composant critique » ou « mauvaise architecture ».

## Compliance and Enforcement

- Tests `ArchitectureTopologyServiceTest` et `ArchitectureCentralityServiceTest` (`minos-application`) — topologie et centralités calculées depuis les faits.
- Revue : aucune interprétation métier ni seuil de criticité ajouté aux mesures.

## Preuves

Voir [`../../project/history/milestones/m6/DECISION_M6.md`](../../project/history/milestones/m6/DECISION_M6.md) et les rapports M6 associés.

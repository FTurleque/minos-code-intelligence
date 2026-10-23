---
id: ARCH-0018
title: "Versionner une API Java publique indépendante des modèles internes"
domain: architecture
rules: false
---

# Versionner une API Java publique indépendante des modèles internes

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M11 — API publique

## Context

Des systèmes Java doivent pouvoir consommer MINOS sans dépendre des adaptateurs SCIP, du stockage local, de la CLI, du MCP ou des classes internes susceptibles d’évoluer.

## Decision

MINOS expose un contrat Java public versionné autour de `MinosApi` et de son implémentation locale.

- le contrat possède une version explicite ;
- les modèles publics sont séparés des modèles internes et fournisseurs ;
- la surface couvre les capacités métier déjà stabilisées ;
- les implémentations locales restent remplaçables derrière le contrat ;
- l’évolution du contrat doit rester additive ou passer par une nouvelle version explicite.

## Do's and Don'ts

### Do

- Donner une version explicite au contrat Java public (`MinosApi`).
- Séparer les modèles publics des modèles internes et fournisseurs.
- Garder les implémentations locales remplaçables derrière le contrat.
- Faire évoluer le contrat de façon additive ou par une nouvelle version explicite.

### Don't

- Ne pas faire dépendre les consommateurs Java des adaptateurs SCIP, du stockage local, de la CLI, du MCP ou des classes internes.
- Ne pas changer le contrat de façon non additive sans nouvelle version explicite.

## Consequences

Les intégrations Java disposent d’une frontière stable et testable sans couplage au stockage, à SCIP ou aux détails de lancement de MINOS.

## Compliance and Enforcement

- Test `MinosApiContractTest` (`minos-api`, lancé par `mvnw verify`) — la version de contrat est explicite ; les signatures publiques et les composants des DTO ne nomment aucun type interne MINOS.
- Tests de contrat des autres API publiques (`MinosMultiRepositoryApiContractTest`, `AdvancedCodeIntelligenceApiContractTest`, `SemanticCodeIntelligenceApiContractTest`) ; leur portée n’est pas détaillée ici.
- Revue : toute évolution d’une API publique est additive ou passe par une nouvelle version explicite.

## Preuves

Voir [`../../project/history/milestones/m11/DECISION_M11.md`](../../project/history/milestones/m11/DECISION_M11.md) et [`../../project/history/milestones/m11/API.md`](../../project/history/milestones/m11/API.md).

---
id: ADR-0016
title: "Stabiliser la CLI comme surface d’exposition du cœur métier"
domain: architecture
rules: true
---

# ADR-0016 — Stabiliser la CLI comme surface d’exposition du cœur métier

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M9 — CLI stabilisée

## Context

MINOS doit être scriptable sans créer une seconde implémentation des capacités déjà présentes dans les services internes. La CLI doit aussi rester honnête sur les opérations réellement disponibles.

## Decision

La CLI MINOS est une surface stable qui délègue au même cœur métier que les autres interfaces.

- administration, import SCIP, statut, recherche, symboles, relations, tests liés, architecture et impact utilisent les services existants ;
- les formats `text` et `json` ainsi que les codes de sortie font partie du contrat CLI ;
- les erreurs et limitations restent structurées et déterministes ;
- `minos index` ne simule jamais un runner externe absent ; l’import d’un artefact fournisseur reste explicite lorsqu’aucun runner qualifié n’est disponible.

## Do's and Don'ts

### Do

- Faire déléguer la CLI au même cœur métier que les autres interfaces, par les services existants.
- Traiter les formats `text` et `json` ainsi que les codes de sortie comme partie du contrat CLI.
- Produire des erreurs et des limitations structurées et déterministes.
- Garder explicite l’import d’un artefact fournisseur lorsqu’aucun runner qualifié n’est disponible.

### Don't

- Ne pas simuler dans `minos index` un runner externe absent.
- Ne pas créer dans la CLI une seconde implémentation des capacités déjà présentes dans les services internes.

## Consequences

Les scripts et intégrations peuvent dépendre d’un comportement CLI cohérent sans divergence fonctionnelle avec l’API ou le MCP.

## Compliance and Enforcement

- Test `CliExitCodesTest` (`minos-cli`, lancé par `mvnw verify` dans les checks `Verify`) — les codes de sortie gardent leurs valeurs publiées.
- Aucun contrôle automatique de la délégation au cœur métier ni du refus de simuler un runner absent.
- Revue : une commande nouvelle ou modifiée délègue à un service existant, garde les formats `text` et `json` et un code de sortie documenté, et ne simule aucun runner.
- Règle Archgate `0016-stable-cli-contract.rules.ts` / `exit-codes-match-cli-reference` : refuse un code de `CliExitCodes.java` absent de la section « Codes de sortie » de `docs/user/cli.md`, ou un code publié là qu'aucune constante ne déclare ; contrôle propre à Archgate, aucun gate Python ne le fait (`CliExitCodesTest` fige les valeurs, pas leur publication).

## Preuves

Voir [`../../project/history/milestones/m9/DECISION_M9.md`](../../project/history/milestones/m9/DECISION_M9.md) et [`../../project/history/milestones/m9/CLI.md`](../../project/history/milestones/m9/CLI.md).

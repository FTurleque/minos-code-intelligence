# ADR-0016 — Stabiliser la CLI comme surface d’exposition du cœur métier

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M9 — CLI stabilisée

## Contexte

MINOS doit être scriptable sans créer une seconde implémentation des capacités déjà présentes dans les services internes. La CLI doit aussi rester honnête sur les opérations réellement disponibles.

## Décision

La CLI MINOS est une surface stable qui délègue au même cœur métier que les autres interfaces.

- administration, import SCIP, statut, recherche, symboles, relations, tests liés, architecture et impact utilisent les services existants ;
- les formats `text` et `json` ainsi que les codes de sortie font partie du contrat CLI ;
- les erreurs et limitations restent structurées et déterministes ;
- `minos index` ne simule jamais un runner externe absent ; l’import d’un artefact fournisseur reste explicite lorsqu’aucun runner qualifié n’est disponible.

## Conséquences

Les scripts et intégrations peuvent dépendre d’un comportement CLI cohérent sans divergence fonctionnelle avec l’API ou le MCP.

## Preuves

Voir [`../history/milestones/m9/DECISION_M9.md`](../history/milestones/m9/DECISION_M9.md) et [`../history/milestones/m9/CLI.md`](../history/milestones/m9/CLI.md).

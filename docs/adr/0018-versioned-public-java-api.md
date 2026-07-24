# ADR-0018 — Versionner une API Java publique indépendante des modèles internes

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M11 — API publique

## Contexte

Des systèmes Java doivent pouvoir consommer MINOS sans dépendre des adaptateurs SCIP, du stockage local, de la CLI, du MCP ou des classes internes susceptibles d’évoluer.

## Décision

MINOS expose un contrat Java public versionné autour de `MinosApi` et de son implémentation locale.

- le contrat possède une version explicite ;
- les modèles publics sont séparés des modèles internes et fournisseurs ;
- la surface couvre les capacités métier déjà stabilisées ;
- les implémentations locales restent remplaçables derrière le contrat ;
- l’évolution du contrat doit rester additive ou passer par une nouvelle version explicite.

## Conséquences

Les intégrations Java disposent d’une frontière stable et testable sans couplage au stockage, à SCIP ou aux détails de lancement de MINOS.

## Preuves

Voir [`../history/milestones/m11/DECISION_M11.md`](../history/milestones/m11/DECISION_M11.md) et [`../history/milestones/m11/API.md`](../history/milestones/m11/API.md).

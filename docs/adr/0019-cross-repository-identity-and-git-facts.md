# ADR-0019 — Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M12 — Multi-dépôts et intelligence Git

## Contexte

Un workspace peut contenir plusieurs dépôts et des références externes potentiellement résolubles entre eux. Une similarité de nom ou une activité Git élevée ne suffit ni à prouver une relation de code ni à établir une importance architecturale.

## Décision

MINOS applique deux frontières strictes.

### Résolution cross-repository

Une relation n’est promue entre dépôts que si `providerId + externalId` correspond exactement à une identité fournisseur locale, avec une cible unique et traçable. Toute ambiguïté reste non résolue.

### Intelligence Git

Les commits, auteurs, fichiers et zones modifiées sont exposés comme faits Git bornés. Ils ne sont pas automatiquement convertis en centralité, criticité ou importance architecturale.

## Conséquences

MINOS peut enrichir la connaissance multi-repo sans fabriquer de liens par ressemblance et sans mélanger activité de développement et structure du code.

## Preuves

Voir [`../history/milestones/m12/DECISION_M12.md`](../history/milestones/m12/DECISION_M12.md) et [`../history/milestones/m12/MULTI_REPO_GIT.md`](../history/milestones/m12/MULTI_REPO_GIT.md).

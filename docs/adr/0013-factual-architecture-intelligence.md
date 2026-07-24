# ADR-0013 — Séparer les faits d’architecture de leur interprétation

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M6 — Intelligence d’architecture

## Contexte

MINOS calcule topologie, dépendances inter-modules, concentration, centralité et technologies détectées. Ces mesures décrivent le graphe observé mais ne constituent pas automatiquement des jugements d’architecture ou de criticité.

## Décision

MINOS expose une intelligence d’architecture factuelle et explicable.

- la topologie et les dépendances proviennent de faits persistés ;
- concentration et centralité restent des mesures, jamais une interprétation métier automatique ;
- centralités entrante et sortante sont distinctes et relatives ;
- les technologies exposées sont celles effectivement qualifiées par la découverte ;
- toutes les vues restent rattachées à un projet et un snapshot cohérents.

## Conséquences

Les consommateurs peuvent interpréter ces mesures selon leur besoin sans que MINOS impose des seuils arbitraires de « composant critique » ou « mauvaise architecture ».

## Preuves

Voir [`../history/milestones/m6/DECISION_M6.md`](../history/milestones/m6/DECISION_M6.md) et les rapports M6 associés.

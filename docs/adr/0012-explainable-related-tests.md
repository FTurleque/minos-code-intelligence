# ADR-0012 — Conserver les tests liés comme dérivations explicables

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M5 — Tests liés et dérivations explicables

## Contexte

La relation entre un symbole de production et un test peut provenir de signaux de qualité différente : référence, appel, convention de nommage ou proximité. Une heuristique ne doit pas devenir silencieusement un fait certain.

## Décision

MINOS représente `RELATED_TEST` comme une dérivation explicable.

- chaque résultat conserve score, raisons et preuves ;
- les signaux de référence/appel et les heuristiques restent distinguables ;
- les conventions de nommage ou de proximité ne sont jamais promues en certitude ;
- les dérivations sont persistables et interrogeables comme les autres faits enrichis.

## Conséquences

Un consommateur peut exploiter les tests liés tout en comprenant la raison et la confiance de la relation. L’analyse d’impact peut réutiliser ces liens sans prétendre à une couverture runtime exhaustive.

## Preuves

Voir [`../history/milestones/m5/DECISION_M5.md`](../history/milestones/m5/DECISION_M5.md) et les rapports M5 associés.

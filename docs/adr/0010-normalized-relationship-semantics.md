# ADR-0010 — Normaliser les relations avec provenance, preuve et confiance explicites

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M3 — Intelligence des relations

## Contexte

Les indexeurs ne publient pas tous les mêmes relations ni avec la même sémantique. Transformer une référence, une implémentation ou une absence de relation en information plus précise créerait de faux faits.

## Décision

MINOS représente les relations dans un modèle fournisseur-neutre en conservant leur origine et leur niveau de certitude.

- les relations fournisseur sont normalisées uniquement lorsqu’une équivalence explicite existe ;
- les dépendances dérivées restent identifiées comme dérivations explicables ;
- provenance, nature, confiance et preuves sont conservées ;
- les cibles non résolues restent non résolues ;
- une capacité absente, telle qu’un `CALLS` non émis, n’est jamais synthétisée comme fait certain.

## Conséquences

Les requêtes directionnelles peuvent être uniformes entre fournisseurs sans masquer leurs limitations. Les consommateurs peuvent expliquer pourquoi une relation existe et distinguer fait observé et dérivation.

## Preuves

Voir [`../history/milestones/m3/DECISION_M3.md`](../history/milestones/m3/DECISION_M3.md) et les rapports M3 associés.

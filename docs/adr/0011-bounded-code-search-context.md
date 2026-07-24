# ADR-0011 — Borner explicitement la recherche et le contexte de code

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M4 — Recherche et contexte compact

## Contexte

Une surface de Code Intelligence destinée aux humains et aux agents doit rester déterministe, compacte et contrôlable. Retourner implicitement des fichiers entiers ou des graphes non bornés rend le coût et la taille du contexte imprévisibles.

## Décision

MINOS sépare la recherche structurée du chargement explicite de source complète.

- les résultats, profondeurs et budgets de tokens sont bornés ;
- les troncatures et limitations sont signalées ;
- les réponses textuelles et JSON restent compactes et déterministes ;
- la recherche privilégie les extraits et faits pertinents ;
- la lecture d’un fichier complet est une opération explicite distincte.

## Conséquences

Les consommateurs peuvent budgéter le contexte et éviter les réponses volumineuses involontaires. Les surfaces CLI, API et MCP doivent conserver ces bornes au lieu de réimplémenter une politique différente.

## Preuves

Voir [`../history/milestones/m4/DECISION_M4.md`](../history/milestones/m4/DECISION_M4.md) et les rapports M4 associés.

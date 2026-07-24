# ADR-0015 — Traiter l’analyse d’impact comme une estimation potentielle du graphe observé

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M8 — Analyse d’impact

## Contexte

Le graphe statique observé par MINOS ne peut pas prouver tous les comportements runtime : dispatch dynamique, réflexion, configuration et chargements externes peuvent échapper aux fournisseurs d’indexation.

## Décision

L’analyse d’impact MINOS est déterministe, bornée et explicable, mais reste une **estimation potentielle** fondée sur le graphe disponible.

- les impacts directs et indirects conservent leurs chemins explicatifs ;
- profondeur et nombre de résultats sont bornés ;
- les cycles sont gérés explicitement ;
- les tests potentiellement impactés peuvent être dérivés ;
- les limitations runtime sont exposées ;
- aucun résultat n’est présenté comme preuve d’exhaustivité runtime.

## Conséquences

Les consommateurs peuvent utiliser l’impact pour prioriser inspection et tests sans confondre absence de chemin statique et absence d’impact réel.

## Preuves

Voir [`../history/milestones/m8/DECISION_M8.md`](../history/milestones/m8/DECISION_M8.md) et [`../history/milestones/m8/IMPACT_ANALYSIS.md`](../history/milestones/m8/IMPACT_ANALYSIS.md).

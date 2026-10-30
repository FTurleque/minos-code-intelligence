# CLI `related-tests`

## Commande

```text
minos related-tests <project> <production-symbol-id> [options]
```

Options :

```text
--limit <1..1000>       maximum de résultats, 20 par défaut
--format <text|json>    rendu déterministe, text par défaut
-h, --help              aide spécifique
```

Le projet accepte son UUID ou un nom d'affichage non ambigu. Le symbole fourni
est le symbole de production. La commande recherche les relations entrantes
`RELATED_TEST`, car celles-ci sont stockées dans le sens test vers production.

## Explication des résultats

Le rendu JSON expose pour chaque relation :

- source et cible ;
- nature `DERIVED` ou `HEURISTIC` ;
- statut de résolution ;
- confiance ;
- origine ;
- preuves structurées complètes.

Le rendu texte expose également chaque type de preuve, son poids et sa
description. Il ne se limite plus à `evidenceCount`.

Exemple :

```powershell
.\minos.cmd related-tests <project-uuid> <symbol-id> --format json
```

Une collection vide est une réponse réussie. Elle signifie qu'aucune relation
M5 n'est présente dans le snapshot actif ; elle ne prouve pas l'absence de tests
dans le dépôt.

## Frontière produit

La commande réutilise le port `ProjectSymbolQuery` et les critères relationnels
MINOS. Elle ne charge ni protobuf SCIP ni API d'indexeur. Le cas d'usage métier
explicite est également disponible via
`RelationshipQueryService.findRelatedTests`.

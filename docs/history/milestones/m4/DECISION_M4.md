# Décision de clôture M4 — Recherche et contexte compact

Date : **23 juillet 2026**

Statut : **M4 TERMINÉ ET VALIDÉ LOCALEMENT**

## Verdict

Le jalon M4 est clôturé. MINOS fournit désormais son premier cœur directement
utilisable : une recherche structurée qui combine symboles, relations, preuves,
usages et plages source sous des limites explicites de résultats, profondeur et
tokens, avec une récupération séparée du fichier complet.

## Porte de sortie

| Critère | Résultat |
|---|---|
| Recherche structurée unifiée | satisfaite par `CodeSearchService` et `minos search` |
| JSON compact et déterministe | satisfait |
| Limites de résultats | satisfaites et signalées |
| Limite de profondeur | satisfaite, 0–3, traversée en largeur |
| Plage source pertinente | satisfaite avec réduction bornée |
| Source complète explicite | satisfaite par `minos get-source` |
| Politique d'efficacité en tokens | satisfaite et mesurable hors ligne |
| Confinement des sources | satisfait par chemins réels et contrôle de racine |
| Snapshot et nouveau processus | satisfaits |
| Benchmark de latence | satisfait sur fixture réelle |
| Frontière fournisseur | satisfaite |

## Preuve technique

```text
.\mvnw.cmd clean verify
92 sources main compilées
45 sources test compilées
131 tests réussis
0 échec
0 erreur
0 skipped
BUILD SUCCESS
```

Le launcher M4 est exercé dans une nouvelle JVM. Le JSON réel a également été
revalidé avec `ConvertFrom-Json`, puis `get-source` a récupéré le fichier
TypeScript complet depuis la racine enregistrée.

## Limites assumées

- l'estimation tokens est un proxy UTF-8 stable, pas un tokenizer de modèle ;
- les snapshots historiques dont les `fileId` sont opaques restent
  interrogeables, mais ne peuvent pas fournir une source locale inventée ;
- les fichiers source sont lus en UTF-8 et plafonnés à 16 MiB ;
- le benchmark est local et porte sur une petite fixture ;
- le contexte final destiné à un LLM reste la responsabilité de NEXUS. MINOS
  fournit une vue de Code Intelligence bornée, il ne choisit pas le prompt ;
- GitHub Actions n'a pas été relancé et ne fait pas partie de cette décision
  locale.

## Suite

Le prochain jalon est M5 — Tests liés et dérivations explicables.

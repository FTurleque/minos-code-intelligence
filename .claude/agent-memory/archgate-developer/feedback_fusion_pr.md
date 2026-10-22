---
name: fusion-pr-sans-suppression-anticipee
description: Fusion d'une PR MINOS — vérifier MERGED avant toute suppression de branche ; auto-merge interdit sur le dépôt ; PR fermée par suppression de branche non réouvrable
metadata:
  type: feedback
---

Ne jamais enchaîner `gh pr merge` et la suppression de la branche distante dans une même commande : vérifier d'abord `state == MERGED` (et `mergeStateStatus == CLEAN` avant la fusion, une PR BEHIND est refusée).

**Why:** le 2026-10-22, une fusion refusée (branche BEHIND) suivie de la suppression de la branche a fermé la PR #537 ; repousser la branche n'a pas permis de la rouvrir, il a fallu ouvrir la #538. GitHub supprime de toute façon la branche distante à la fusion sur ce dépôt.

**How to apply:** avant `gh pr merge --merge`, lire checks + `mergeStateStatus` ; après, lire `state`/`mergeCommit`. La fusion automatique GitHub est interdite sur `FTurleque/minos-code-intelligence` : attendre que l'utilisateur signale le CI vert. Voir [[minos-pr-flow]].

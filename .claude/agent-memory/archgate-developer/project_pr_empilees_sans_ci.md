---
name: pr-empilees-sans-ci
description: Une PR dont la base n'est pas main n'a aucun check ; pièges de la pile #758/#760/#761 (sprint 4 de l'audit 2026-11-15)
metadata:
  type: project
---

`pr-ci.yml` ne se déclenche que pour une PR qui vise `main` (`pull_request: branches: [main]`) : une PR empilée sur la branche d'une autre n'a **aucun check** (« no checks reported »).

**Why:** la pile #758 → #760 → #761 (fusionnée le 2026-11-16) est restée sans CI sur #760 et #761 tant qu'elles visaient la branche précédente.

**How to apply:**
- Ouvrir chaque PR d'une pile vers `main` dès le départ, ou re-cibler (`gh pr edit <n> --base main`) puis fermer et rouvrir la PR pour déclencher le CI (changer la base ne le relance pas).
- Une PR empilée affiche aussi les commits des PR précédentes jusqu'à leur fusion ; fusionner dans l'ordre, une à la fois, en remettant la suivante à jour depuis `main` (merge, jamais rebase) après chaque fusion.
- Gitleaks scanne l'historique de toutes les branches poussées : un faux positif d'une autre session fait rougir ma PR ; la règle d'exclusion se corrige dans la PR de cette session, pas ici.
- Un job `Verify (ubuntu)` qui échoue sur `curl: (22) … 429` après un `BUILD SUCCESS` (`qualify-linux-offline-maven-index.sh`) est transitoire : `gh run rerun --failed` une fois, après la fin du run.
- Sonar : les alertes se lisent par `curl https://sonarcloud.io/api/issues/search?componentKeys=FTurleque_atlas&pullRequest=<n>&resolved=false&sinceLeakPeriod=true`.
- GitHub supprime la branche distante à la fusion ; les branches locales fusionnées se suppriment par `git branch -d` hors des checkouts actifs.

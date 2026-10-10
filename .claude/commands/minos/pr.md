---
description: Préparer et ouvrir la PR de la branche courante vers develop (commit propre, vérifications, corps de PR)
argument-hint: "[titre de la PR]"
---

Ouvre la PR de la branche courante, selon le skill `minos-pr-flow`. Cette commande est la demande explicite de l'utilisateur de **pousser et d'ouvrir une PR**.

Titre proposé : `$ARGUMENTS` (sinon le déduire des commits, Conventional Commits en français, ≤ 70 caractères).

1. `git branch --show-current` : refuser `main` et `develop` (créer d'abord une branche thématique).
2. `git status --short` : lister ce qui est modifié ou non suivi ; n'ajouter que des chemins explicites qui font partie du changement (jamais `git add -A`) ; signaler les fichiers étrangers (`.rtk/`, `Claude outputs/`, audits locaux).
3. Rejouer `python .claude/scripts/run_gates.py --fast` et la vérification adaptée (skill `minos-verify-local`).
4. Commit si nécessaire (Conventional Commits, corps = pourquoi + preuves, avec l'attribution demandée par la session).
5. `git push -u origin <branche>` — si une permission est refusée, **ne pas contourner** : donner la commande à l'utilisateur.
6. `gh pr create --base develop --head <branche>` avec un corps : objet, changements, écarts et décisions, vérifications réellement exécutées, puis la ligne d'attribution de la session.
7. Rendre l'URL de la PR et proposer de suivre le CI (`minos-ci-triage` en cas d'échec).

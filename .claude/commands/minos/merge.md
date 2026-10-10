---
description: Fusionner une ou plusieurs PR dans develop quand leur CI est entièrement vert, une à la fois, branche à jour
argument-hint: "<numéro de PR> [<numéro suivant> …]"
---

Fusionne les PR `$ARGUMENTS` selon le skill `minos-pr-flow`. Cette commande est la demande explicite de fusionner **si et seulement si** le CI est vert.

Pour chaque PR, dans l'ordre donné :
1. `gh pr view <n> --json state,mergeStateStatus,baseRefName,headRefName,statusCheckRollup` (avec `rtk proxy gh` si `rtk` filtre le JSON). Refuser si la base n'est pas `develop`, si la PR n'est pas ouverte ou si un commentaire de revue demande des changements non traités.
2. Si `BEHIND` : `gh pr update-branch <n>`, puis attendre le nouveau CI (boucle **bornée**, en arrière-plan ; ne pas sonder en boucle courte). Ne pas fusionner la PR suivante avant d'avoir mis à jour son état.
3. Tous les **checks exigés** doivent être `SUCCESS` : `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `osv-scan`, `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)`, `SonarCloud Code Analysis`. Un échec : agent `minos-ci-triage`, ne rien forcer. Jamais `--admin`.
4. `gh pr merge <n> --merge --subject "Merge pull request #<n> from FTurleque/<branche>"`.
5. `git checkout develop && git pull --ff-only` puis passer à la PR suivante.

Rendre un tableau `PR | état | action | commit de fusion`. Ne supprimer aucune branche sauf demande explicite.

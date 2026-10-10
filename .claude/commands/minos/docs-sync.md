---
description: Synchroniser STATUS.md, ROADMAP.md et les docs courantes avec l'état réel (PR fusionnées, changements archivés), puis rejouer les gates documentaires
argument-hint: "[sujet ou numéros de PR]"
---

Applique le skill `minos-doc-sync` : `$ARGUMENTS`.

1. Relever l'état réel : `git log --oneline develop -20`, PR fusionnées (`gh pr list --state merged --limit 15`), changements OpenSpec actifs et archivés, `docs/audit/2026-10-10/SUIVI.md`.
2. Pour chaque affirmation à écrire, la vérifier (commande, run id, commit). Ne rien déclarer livré sans preuve.
3. Mettre à jour `docs/STATUS.md`, `docs/ROADMAP.md` et, si une sortie ou une règle change, `docs/user/` ou `docs/developer/`. Ne pas modifier l'historique figé.
4. `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `python scripts/quality/check-milestone-artifact-references.py`.
5. Présenter le diff et proposer le message de commit `docs(status): …`. Ne pas pousser.

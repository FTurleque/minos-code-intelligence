---
name: cloture-finale-audit-2026-11-05
description: AUD-SEC-04 et réserves DEP-13/TST-03/DEP-03 fusionnées dans develop le 2026-11-05 (PR #662, #663, #664) ; restent la mise à jour de findings.json et ETAT par l'auditeur
metadata:
  type: project
---

PR #662 (SEC-04, `7ad45c93`), #663 (DEP-13 et TST-03, `e0a9bb77`) et #664 (R-30, références SEC-0074, QUA-08, `cc38f352`) fusionnées dans `develop` le 2026-11-05, dans cet ordre, chacune remise à jour par `gh pr update-branch` après la fusion de la précédente. Branches distantes supprimées par le dépôt (`delete_branch_on_merge`), worktrees et branches locales retirés.

**TST-03** : `scripts/ci/install-linux-sandbox-toolchain.sh --with-packaged-jdk` (appelé par `pr-ci.yml` seulement) installe `openjdk-21-jre-headless=21.0.12.1+1-1~24.04.4` ; `LinuxSandboxToolchainHomeTest` passe par `SandboxTestSupport.decide(required(), …)`. Prouvé sur runner : 13 tests, 0 sauté (1 sauté avant).

**How to apply:** une nouvelle version Ubuntu d'openjdk-21 (chaque mise à jour trimestrielle d'OpenJDK) fait échouer l'installation volontairement : mettre à jour `PACKAGED_JDK_PACKAGE` dans le script. Restent à l'auditeur : `findings.json` et `ETAT-2026-10-13.md`. R-30 est daté de la dérogation du 2026-10-31 sans « décision du propriétaire » : l'utilisateur doit confirmer.

Pièges d'outillage de cette session :
- `SUIVI.md` est en CRLF dans l'arbre de travail : `Path.read_text` puis `write_text` le réécrit en LF ; le diff Git reste propre (autocrlf), l'avertissement est sans effet.
- Un script Python qui filtre un diff sous Windows doit lire et écrire en binaire (`sys.stdin.buffer`, `sys.stdout.buffer`), sinon `\r\n` casse `git apply`.
- `tar -x -C C:\...` lit `C:` comme un hôte distant : passer par `cygpath -u` ou `(cd dir && tar -x)`.
- `git worktree remove` échoue (Permission denied) si le shell a son répertoire courant dans le worktree : se placer à la racine avant.
- Le check « Transactional upgrade engine » (sonde MCP de l'installation neuve Windows, délai de 30 s) échoue par intermittence : une relance du job a suffi.
- `archgate review-context` n'inclut pas le texte des ADR ; voir [[reference-review-context-sans-texte]].

---
name: sprint8-audit-2026-11-15-fusionne
description: Sprint 8 de l'audit 2026-11-15 fusionné le 2026-11-19 (PR 781-792) ; restes archivage et SUIVI ; pièges CI externes
metadata:
  type: project
---

Sprint 8 (garde de frontières, contrats, documentation) fusionné le 2026-11-19 : #783 reclasser, #784 codec, #786
exécuteur, #791 donner (source unique `scripts/architecture/module-boundaries.json`), #792 aligner (A9 : portées,
Sources, Interfaces), #781 trancher (ARCH-0086), plus #790 (JDK Ubuntu épinglé relevé).

**Why:** restes à traiter et pièges qui ont coûté des heures.

**How to apply:**
- Restes : archivage OpenSpec des cinq changements (les lignes de traçabilité des exigences ajoutées n'entrent dans
  les dossiers de capacité qu'à l'archivage), statuts du sprint 8 dans `docs/project/audits/2026-11-15/SUIVI.md`.
- La politique de frontières se modifie dans `module-boundaries.json` seulement : le garde et `ModuleArchitectureTest`
  le lisent, A17 refuse une table recopiée dans le test.
- `scripts/ci/install-linux-sandbox-toolchain.sh` épingle le JDK de distribution Ubuntu : chaque build trimestriel
  d'Ubuntu casse `Verify (ubuntu-24.04)` jusqu'au relèvement (version publiée : API Launchpad getPublishedBinaries).
- Échecs CI externes fréquents, à relancer une fois (relance partielle seulement run terminé) : 404 Maven Central,
  429, miroirs Ubuntu en 503, job Windows coupé vers 51 min sans journal. Fusionner une PR rend les autres BEHIND :
  `gh pr update-branch`, puis un cycle CI complet (~50 min) chacune.
- Le hook interdit d'éditer un autre worktree depuis la session ; `reset --soft origin/main` après un fetch qui a
  avancé main produit un commit qui annule main : réinitialiser sur la base de la branche (merge-base).

Voir [[sprint2-securite-java-2026-11-16]] pour la règle de fusion sans suppression anticipée.

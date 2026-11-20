---
name: audit-2026-11-15-clos
description: Audit 2026-11-15 clos le 2026-11-19 (8 sprints fusionnés et archivés) ; restes manuels, groupe 4 d'injecter reporté ; pièges CI et fusion en série
metadata:
  type: project
---

Les huit sprints de l'audit 2026-11-15 sont fusionnés et archivés au 2026-11-19 ; aucun changement OpenSpec de cet
audit n'est actif. Les restes vivent dans `docs/project/audits/2026-11-15/SUIVI.md` (colonne « Statut », « Tâches
manuelles ») et dans les tâches non cochées des archives `openspec/changes/archive/2026-11-19-*`.

**Why:** des changements fusionnés sont restés actifs des jours faute d'archivage (sprints 2, 3, 4, 7) : l'utilisateur
attend qu'une PR fusionnée soit suivie de l'archivage OpenSpec et de la mise à jour du SUIVI sans qu'il le demande.

**How to apply:**
- Après la fusion du dernier changement d'un sprint, archiver tout de suite : `openspec archive -y <change>` dans
  l'ordre de fusion, puis une ligne de traçabilité par exigence ajoutée dans `docs/architecture/diagrams/capacites/`
  (`check-capability-dossiers.py` refuse la ligne avant l'archivage, l'exige après).
- Une exigence dont le code n'est pas livré ne s'archive pas : la retirer du delta avant `openspec archive` (cas du
  groupe 4 d'`injecter-les-collaborateurs-des-cas-d-usage`, retrait de `compositionRoot()` et des 15 accesseurs,
  reporté par le propriétaire ; ARCH-0087 reste `Proposed`).
- Restes connus : mesures sur la machine de référence, journaux de jobs CI à relire, JaCoCo agrégés, rotation Docker,
  parcours de la carte dans un navigateur, échec réel du worker WSL, `code-audit.yml` (mutation), SEC-0083 `Proposed`,
  `SnapshotScalabilityBenchmark` non rejoué.
- La politique de frontières se modifie dans `scripts/architecture/module-boundaries.json` seulement.
- `scripts/ci/install-linux-sandbox-toolchain.sh` épingle le JDK de distribution Ubuntu : chaque build trimestriel
  casse `Verify (ubuntu-24.04)` jusqu'au relèvement.
- CI : 404/429 Maven Central, miroirs Ubuntu en 503, job Windows coupé vers 51 min sans journal → relancer une fois
  (relance partielle seulement run terminé). Fusion en série : les autres PR passent BEHIND (`gh pr update-branch`,
  ~50 min de CI chacune) et se heurtent sur les lignes adjacentes du SUIVI ; `Verify` échoue en 18 s si main a avancé
  (« main is not an ancestor »). SonarCloud n'importe pas la couverture de `minos-app`.

Sprint 9 (AUD-ARC-11, AUD-PERF-14, ouverts par la vérification du 19/11) : fusionné (#796, #797) et archivé (#798) le
2026-11-20. Décisions D2 (résidu `FileProgramGraphProvider`) et D1 option C retenues par le propriétaire le 2026-11-20
(PR #799). Contournements A19 antérieurs (import générique
`java.net.*`, homonyme d'adaptateur) proposés en session à part. La fusion d'une PR exige un accord explicite pour
cette PR : le classificateur refuse une fusion couverte seulement par un accord donné pour d'autres PR.

Voir [[fusion-pr-sans-suppression-anticipee]] et [[pr-empilees-sans-ci]].

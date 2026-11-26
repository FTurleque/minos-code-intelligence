---
name: audit-2026-11-15-clos
description: Audit 2026-11-15 : 11 sprints fusionnés et archivés au 2026-11-26 (sprint 11 : #821-#828) ; restes manuels ; pièges CI, Dependabot et fusion en série
metadata:
  type: project
---

Les onze sprints de l'audit 2026-11-15 sont fusionnés et archivés au 2026-11-26 ; aucun changement OpenSpec de cet
audit n'est actif. Les restes vivent dans `docs/project/audits/2026-11-15/SUIVI.md` (colonne « Statut », « Tâches
manuelles ») et dans les tâches non cochées des archives `openspec/changes/archive/2026-11-{19,20,24,26}-*`.

**Why:** des changements fusionnés sont restés actifs des jours faute d'archivage (sprints 2, 3, 4, 7) : l'utilisateur
attend qu'une PR fusionnée soit suivie de l'archivage OpenSpec et de la mise à jour du SUIVI sans qu'il le demande.

**How to apply:**
- Après la fusion du dernier changement d'un sprint, archiver tout de suite : `openspec archive -y <change>` dans
  l'ordre de fusion, puis une ligne de traçabilité par exigence ajoutée dans `docs/architecture/diagrams/capacites/`
  (`check-capability-dossiers.py` refuse la ligne avant l'archivage, l'exige après).
- Une exigence dont le code n'est pas livré ne s'archive pas : la retirer du delta avant `openspec archive` (cas d'une
  exigence d'`injecter-les-collaborateurs-des-cas-d-usage` retirée du delta, livrée ensuite par
  `retirer-la-racine-de-composition-de-la-facade`).
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

Sprint 10 (2026-11-24) : AUD-ARC-12, TST-13, SEC-11, PERF-15 et les partiels DEP-05 (job SonarCloud sans code de la
PR, scanner autonome ; `minos-app` construit dans le `target/` de la racine, à lire à part) et ARC-02 (façade sans
racine de composition, `test-jar` en portée test, ARCH-0087 `Accepted`). Maven Central répond 429 aux runners GitHub
pendant plus de cinq minutes certains jours : les réessais de l'image Docker (#815) ne suffisent pas toujours ; un
miroir est une décision de chaîne d'approvisionnement laissée au propriétaire. Une PR fusionnée avant les autres ne
coûte rien si sa CI a tourné sur un `main` à jour : en tenir compte pour ordonner la série.

Sprint 11 (2026-11-26, #821-#828) : restes ARCH-0087 (mise à jour à proposer au propriétaire, décision D5) et skill
`minos-dependency-update` à appliquer à la prochaine PR Dependabot Temurin ou wrapper. `openspec validate` d'un
changement actif ne signale pas une exigence `MODIFIED` de plus de 500 caractères : elle n'apparaît qu'à la validation
de la spécification après `openspec archive`. Compter les caractères avant d'archiver ; sinon annuler l'archivage,
corriger le delta et réarchiver. Un job non exigé rouge laisse la PR `UNSTABLE` : un script de fusion qui attend
`CLEAN` s'arrête tant que le job n'est pas relancé et vert.

PR Dependabot : relever l'instantané apt `UBUNTU_APT_SNAPSHOT` de `docker/Dockerfile.mcp.release` avec chaque image
Temurin (un instantané plus ancien que l'image casse l'installation des paquets) ; remettre `mvnw.cmd` en LF quand
Dependabot le réécrit en CRLF (le candidat Windows refuse l'arbre modifié) ; aligner sur une montée de Gradle tous
les documents gardés par `check-toolchain-baseline.py` et `check-audit-remediation-v2.py`.

Voir [[fusion-pr-sans-suppression-anticipee]] et [[pr-empilees-sans-ci]].

# Tasks

Ordre : mesure, décision, puis rouge, correction, preuve. Commandes Gradle par le wrapper (`minos-intellij/gradlew` / `gradlew.bat`), avec réseau. Le plugin est hors reactor : aucune commande Maven ne le construit.

## 1. Mesure et décision (AUD-DEP-04)

- [ ] 1.1 [AUD-DEP-04] Mesurer : liste des configurations résolues par le build du plugin et, pour chacune, si elle résout une distribution d'IDE ; essai de verrou par configurations nommées (`--write-locks`) sous Linux et sous Windows, comparaison des deux fichiers ; essai de `--write-verification-metadata sha256` sur les deux systèmes (taille, artefacts d'IDE inclus ou non, stabilité à une seconde exécution). Consigner dans `design.md` (lève les « non mesuré »).
- [ ] 1.2 [AUD-DEP-04] **Décision à obtenir** : D2 (vérification complète, verrou seul, ou vérification limitée), au vu de 1.1. Consigner dans `design.md`.

## 2. Verrou (AUD-DEP-04)

- [ ] 2.1 [AUD-DEP-04] Rouge : cas d'auto-test du gate du plugin (`scripts/ci/test_plugin_gate.py` ou le test du gate retenu) sur arbres temporaires — verrou absent (refusé), verrou présent sans activation stricte dans `build.gradle.kts` (refusé), verrou contenant une coordonnée de distribution d'IDE (refusé), configuration cible (acceptée). **Preuve du rouge : le dépôt réel échoue.**
- [ ] 2.2 [AUD-DEP-04] `build.gradle.kts` : `dependencyLocking` strict sur les configurations de la liste 1.1, exclusions nommées et commentées (D1) ; `gradle.lockfile` généré et versionné. Preuve : gate vert ; `gradlew test`, `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure` verts sous le système du poste ; une dépendance modifiée sans régénération fait échouer le build (essai sur une copie).
- [ ] 2.3 [AUD-DEP-04] Preuve OSV : sur une copie, remplacer dans le verrou une version par une version touchée par un avis connu et lancer OSV-Scanner avec les `scan-args` de `pr-ci.yml` → échec nommant la dépendance ; sur le dépôt réel → succès, le verrou figure dans les sources analysées. Si OSV ne lit pas le verrou sans argument, l'ajouter aux `scan-args` (`yaml.safe_load`, `python scripts/quality/check-workflow-pins.py`).

## 3. Vérification de sommes (AUD-DEP-04, selon D2)

- [ ] 3.1 [AUD-DEP-04] Si D2 = A ou C : rouge (cas « métadonnées absentes » dans le gate ; somme altérée → build en échec sur une copie), puis `gradle/verification-metadata.xml` généré sous Linux et complété sous Windows, versionné. Si D2 = B : écrire la limite dans `docs/developer/supply-chain.md` et retirer du gate l'exigence de métadonnées.
- [ ] 3.2 **(manuelle)** [AUD-DEP-04] À la PR : `IntelliJ plugin (gate)` vert avec ses deux jobs exécutés (Linux et `windows-ownership`), `osv-scan` vert ; noter les run ids dans `SUIVI.md`.

## 4. Montées et clôture

- [ ] 4.1 [AUD-DEP-04] Procédure de montée (D4) dans `docs/developer/` et le skill `minos-dependency-update` ; `docs/developer/supply-chain.md` : périmètre vérifié et non vérifié. Rejouer `python scripts/docs/check-current-docs.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-toolchain-baseline.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-04 : statut, mesures 1.1, décision D2, écart sur `lockAllConfigurations()`).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/supply-chain.md`, `docs/TOOLCHAIN_POLICY.md` (qualification du plugin).

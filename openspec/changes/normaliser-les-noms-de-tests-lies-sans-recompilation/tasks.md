# Tasks

## 1. Normalisation sans recompilation (AUD-PERF-11)

- [x] 1.1 AUD-PERF-11 — Caractérisation (verte au HEAD, garde de la réécriture) : ajouter à `RelatedTestDerivationServiceTest` (`minos-engine`) les scénarios de l'exigence tests-lies-et-impact › Les noms comparés par la dérivation des tests liés sont normalisés par une règle stable : nom mixte, signe kelvin U+212A, langue par défaut `tr-TR` (restaurée en `finally`), nom de test et nom de fichier sans lettre ni chiffre ASCII ; les quatre passent par l'API publique de dérivation, la méthode de normalisation étant privée. Prouver que la garde mord : une normalisation qui filtre avant la mise en minuscules (mutation locale, non commitée) fait échouer le scénario kelvin. Vérifier : `./mvnw -B -ntp -pl minos-engine -am test "-Dtest=RelatedTest*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` vert au HEAD, rouge sous la mutation.
  - Preuve (2026-11-17, Windows) : 96 tests verts au HEAD ; sous la mutation `replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT)`, `keepsANonAsciiCharacterWhoseRootLowerCaseIsAsciiLetter` échoue (`expected: <1> but was: <0>`).
- [x] 1.2 AUD-PERF-11 — Correction : remplacer `replaceAll("[^a-z0-9]", "")` par un `Pattern` compilé une fois, appliqué après `toLowerCase(Locale.ROOT)` (design D1) ; commentaire en anglais.
  - Fait : constante `NOT_ASCII_LOWER_ALPHANUMERIC`, sans commentaire : la Javadoc du fichier est en français et un commentaire anglais ferait de lui un fichier mixte refusé par `check-comment-language.py` ; la règle est expliquée en anglais dans le test. Vérifier : tests de 1.1 verts ; `python scripts/quality/check-comment-language.py`.
- [x] 1.3 AUD-PERF-11 — Preuve : `./mvnw -B -ntp -pl minos-app -am test` vert sans golden régénéré (les tests de caractérisation comparent les sorties aux goldens ; un `git diff` des goldens ne prouve rien, la suite ne les réécrit pas) ; `python .claude/scripts/run_gates.py --fast` ; scope JaCoCo `global` inchangé sur la CI. Mesure de durée facultative, consignée « non mesuré » dans la PR si elle n'est pas faite.
  - Preuve (2026-11-17, Windows) : `./mvnw -B -ntp -o -pl minos-app -am test` BUILD SUCCESS (15 modules, 49 min), aucun golden régénéré ; durée de dérivation non mesurée.
- [ ] 1.4 AUD-PERF-11 — Documentation (reste, à l'archivage : `check-capability-dossiers.py` refuse une ligne qui nomme une exigence absente des spécifications de référence) : ajouter l'exigence à la traçabilité de `docs/architecture/diagrams/capacites/tests-lies-et-impact.md`. Vérifier : `python scripts/docs/check-capability-dossiers.py`.

## 2. Clôture

- [x] 2.1 AUD-PERF-11 — Mettre à jour la matrice de traçabilité du design ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 2.2 AUD-PERF-11 — (reste, à l'archivage, avec 1.4) Contrôler le dossier de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [x] 2.3 AUD-PERF-11 — Valider ; vérifier par `openspec validate --all --strict`.
- [x] 2.4 AUD-PERF-11 — Lister les documents à mettre à jour avant archivage (dossier de capacité, `docs/project/audits/2026-11-15/SUIVI.md`) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver après revue (`openspec archive normaliser-les-noms-de-tests-lies-sans-recompilation -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.

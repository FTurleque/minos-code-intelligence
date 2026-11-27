# Tasks

Changement indépendant des autres du sprint 12 ; seul à toucher du code Java (tests de `minos-application`). Jamais deux `mvnw test` simultanés sur le poste (octrois AppContainer sur le JDK partagé).

## 1. Preuves d'assemblage des opérations locales (AUD-TST-15)

- [x] 1.1 AUD-TST-15 — Rouge (import) : dans `minos-application/src/test/java/com/minos/application/LocalProjectOperationsRetentionTest.java`, ajouter `aPublishedImportThroughTheApplicationAppliesItsRetention` : `new LocalProjectOperations(fixture.application())`, import de `snapshot-1`, assertion `List.of("publish snapshot-1", "state snapshot-1", "retention snapshot-1")`. Le voir échouer d'abord avec une application dont la rétention n'est pas celle de la fixture (mutation locale du test, non commitée). Exigence : frontieres-de-modules › Les points d'entrée publics des opérations locales sont éprouvés sur une application.
- [x] 1.2 AUD-TST-15 — Rouge (requête de symboles) : dans `LocalOperationsAssemblyTest.java`, remplacer le corps de `theSymbolQueryOfAnApplicationIsBuiltOverItsQueryService` : registre double qui enregistre `findProject` (ou `listProjects`), application construite sur ce registre, `findSymbols` par `new LocalProjectSymbolQuery(application)`, assertion de l'appel enregistré ; garder l'assertion `NullPointerException` sur `null`. Le voir échouer d'abord avec un registre qui n'est pas celui de l'application.
- [x] 1.3 AUD-TST-15 — Correction : aucun code de production ; retirer les mutations locales de 1.1 et 1.2. Javadoc des cas en anglais.
- [x] 1.4 AUD-TST-15 — Preuve par mutation de la production (copie de travail, non commitée) : `MinosApplication.localProjectOperationsDependencies()` passe un autre `StorageRetentionService` ; `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalProjectOperationsRetentionTest" "-Dsurefire.failIfNoSpecifiedTests=false"` échoue sur le cas de 1.1 ; restaurer, puis vert.
- [x] 1.5 AUD-TST-15 — Gates : `./mvnw -B -ntp -pl minos-application -am test` vert, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`. Scopes JaCoCo : rejouer `python scripts/quality/check-jacoco.py` sur les rapports de `minos-application` si produits ; aucun plancher modifié.

## 2. Clôture

- [x] 2.1 AUD-TST-15 — Mettre à jour la matrice de traçabilité du design.
- [ ] 2.2 AUD-TST-15 — **(manuelle)** Sur la PR, constater `Verify (ubuntu-24.04)` et `Verify (windows-2022)` verts avec les deux cas exécutés.
- [ ] 2.3 AUD-TST-15 — Valider : `openspec validate --all --strict` vert ; après archivage, ligne de l'exigence ajoutée dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` ; `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 2.4 AUD-TST-15 — Documents à mettre à jour avant archivage : dossier de capacité (2.3), ligne AUD-TST-15 du suivi de l'audit (coordinateur, après fusion).

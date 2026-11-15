# Tasks

## 1. Suppression des miroirs stricts (AUD-QUA-04)

- [ ] 1.1 AUD-QUA-04 — Rouge : vérifier que `RemoteIndexCommandTest` et `LocalRemoteIndexOperationsIntegrationTest` figent les sorties texte et JSON de `remote materialize` et `remote index` (compléter le champ manquant s'il y en a un) ; ajouter dans `minos-application` un test d'`ArtifactEvidenceSummary` et de `RemoteMaterializationSummary` qui refuse un champ vide en le nommant, s'il n'existe pas ; écrire dans `minos-cli` un test qui exige, par réflexion, que `RemoteIndexOperations` ne déclare plus les types imbriqués `RemoteMaterializationView` et `ArtifactEvidence`. Exigence : surfaces-publiques › La CLI ne recopie pas à l'identique un résumé applicatif. Vérifier : le dernier test échoue.
- [ ] 1.2 AUD-QUA-04 — Correction : supprimer les deux enregistrements et les deux conversions de `LocalRemoteIndexOperations` ; faire rendre `RemoteMaterializationSummary` par `RemoteIndexOperations.materialize` et porter les résumés dans `RemoteIndexView` ; adapter `RemoteIndexCommand` et `RemoteIndexCommandTest` en gardant les quatre noms que `scripts/quality/check-remote-distributed-consistency.py:207-209` exige dans ce test (`SecretFree`, `RequiresExplicitWorkerNetworkPolicy`, `token@`, `rendersProjectRelativeRootInJsonAndTextOutput`) et les faits attendus de `LocalRemoteIndexOperationsIntegrationTest` (`:210`). Vérifier : `./mvnw -B -ntp -pl minos-cli -am test` ; ce gate vert ; `python scripts/architecture/check-module-boundaries.py` (A11 inchangé) ; scope JaCoCo citant `LocalRemoteIndexOperations`.

## 2. Parité des vues conservées (AUD-QUA-04)

- [ ] 2.1 AUD-QUA-04 — Rouge puis correction : écrire `CliViewParityTest` dans `minos-cli` — fonction de comparaison testée sur des enregistrements de test (composante ajoutée au résumé seulement, exclusion sans raison, exclusion périmée : trois refus), puis appliquée aux cinq couples du design avec la table de correspondances (`providerId` → `id`) et la table d'exclusions. Exigence : surfaces-publiques › Une vue conservée de la CLI couvre toutes les composantes de son résumé. Vérifier : `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=CliViewParityTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert.
- [ ] 2.2 AUD-QUA-04 — Documentation : écrire dans la Javadoc (en anglais) de `ProviderView`, `IndexPlanView`, `ResumeView`, `IndexExecutionView` et `RemoteIndexView` pourquoi la vue existe (assainissement, forme) et que `CliViewParityTest` en garde l'exhaustivité ; ajouter les deux exigences au dossier `docs/architecture/diagrams/capacites/surfaces-publiques.md`. Vérifier : `python scripts/quality/check-comment-language.py` ; `python scripts/docs/check-capability-dossiers.py`.
- [ ] 2.3 AUD-QUA-04 — Preuve : `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/`. Vérifier : code 0.

## 3. Clôture

- [ ] 3.1 AUD-QUA-04 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 3.2 AUD-QUA-04 — Contrôler le dossier de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 3.3 AUD-QUA-04 — Valider les spécifications ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`.
- [ ] 3.4 AUD-QUA-04 — Lister les documents à mettre à jour avant archivage (suivi de l'audit ; STATUS et ROADMAP si le constat y figure) ; nommer comme reste la copie interne `ProjectInspectionService.ProjectView` / `LocalProjectOperations`.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive supprimer-les-vues-miroirs-de-la-cli -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.

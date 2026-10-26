# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans le même module que leur classe. Gates à rejouer à chaque tâche : `python scripts/architecture/check-module-boundaries.py` et son auto-test, `python .claude/scripts/run_gates.py --fast`. Portées JaCoCo concernées : `global`, `public-api`.

## 0. Préalable

- [x] 0.1 [AUD-ARC-01] Décisions Q1 (résultats de requête reconnus ou descendus) et Q2 (forme de la précision de l'ADR 0057) du propriétaire **(manuelle)**. Les compteurs et les tâches 3.1 et 4.1 se recalculent si Q1 vaut B.

## 1. Requête applicative d'activité Git (AUD-ARC-01, lot 4)

- [x] 1.1 [AUD-ARC-01] Rouge (`minos-application`) : `LocalProjectGitQueryTest`, avec un faux port. Cas couverts : chaque champ de l'inspection et du rapport rendu à l'identique, listes nulles normalisées, mêmes noms de composants que les records du port (réflexion), refus de chaque borne de `ActivityRequest` avec le message du port, exception du port propagée sans enveloppe (même instance), construction par `MinosApplication`. **Preuve du rouge : la requête n'existe pas.**
- [x] 1.2 [AUD-ARC-01] `ProjectGitQuery` (interface et records) et `LocalProjectGitQuery`, dans `com.minos.application` (D1). Commentaires en anglais. Preuve : tests 1.1 verts ; `check-module-boundaries.py` vert (A8, A14, A15) ; `check-comment-language.py` vert.

## 2. Les surfaces consomment la requête (AUD-ARC-01, lot 4)

- [x] 2.1 [AUD-ARC-01] Rouge : `GitActivityCommandTest`, `CliValidInvocationsTest` et `MinosCliBuilderTest` passent aux types applicatifs (records applicatifs, builder `gitQuery`) et ne compilent pas contre le HEAD.
- [x] 2.2 [AUD-ARC-01] `GitActivityCommand`, `MinosCli` et `MinosCliRunner` (`LazyApplication.lazy(ProjectGitQuery.class, …LocalProjectGitQuery::new)`) consomment `ProjectGitQuery` ; `LocalMinosMultiRepositoryApi` construit `new LocalProjectGitQuery(app)`. Preuve : tests 2.1 et `LocalMinosMultiRepositoryApiIntegrationTest` verts sans attente modifiée ; `check-mne.py` vert.
- [x] 2.3 [AUD-ARC-01] `LocalMinosApi` construit `new LocalProjectSymbolQuery(app)` (D2). Preuve : tests de `minos-api` verts.

## 3. Cliquets et justifications (AUD-ARC-01, lot 4)

- [x] 3.1 [AUD-ARC-01] Rouge : avec les entrées retirées et le code du HEAD, le garde échoue (types non listés) ; le nouveau cas de l'auto-test (entrées `query.*` limitées aux trois modèles de lecture, aucun `git.GitIntelligence` ni accesseur de magasin pour l'API) échoue tant qu'une entrée Git reste listée. Correction : A11 du garde 33 → 29, A11 bytecode 28 → 26 ; raison écrite sur chaque entrée `query.*` des deux tables (D3). Mutations sur copie : réimport de `GitIntelligence` dans `GitActivityCommand`, `app.gitIntelligence()` dans `LocalMinosMultiRepositoryApi`, `app.projectRegistry()` dans `LocalMinosApi`, import de `query.SymbolQueryService` dans la CLI : chacune fait échouer A11. Preuve : le garde, son auto-test et `ModuleArchitectureTest` sont verts.

## 4. Clôture

- [x] 4.1 [AUD-ARC-01] `mvnw -pl minos-app -am test "-Dtest=A2SurfaceCharacterizationTest,ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert : les 12 goldens inchangés, aucun régénéré.
- [x] 4.2 [AUD-ARC-01] Documentation : ADR 0057 (mise en œuvre, point 6 : modèles de lecture publiés et requête Git applicative) ; ADR 0058 (« Point 3, lot 4 résorbé ») ; ligne du lot 4 dans `docs/project/audits/2026-10-10/SUIVI.md` ; arc42 relu. Rejouer `python .claude/scripts/run_gates.py`, `check-current-docs.py`, `product-facts.py --check`, `openspec validate --all --strict` et `archgate check`.
- [x] 4.3 [AUD-ARC-01] SpotBugs (profil `audit-spotbugs`) sur `minos-application`, `minos-cli` et `minos-api` : 0 nouvelle alerte, lignes de base réenregistrées si elles bougent. `mvnw verify` complet sous Windows ; Linux par la CI.

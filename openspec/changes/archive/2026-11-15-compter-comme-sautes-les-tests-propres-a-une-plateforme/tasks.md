# Tasks

## 1. Gate des retours anticipés de plateforme (AUD-TST-04)

- [x] 1.1 AUD-TST-04 — Rouge : écrire `scripts/quality/check-test-platform-guards.py` (motif multiligne, variable locale issue de `os.name`, tous les `src/test` Java, refus de zéro fichier) ; vérifier que `python scripts/quality/check-test-platform-guards.py` échoue au HEAD en nommant `CommandLocatorTest.java` (lignes 90, 155, 179), `ManagedPolyglotScipRuntimeManagerTest.java:62` et `M24PolyglotProviderTest.java:120` (`controles-de-fusion` › Aucun test ne passe à vide derrière un retour anticipé de plateforme)
- [x] 1.2 AUD-TST-04 — `--self-test` sur arbres temporaires : garde sur une ligne, sur deux lignes, par variable locale, racine sans test, exception sans objet, exception dans une méthode `@Test`, formes acceptées (`@EnabledOnOs`, retour anticipé sans rapport avec le système, `return <valeur>;` d'un assistant sous une garde de système) ; déclarer la liste d'exceptions (`AppContainerGrantLeakCheck.java` 2, `IcaclsTestAccessControl.java` 2, raison écrite) ; vérifier par `python scripts/quality/check-test-platform-guards.py --self-test` ; l'option est déclarée sous la forme littérale `add_argument("--self-test"`, que `check-ci-wiring.py` reconnaît : dès cette tâche, `check-ci-wiring.py` et `run_gates.py` sont rouges jusqu'au câblage dans `invariants` (rouge attendu) (tâche 3.2) (`controles-de-fusion` › Les retours anticipés de plateforme tolérés sont une liste fermée et motivée)

## 2. Tests corrigés (AUD-TST-04)

- [x] 2.1 AUD-TST-04 — `CommandLocatorTest` : `@EnabledOnOs(OS.WINDOWS)` sur `realWindowsBatchInvocationUsesCanonicalSystem32Cmd` et `realWindowsCmdPreservesAdversarialQuotedArguments`, `@EnabledOnOs(OS.LINUX)` sur `linuxSecurityAuthorityCommandsUseRootOwnedSystemPathsInsteadOfPath`, gardes retirées ; vérifier par `./mvnw -B -ntp -pl minos-runtime-local -am test` (un test sauté de plus sur le poste Windows) ; scope JaCoCo `provider-execution-trust-boundary` à comparer en CI
- [x] 2.2 AUD-TST-04 — `ManagedPolyglotScipRuntimeManagerTest.windowsReportsClangAsBlockedInsteadOfPretendingCrossPlatformSupport` et `M24PolyglotProviderTest.windowsDoesNotPretendScipClangRuntimeIsReady` : `@EnabledOnOs(OS.WINDOWS)`, gardes retirées ; vérifier par `./mvnw -B -ntp -pl minos-provider-scip -am test` puis `./mvnw -B -ntp -pl minos-app -am test` (l'un après l'autre) ; scope JaCoCo `m24-polyglot-provider-platform` (rouge sur poste Windows hors régression : comparer à `main`) ; rejouer `python scripts/quality/check-polyglot-provider-consistency.py`
- [x] 2.3 AUD-TST-04 — Preuve : `python scripts/quality/check-test-platform-guards.py` vert ; sur la PR, le rapport Surefire du job `Verify (ubuntu-24.04)` compte comme sautés les trois tests Windows de `minos-runtime-local`, `minos-provider-scip` et `minos-app` nommés ci-dessus
  - Fait : rapports Surefire de l'artefact `pr-validation-Linux-1` de la PR #742 (run 43744069549, tête c628318c) : `M24PolyglotProviderTest` (minos-app), `ManagedPolyglotScipRuntimeManagerTest` (minos-provider-scip) et les trois tests Windows de `CommandLocatorTest` (minos-runtime-local) sont comptés sautés, « Disabled on operating system: Linux », 0 échec.

## 3. Câblage et documentation (AUD-TST-04)

- [x] 3.1 AUD-TST-04 — Constat : `python scripts/quality/check-ci-wiring.py` échoue depuis la tâche 1.2 et tant que `check-test-platform-guards.py --self-test` n'est pas lancé par `invariants`
- [x] 3.2 AUD-TST-04 — Correction : deux étapes dans le job `invariants` de `.github/workflows/pr-ci.yml` (gate et auto-test) ; rejouer `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-workflow-pins.py`, `python .claude/scripts/run_gates.py`
- [x] 3.3 AUD-TST-04 — Documentation : `docs/developer/testing.md` (le gate remplace la recherche manuelle), `docs/developer/quality-gates.md` (gate, ce qu'il ne prouve pas : gardes de capacité), `docs/architecture/diagrams/capacites/controles-de-fusion.md` ; vérifier par `python scripts/docs/check-current-docs.py`

## 4. Clôture

- [x] 4.1 Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes (décompte Linux tant que la CI n'a pas tourné) ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [x] 4.2 Vérifier le dossier durable `controles-de-fusion` par `python scripts/docs/check-capability-dossiers.py`
- [x] 4.3 Valider les spécifications par `openspec validate --all --strict`
- [x] 4.4 Lister les documents à mettre à jour avant archivage (`docs/developer/testing.md`, `quality-gates.md`, `SUIVI.md` de l'audit du 2026-11-15, où est inscrit le constat de suivi des 34 gardes de lien symbolique) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive compter-comme-sautes-les-tests-propres-a-une-plateforme -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.

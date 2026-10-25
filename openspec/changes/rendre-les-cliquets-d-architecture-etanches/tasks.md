# Tasks

Ordre dans chaque section : rouge (cas d'auto-test sur arbres temporaires, puis mutation d'une copie du dépôt), règle, preuve. Aucun fichier de `src/main` n'est modifié. Les sections 1 et 2 modifient `check-module-boundaries.py` et son test : les faire en série. Gates à rejouer à chaque tâche : `python scripts/architecture/check-module-boundaries.py` et `python scripts/architecture/test_check_module_boundaries.py`. Aucune portée JaCoCo n'est concernée.

## 1. A11 et A13 : compteur de fichiers par type (AUD-ARC-01, AUD-ARC-08 ; SH-11)

- [x] 1.1 [AUD-ARC-01, AUD-ARC-08] Rouge : cas de `test_check_module_boundaries.py`. Refusés : un second fichier qui nomme un type listé pour un fichier (A11 et A13) ; un compteur plus grand que le nombre de fichiers observé (entrée périmée). Accepté : le compteur exact. **Preuve du rouge : les nouveaux cas échouent sur le garde du HEAD.**
- [x] 1.2 [AUD-ARC-01, AUD-ARC-08] Tables `ENGINE_TYPES_NAMED_BY_SURFACE` et `ADAPTER_TYPES_NAMED_BY_ADAPTER` en `type → fichiers`, relevées sur le HEAD (D1). Message qui liste les fichiers et le compteur attendu. Preuve : auto-test vert, garde vert sur le dépôt avec les décomptes de types et de fichiers.

## 2. A11 : accesseurs de `MinosApplication` (AUD-ARC-01)

- [x] 2.1 [AUD-ARC-01] Rouge : cas refusé (accesseur appelé sans nom, type absent de la liste) ; cas accepté (`var` à la place de l'import, même compteur) ; référence `::nom` comptée ; homonyme d'arité différente ignoré ; type de retour générique (`Optional<…>`) et type imbriqué résolus. **Preuve du rouge sur le garde du HEAD.**
- [x] 2.2 [AUD-ARC-01] Lecture des accesseurs dans la source de `MinosApplication`, comptage dans A11, `call_argument_count` paramétré par son libellé (D2, D5). Ajout des 4 entrées existantes (API et CLI : `registry.ProjectRegistry`, `store.CodeKnowledgeSnapshotStore`) et du fichier `MinosCliRunner` pour `hosted.HostedControlPlaneService`. Preuve : auto-test et garde verts, A11 = 43 types.

## 3. A12 : sites de lancement par classe (AUD-ARC-05 ; SH-08)

- [x] 3.1 [AUD-ARC-05] Rouge : un troisième `new ProcessBuilder` dans la classe listée (refusé) ; un site en moins (refusé, compteur à écrire) ; `ProcessBuilder::new` et `Runtime.getRuntime().exec` comptés ; `ProcessBuilder.Redirect` non compté. **Preuve du rouge sur le garde du HEAD.**
- [x] 3.2 [AUD-ARC-05] `PROCESS_LAUNCHERS` en `classe → sites` = `{"minos-engine/io.PrivateLocalStorage": 2}` (D3). Preuve : auto-test et garde verts.

## 4. A11 en bytecode (AUD-ARC-01)

- [x] 4.1 [AUD-ARC-01] Test `surfacesUseOnlyListedEngineTypes` de `ModuleArchitectureTest` et table `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE`, relevée sur les classes compilées du HEAD (D4). **Rouge : une table privée d'une entrée échoue en nommant le type et les classes ; une entrée en trop échoue en la nommant.** Preuve : `mvnw -pl minos-app -am test "-Dtest=ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert.

## 5. Preuves sur copie mutée

- [x] 5.1 [AUD-ARC-01, -05, -08] Sur une copie du dépôt, une mutation par règle : un nouveau fichier de la CLI qui importe `io.PrivateLocalStorage` ; un appel `app.snapshotStore()` dans un nouveau fichier de NEXUS ; un troisième `new ProcessBuilder` dans `PrivateLocalStorage` ; un nouveau fichier SCIP qui importe `CommandLocator`. Pour chaque mutation, le garde du HEAD réussit (rouge prouvé) et le garde modifié échoue. Consigner les commandes et les codes de sortie dans `SUIVI.md`.

## 6. Clôture

- [x] 6.1 `docs/developer/quality-gates.md` (§ A11 à A14 : grains, accesseurs, vue bytecode, façon de baisser un compteur) ; ADR 0068 « Compliance and Enforcement » (grains et nom du test) ; mise en œuvre de l'ADR 0058 si elle cite A11. Rejouer `python .claude/scripts/run_gates.py --fast`, `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py`.
- [x] 6.2 `docs/project/audits/2026-10-10/SUIVI.md` : statut, PR, compteurs avant et après, compteurs de départ des phases 2 à 4 ; AUD-ARC-12 clos par décision documentée (ADR 0068, D4). Plan du chantier (`docs/project/roadmap/storage-hexagonal-2026-10/README.md`) : SH-11 étendu.
- [x] 6.3 `openspec validate --all --strict` ; `archgate check`. Docs à mettre à jour avant archivage : `docs/developer/quality-gates.md`, `SUIVI.md`.

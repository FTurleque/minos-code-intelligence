## MODIFIED Requirements

### Requirement: Chaque règle de frontière du garde Python est couverte par un cas refusé et un cas accepté
Chaque règle du garde `scripts/architecture/check-module-boundaries.py` et de ses modules de règles `module_boundary_*.py` (A2 hexagonale, politique de dépendances, dépendances cachées, mise en page, frontières de sources, disposition Java, document généré, A3 et A7 à A20) SHALL avoir au moins un cas refusé et un cas accepté dans les auto-tests `test_check_module_boundaries*.py`. L'auto-test SHALL échouer si une fonction `check_*` n'est référencée par aucun test.

#### Scenario: Règle sans test
- **WHEN** une fonction `check_*` est ajoutée au script sans qu'aucun test la référence
- **THEN** `python scripts/architecture/test_check_module_boundaries.py` se termine avec le code 1 et nomme la fonction

#### Scenario: Arête interdite par l'ADR 0042
- **WHEN** une copie des POM réels déclare une dépendance de `minos-cli` vers `minos-storage` (ou de `minos-application` vers `minos-provider-scip`)
- **THEN** le garde échoue en nommant le module, la cible et la règle hexagonale violée

#### Scenario: Surface vers la racine de composition à la compilation
- **WHEN** un POM de surface déclare `minos-bootstrap` en portée `compile`
- **THEN** le garde échoue ; la même dépendance en portée `runtime` ou `test` est acceptée

#### Scenario: Retrait d'une dépendance
- **WHEN** la politique de dépendances reçoit un graphe où `minos-runtime-local` déclare `minos-engine` sans `minos-domain`
- **THEN** la règle de politique réussit (la table est un maximum, non une obligation ; la fraîcheur du document généré est une règle distincte)

#### Scenario: Classe d'adaptateur nommée par une source
- **WHEN** une source de production de `minos-cli` importe une classe d'un adaptateur par import simple, statique, joker ou nom qualifié
- **THEN** le garde échoue en nommant le fichier et la classe ; une simple mention en commentaire ou en littéral est acceptée

## ADDED Requirements

### Requirement: Le point d'entrée du garde exécute chacune de ses règles
L'auto-test du câblage SHALL dériver la liste des règles de toutes les fonctions `check_*` du garde et de ses modules de règles, sans liste recopiée, et prouver pour chacune qu'un espion qui lève une exception rend `main()` rouge : code 1, message de l'espion sur stderr. Une fonction `check_*` que `main()` n'exécute pas, ou dont l'erreur n'atteint pas `main()`, SHALL faire échouer l'auto-test en la nommant, sauf entrée d'une liste d'exemptions fermée et justifiée, vide à la livraison.

#### Scenario: Appel d'une règle retiré du point d'entrée
- **GIVEN** une copie de `scripts/architecture/` dans un répertoire temporaire, où la ligne `service_loaders = check_service_loader_class_loaders()` de `run_rules` est remplacée par `service_loaders = 0`
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_service_loader_class_loaders`
- **AND** la même mutation sur `check_embedded_adapters` (`api_users, embedded_adapters = 0, 0`) échoue de même

#### Scenario: Règle atteinte par une autre règle
- **GIVEN** une copie de `scripts/architecture/` où l'appel `check_pom_layout(module, pom)` de `check_pom_rules` est supprimé
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_pom_layout`

#### Scenario: Règle nouvelle non câblée
- **GIVEN** une copie de `scripts/architecture/` où un module de règles définit une fonction `check_demo` que `run_rules` n'appelle pas, référencée par un test
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_demo`

#### Scenario: Erreur d'une règle avalée
- **GIVEN** une copie de `scripts/architecture/` où `run_rules` entoure l'appel de `check_package_cycles` d'un `try` / `except Exception` qui rend `0, 0`
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_package_cycles`

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté sur le dépôt
- **THEN** l'auto-test réussit et chaque fonction `check_*` du garde et de ses modules de règles a son sous-test de câblage

### Requirement: Le lanceur partagé des gardes d'architecture a son propre auto-test
`scripts/architecture/gate_runner.py` SHALL avoir un auto-test exécuté par le job `invariants` de `pr-ci.yml`. Il SHALL prouver qu'un rapport rendu est imprimé sur stdout avec le code 0 et rien sur stderr, qu'une exception est rapportée sur stderr sous la forme `<label> FAILED: <message>` avec le code 1 et rien sur stdout, et qu'une interruption qui n'est pas une `Exception` (`SystemExit`, `KeyboardInterrupt`) n'est jamais rendue comme un succès.

#### Scenario: Rapport d'un garde vert
- **WHEN** `python scripts/architecture/test_gate_runner.py` est exécuté sur le dépôt
- **THEN** l'auto-test réussit, dont le cas où `run_gate` reçoit une vérification qui rend un rapport : code 0, rapport sur stdout, stderr vide

#### Scenario: Échec rendu comme un succès
- **GIVEN** une copie de `scripts/architecture/` dans un répertoire temporaire, où `return 1` de `run_gate` est remplacé par `return 0`
- **WHEN** `python test_gate_runner.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1

#### Scenario: Auto-test du lanceur sans exécutant
- **GIVEN** une copie du dépôt dont le job `invariants` de `.github/workflows/pr-ci.yml` n'a plus d'étape qui lance `scripts/architecture/test_gate_runner.py`
- **WHEN** `python scripts/quality/check-ci-wiring.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `scripts/architecture/test_gate_runner.py`

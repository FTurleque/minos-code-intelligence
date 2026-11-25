## MODIFIED Requirements

### Requirement: Le point d'entrée du garde exécute chacune de ses règles
L'auto-test du câblage SHALL dériver la liste des règles de toutes les fonctions `check_*` du garde et de chaque fichier `scripts/architecture/module_boundary_*.py` trouvé sur le disque, sans liste recopiée, et échouer sur un glob vide. Il SHALL prouver pour chaque règle qu'un espion qui lève une exception rend `main()` rouge (code 1, message sur stderr). Une `check_*` que `main()` n'exécute pas SHALL faire échouer l'auto-test en la nommant, sauf exemption fermée et justifiée, vide à la livraison.

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

#### Scenario: Module de règles nouveau non câblé
- **GIVEN** une copie du dépôt où `scripts/architecture/module_boundary_extra.py` définit `check_extra_rule`, que le garde n'importe pas, ou qu'il atteint seulement par `import module_boundary_extra` sans l'appeler
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_extra_rule`, dans les deux variantes

#### Scenario: Modules de règles introuvables
- **GIVEN** une copie du dépôt où le glob des modules de règles ne trouve aucun fichier `module_boundary_*.py`
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 au lieu de ne contrôler que le script d'entrée

#### Scenario: Erreur d'une règle avalée
- **GIVEN** une copie de `scripts/architecture/` où `run_rules` entoure l'appel de `check_package_cycles` d'un `try` / `except Exception` qui rend `0, 0`
- **WHEN** `python test_check_module_boundaries.py MainWiringTest` est exécuté dans la copie
- **THEN** l'auto-test se termine avec le code 1 et nomme `check_package_cycles`

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/test_check_module_boundaries.py` est exécuté sur le dépôt
- **THEN** l'auto-test réussit et chaque fonction `check_*` du garde et de ses modules de règles a son sous-test de câblage

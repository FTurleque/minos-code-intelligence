## ADDED Requirements

### Requirement: La documentation courante ne cite aucune ligne du garde de frontières
Hors des documents figés (`docs/project/history/`, `docs/project/audits/`, `docs/project/roadmap/`, archives, `.claude/agent-memory/`), `docs/` et `.claude/` SHALL ne citer aucune ligne de `scripts/architecture/check-module-boundaries.py`, ni décrire une copie de la politique de frontières dans `ModuleArchitectureTest`, ni situer « dans le script » une table que porte `module-boundaries.json` ou un module de règles.

#### Scenario: Documentation alignée
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE "check-module-boundaries\.py:[0-9]|liste .MODULES. du script|lue dans le script|ALLOWED. égale" -- docs .claude ':!docs/project/history' ':!docs/project/audits' ':!docs/project/roadmap' ':!**/archive/**' ':!.claude/agent-memory'` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Citation de ligne réintroduite
- **GIVEN** une copie de travail où le dossier de la capacité cite de nouveau `check-module-boundaries.py:59-85`
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

### Requirement: La documentation des gates nomme chaque fichier du garde de frontières
Le chapitre « Gardes d'architecture » de `docs/developer/quality-gates.md` SHALL nommer `check-module-boundaries.py`, chaque fichier `scripts/architecture/module_boundary_*.py` avec les règles qu'il porte, `gate_runner.py`, et `scripts/architecture/module-boundaries.json` comme source unique de la politique.

#### Scenario: Chaque fichier du garde est nommé
- **GIVEN** le dépôt après correction
- **WHEN** pour chaque fichier `f` de `scripts/architecture/module_boundary_*.py`, `gate_runner.py` et `module-boundaries.json`, `grep -c "$(basename f)" docs/developer/quality-gates.md` est exécuté
- **THEN** chaque compte est au moins 1

#### Scenario: Module de règles ajouté sans documentation
- **GIVEN** une copie de travail où un fichier `scripts/architecture/module_boundary_x.py` est ajouté sans modifier `docs/developer/quality-gates.md`
- **WHEN** la même boucle est exécutée
- **THEN** le compte de `module_boundary_x.py` vaut 0, ce que la revue refuse

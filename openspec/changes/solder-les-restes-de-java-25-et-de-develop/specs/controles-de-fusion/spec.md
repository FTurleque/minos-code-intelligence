## ADDED Requirements

### Requirement: Les documents courants nomment les rulesets déclarés et aucun risque ouvert ne vise une branche retirée
Dans `docs/project/governance/STATUS.md`, `docs/project/governance/ROADMAP.md` et les pages de `docs/developer/`, tout nom de ruleset cité sous la forme « Protect … » SHALL être un ruleset de `.github/required-checks.json`. Une ligne du registre des risques dont le statut est ouvert SHALL NOT citer la branche `develop`. Le gate des documents courants SHALL échouer dans les deux cas. Les relevés datés ne sont pas concernés.

#### Scenario: Ancien nom de ruleset dans l'état courant
- **GIVEN** une copie du dépôt où `STATUS.md` cite le ruleset « Protect main & develop »
- **WHEN** `python scripts/docs/check-current-docs.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier et le nom de ruleset inconnu

#### Scenario: Risque ouvert sur la branche retirée
- **GIVEN** une copie du dépôt où une ligne `OPEN` de `docs/architecture/risks/register.md` cite `develop`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant l'identifiant du risque

#### Scenario: Dépôt réel
- **GIVEN** les documents courants après le changement
- **WHEN** `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` sont exécutés
- **THEN** les deux réussissent

#### Scenario: Relevé daté
- **GIVEN** une section de sprint de `STATUS.md` qui dit « fusionné dans `develop` » pour une PR antérieure au 2026-11-13
- **WHEN** le gate est exécuté
- **THEN** cette mention n'est pas signalée

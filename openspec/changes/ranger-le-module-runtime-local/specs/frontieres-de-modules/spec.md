## ADDED Requirements

### Requirement: Le module d'exécution locale est rangé en sous-packages sans cycle
`minos-runtime-local` SHALL répartir ses classes entre `process`, `sandbox`, `workspace` et `distributed`, sa racine ne gardant que l'orchestration. Les dépendances SHALL aller de la racine vers les autres, de `sandbox` vers `process` et `workspace`, et de `process` vers `workspace`, sans cycle. Les ressources empaquetées SHALL rester chargées par chemin absolu.

#### Scenario: Aucun cycle entre les sous-packages
- **GIVEN** le module rangé
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit, la table des cycles connus restant vide

#### Scenario: Le fournisseur d'identité de chemin reste découvert
- **GIVEN** le module rangé
- **WHEN** les tests de `minos-runtime-local` sont exécutés sous Windows
- **THEN** `ServiceLoader` trouve le fournisseur déclaré dans `META-INF/services` et son script se charge

#### Scenario: Un espace de travail ne dépend pas du bac à sable
- **GIVEN** une classe de `workspace` qui importerait une classe de `sandbox` ou de `process`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le cycle de packages

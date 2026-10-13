## ADDED Requirements

### Requirement: Le module PostgreSQL est rangé en sous-packages sans cycle
`minos-storage-postgresql` SHALL répartir ses classes entre `support`, `snapshot`, `project` et `semantic`, sa racine ne gardant que le backend et son fournisseur. Les dépendances SHALL aller vers `support` et de la racine vers les autres, sans cycle. Le nom du fournisseur déclaré dans `META-INF/services` SHALL rester inchangé.

#### Scenario: Aucun cycle entre les sous-packages
- **GIVEN** le module rangé
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit, la table des cycles connus restant vide

#### Scenario: Le fournisseur reste découvert par son nom
- **GIVEN** le jar ombré construit
- **WHEN** `ShadedJarCompositionRootIT` est exécuté
- **THEN** il trouve `com.minos.storage.postgresql.PostgresStorageBackendProvider`

#### Scenario: Une classe de support ne dépend pas d'un store
- **GIVEN** une classe de `support` qui importerait une classe de `snapshot`, `project` ou `semantic`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le cycle de packages

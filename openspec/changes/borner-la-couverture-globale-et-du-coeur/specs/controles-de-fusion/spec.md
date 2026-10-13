## ADDED Requirements

### Requirement: La couverture globale du reactor est bornée sur chaque système de qualification
Le gate JaCoCo SHALL évaluer un scope `global` couvrant `com/minos/`, avec un plancher de lignes et de branches déclaré pour Linux et pour Windows. Il SHALL échouer quand la mesure est sous le plancher du système courant et SHALL écrire la mesure, le plancher et les exclusions nommées du scope dans `jacoco-gate.json`.

#### Scenario: Mesure sous le plancher du système courant
- **GIVEN** un rapport JaCoCo dont la couverture de `com/minos/` est inférieure au plancher déclaré pour le système courant
- **WHEN** `python scripts/quality/check-jacoco.py <rapport>` est exécuté
- **THEN** le gate échoue en nommant `global`, la mesure et le plancher

#### Scenario: Résultat publié
- **GIVEN** un rapport dont la couverture respecte le plancher
- **WHEN** le gate est exécuté
- **THEN** `jacoco-gate.json` contient pour `global` la mesure, le plancher du système courant et la liste des exclusions

#### Scenario: Exclusion sans objet
- **GIVEN** un scope dont une exclusion ne désigne aucune classe du rapport
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` est exécuté
- **THEN** le scénario correspondant constate l'échec du scope

### Requirement: Un scope sans plancher pour le système courant échoue
Quand un scope déclare ses planchers par système, l'absence de plancher pour le système courant SHALL faire échouer le gate. Seuls un scope réservé à un autre système et une exclusion explicite en ligne de commande SHALL produire le statut `SKIPPED`, avec leur raison.

#### Scenario: Plancher manquant
- **GIVEN** un scope qui déclare un plancher pour Linux seulement, sans être réservé à Linux
- **WHEN** l'auto-test du gate l'évalue sur un autre système
- **THEN** le scope est en échec et non sauté

#### Scenario: Scope réservé à un autre système
- **GIVEN** un scope réservé à un système qui n'est pas le système courant
- **WHEN** le gate est exécuté
- **THEN** le scope est `SKIPPED` et la raison nomme le système courant

### Requirement: Les classes du cœur nommées sont sous un scope ciblé
Le codec binaire des snapshots, les stores d'état d'index et d'empreinte, le registre de projets, les primitives d'E/S de `com/minos/io/` et la rétention des dossiers de run SHALL chacun appartenir à un scope JaCoCo ciblé. Le gate SHALL échouer si l'une de ces classes disparaît du rapport ou passe sous son plancher.

#### Scenario: Classe renommée ou supprimée
- **GIVEN** un rapport JaCoCo où `SnapshotBinaryCodecSupport` n'apparaît plus
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le préfixe qui ne désigne plus aucune classe

#### Scenario: Érosion d'une primitive d'E/S
- **GIVEN** un rapport où la couverture de `com/minos/io/` est sous le plancher du scope
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le scope des primitives d'E/S confinées

### Requirement: Une classe propre à une plateforme est jugée sur sa plateforme
Une classe qui ne s'exécute que sur un système SHALL appartenir à un scope réservé à ce système et y porter un plancher de classe. La propriété de processus par cgroup SHALL être jugée sur Linux et la propriété par Job Object sur Windows ; aucune des deux SHALL NOT être évaluée sur l'autre système.

#### Scenario: Job Object sous Windows
- **GIVEN** un rapport produit sous Windows où `WindowsJobObjectProcessOwnership` est sous son plancher de classe
- **WHEN** le gate est exécuté sous Windows
- **THEN** il échoue en nommant le scope Windows et la classe

#### Scenario: Job Object sous Linux
- **GIVEN** le gate exécuté sous Linux
- **WHEN** il atteint le scope Windows
- **THEN** ce scope est `SKIPPED` et n'entre pas dans le verdict

### Requirement: Un plancher de classe empêche un store de se cacher dans son paquet
Dans un scope défini par un préfixe de paquet, une classe désignée SHALL porter son propre plancher. Le store PostgreSQL des observations d'exécution SHALL en porter un dans le scope du stockage PostgreSQL. Là où ce scope n'est pas évalué, le résultat SHALL le dire (`SKIPPED`) et SHALL NOT être présenté comme réussi.

#### Scenario: Classe faible dans un paquet bien couvert
- **GIVEN** un rapport où le paquet respecte son plancher mais où la classe désignée est sous le sien
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant la classe, sa mesure et son plancher

#### Scenario: Scope écarté sous Windows
- **GIVEN** le gate exécuté avec `--skip-scope m30-postgresql-pgvector`
- **WHEN** `jacoco-gate.json` est lu
- **THEN** le scope y figure avec le statut `SKIPPED` et sa raison

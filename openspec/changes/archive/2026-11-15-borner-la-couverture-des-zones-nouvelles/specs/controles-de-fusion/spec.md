## ADDED Requirements

### Requirement: Les zones créées après la mesure de référence sont sous un scope nominatif
Le gate JaCoCo SHALL évaluer un scope nominatif pour le module de l'explorateur sémantique, pour la voie d'indexation WSL2 et pour la projection 2D, chacun avec un plancher de lignes et de branches tiré d'une mesure citée du rapport agrégé de la CI. Il SHALL échouer quand un préfixe de ces scopes ne désigne plus aucune classe du rapport, et SHALL NOT laisser le scope `global` seul juge de ces zones.

#### Scenario: Les trois scopes sont évalués
- **GIVEN** un rapport agrégé produit par `./mvnw -B -ntp verify`
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** `jacoco-gate.json` contient `explorer-surface`, `wsl-lane` et `semantic-projection` avec leur mesure et leur plancher pour le système courant

#### Scenario: Les tests d'une zone disparaissent
- **GIVEN** un rapport où la couverture des classes de `com/minos/explorer/` est tombée sous le plancher du scope alors que `global` respecte le sien
- **WHEN** le gate est exécuté
- **THEN** il se termine avec le code 1 et nomme `explorer-surface`, la mesure et le plancher

#### Scenario: Une zone est renommée sans son scope
- **GIVEN** un rapport où aucune classe ne commence par `com/minos/runtime/local/wsl/`
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant le préfixe mort du scope `wsl-lane`

#### Scenario: Déclaration retirée du gate
- **GIVEN** une copie de la table des scopes privée de `semantic-projection`
- **WHEN** `python scripts/quality/check-jacoco.py --self-test` l'évalue
- **THEN** le cas correspondant de l'auto-test échoue en nommant le scope manquant

### Requirement: La garde de la surface HTTP locale a un plancher de classe
Dans le scope de l'explorateur, les classes qui décident de servir une requête (contrôle de l'hôte, de l'origine et du jeton, jeton de session, politique d'écoute) SHALL chacune porter un plancher de lignes et de branches. Le gate SHALL échouer quand l'une d'elles passe sous son plancher, même si le scope respecte le sien.

#### Scenario: Garde affaiblie dans un module bien couvert
- **GIVEN** un rapport où `explorer-surface` respecte son plancher et où `RequestGuard` est sous son plancher de classe
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `com/minos/explorer/RequestGuard`, sa mesure et son plancher

#### Scenario: Classe de garde absente du rapport
- **GIVEN** un rapport où `com/minos/explorer/SessionToken` ne figure plus
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant la classe et ne la compte pas comme conforme

### Requirement: Le budget mémoire des vues a un plancher de classe
La classe qui tient le budget mémoire des vues de requête SHALL appartenir à un scope ciblé du stockage et y porter un plancher de classe. Le gate SHALL échouer quand elle disparaît du rapport ou passe sous ce plancher.

#### Scenario: Budget sous son plancher
- **GIVEN** un rapport où `com/minos/storage/codec/QueryViewMemoryBudget` est sous son plancher de classe
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** le gate échoue en nommant la classe, sa mesure et son plancher

#### Scenario: État du dépôt
- **GIVEN** un rapport agrégé produit par `./mvnw -B -ntp verify` au commit candidat
- **WHEN** `python scripts/quality/check-jacoco.py` est exécuté
- **THEN** il se termine avec le code 0 et `target/m21-quality/jacoco-gate.json` porte la mesure et le plancher de la classe

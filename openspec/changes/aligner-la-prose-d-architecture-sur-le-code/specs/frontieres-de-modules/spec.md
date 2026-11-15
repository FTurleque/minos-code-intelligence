## ADDED Requirements

### Requirement: La portée d'une dépendance hors compilation est écrite dans la documentation de dépendances
Le document de dépendances généré SHALL afficher, à côté de chaque dépendance interne déclarée dans une portée autre que la compilation, cette portée (`test` ou `runtime`). Dans `docs/architecture/arc42/05-vue-blocs.md`, la ligne « Dépendances » d'un module SHALL faire suivre un tel module d'une parenthèse qui commence par sa portée, et le garde SHALL échouer si la portée manque, est fausse, ou est écrite pour une dépendance de compilation.

#### Scenario: Document généré
- **GIVEN** le dépôt, où `minos-explorer` déclare `minos-engine` en portée `test` et `minos-bootstrap` en portée `runtime`
- **WHEN** `python scripts/architecture/check-module-boundaries.py --write-doc` est exécuté
- **THEN** la ligne de `minos-explorer` du document porte ces deux portées, et une seconde exécution sans `--write-doc` se termine avec le code 0

#### Scenario: Portée absente de l'arc42
- **GIVEN** une copie de la vue des blocs où la ligne de `minos-explorer` cite `minos-engine` sans portée
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le module, la dépendance et la portée attendue `test`

#### Scenario: Parenthèse qui n'est pas une portée
- **GIVEN** le dépôt, où la ligne de `minos-bootstrap` fait suivre sa dernière dépendance d'une parenthèse « (voir … ) »
- **WHEN** le garde est exécuté
- **THEN** cette parenthèse n'est pas lue comme une portée, et la ligne est acceptée puisque la dépendance est déclarée en compilation

#### Scenario: Portée écrite à tort
- **GIVEN** une copie de la vue des blocs où la ligne de `minos-cli` écrit `minos-application` suivi de la portée `test`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue : la dépendance est déclarée en compilation

### Requirement: Les racines de packages citées par la documentation d'un module sont les siennes
Pour chaque section de module de `docs/architecture/arc42/05-vue-blocs.md`, le garde SHALL exiger une ligne « Sources ». Les racines `com/minos/<racine>/` qu'elle cite SHALL être exactement celles que la table des racines de packages attribue au module. Une ligne qui ne cite que le répertoire de sources du module, sans racine, SHALL être acceptée.

#### Scenario: Racine omise
- **GIVEN** une copie de la vue des blocs où la ligne « Sources » de `minos-engine` cite ses racines sauf `com/minos/io/`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant `minos-engine` et la racine `io` manquante

#### Scenario: Racine d'un autre module
- **GIVEN** une copie où la ligne « Sources » de `minos-domain` cite `com/minos/store/`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant la racine que le module ne possède pas

#### Scenario: Section sans ligne Sources
- **GIVEN** une copie où la section d'un module n'a pas de ligne « Sources »
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue (jamais un succès par défaut)

#### Scenario: Répertoire de sources seul
- **GIVEN** le dépôt, où la ligne de `minos-application` cite seulement son répertoire de sources
- **WHEN** le garde est exécuté
- **THEN** la ligne est acceptée

### Requirement: Les interfaces citées par la documentation d'un module existent et sont attribuées au bon module
Dans une ligne « Interfaces » de la vue des blocs, chaque type cité entre accents graves SHALL être un type de production du reactor, appartenant au module de la section ou à un module que la ligne nomme par une tournure « défini dans » ; un module nommé autrement SHALL NOT compter. La mention « aucune » SHALL être refusée pour un module qui déclare une interface publique de premier niveau. Une section sans ligne « Interfaces » SHALL rester acceptée.

#### Scenario: Port attribué au mauvais module
- **GIVEN** une copie où la ligne de `minos-storage` cite `SemanticVectorStore` parmi des types « définis dans `minos-engine` », et nomme `minos-application` dans une autre phrase
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le type et son module réel, `minos-domain`

#### Scenario: Aucune interface déclarée à tort
- **GIVEN** une copie où la ligne de `minos-domain` vaut « aucune »
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant au moins une interface publique du module

#### Scenario: Type disparu
- **GIVEN** une copie où une ligne « Interfaces » cite un type qu'aucun module ne déclare
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le type

#### Scenario: Documentation corrigée
- **GIVEN** le dépôt après correction de la vue des blocs
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

## MODIFIED Requirements

### Requirement: Les adaptateurs de stockage forment un seul module
Le reactor SHALL compter un seul module de stockage, `minos-storage`, qui contient `com.minos.storage.local` et `com.minos.storage.postgresql` sans classe dupliquée ni package déclaré ailleurs. `ALLOWED_DEPENDENCIES` et `ModuleArchitectureTest.ALLOWED` SHALL lui donner `minos-domain` et `minos-engine` pour seules cibles. Le garde SHALL refuser un module `minos-storage-local` ou `minos-storage-postgresql` réapparu.

#### Scenario: Reactor fusionné
- **GIVEN** le dépôt après la fusion
- **WHEN** `python scripts/architecture/check-module-boundaries.py` s'exécute
- **THEN** il réussit, la liste `MODULES` égale la liste des modules du reactor (règle A7), et la politique de dépendances lue par le garde et par `ModuleArchitectureTest` nomme `minos-storage` sans aucun des deux anciens nœuds

#### Scenario: Ancien module réintroduit
- **GIVEN** une copie du dépôt dont le reactor déclare de nouveau `minos-storage-postgresql`
- **WHEN** le garde s'exécute
- **THEN** il échoue en nommant le module absent de `MODULES`

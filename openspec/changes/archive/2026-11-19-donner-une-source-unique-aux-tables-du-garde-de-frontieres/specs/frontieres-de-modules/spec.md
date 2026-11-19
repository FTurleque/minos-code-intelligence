## ADDED Requirements

### Requirement: La politique de frontières partagée a une source unique
La liste des modules, leur famille, les dépendances internes autorisées, les couples admis en portée test seulement et les deux vues de la dette A11 (source et bytecode) SHALL être écrits dans un seul fichier de données versionné. Le garde Python et `ModuleArchitectureTest` SHALL lire ce fichier et SHALL NOT en tenir de copie. Un mouvement de cliquet SHALL se faire par une seule édition.

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** `python scripts/architecture/check-module-boundaries.py` puis `ModuleArchitectureTest` sont exécutés
- **THEN** les deux réussissent, et le garde affiche le même nombre de modules et de règles qu'avant la bascule

#### Scenario: Arête retirée de la source unique
- **GIVEN** une copie du dépôt où l'arête `minos-cli` → `minos-nexus` est retirée du seul fichier de données
- **WHEN** le garde, puis `ModuleArchitectureTest`, sont exécutés sur cette copie
- **THEN** le garde échoue en nommant l'arête du POM hors politique, et la règle ArchUnit des modules autorisés échoue sur le même couple, sans autre édition

#### Scenario: Compteur de la vue bytecode
- **GIVEN** une copie du dépôt où le compteur d'un type du moteur de la vue bytecode est augmenté de un dans le fichier de données
- **WHEN** `ModuleArchitectureTest` est exécuté
- **THEN** il échoue en nommant la surface, le type, le compteur écrit et le compteur observé

### Requirement: Une source de politique illisible fait échouer les deux lecteurs
Le garde Python et `ModuleArchitectureTest` SHALL échouer, sans appliquer de valeur par défaut, quand le fichier de politique est absent, n'est pas analysable, porte une clé inconnue ou une clé en double, omet une section attendue, donne à un module une famille inconnue ou nomme un module absent de la liste des modules. Le message SHALL nommer le fichier par son chemin relatif au dépôt et la cause.

#### Scenario: Fichier absent
- **GIVEN** une copie du dépôt sans le fichier de politique
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant le fichier attendu, et n'affiche aucune ligne de succès

#### Scenario: Clé inconnue
- **GIVEN** une copie du fichier où une section est mal orthographiée
- **WHEN** le garde, puis `ModuleArchitectureTest`, sont exécutés
- **THEN** chacun échoue en nommant la clé inconnue ; aucun ne traite la section manquante comme vide

#### Scenario: Module inconnu dans une arête
- **GIVEN** une copie du fichier où une dépendance autorisée nomme `minos-inconnu`
- **WHEN** le garde est exécuté
- **THEN** il échoue en nommant le module et l'entrée

### Requirement: Le test ArchUnit ne redéclare aucune table de politique
La règle A17 du garde SHALL échouer quand `ModuleArchitectureTest` ne lit plus le fichier de politique, ou déclare de nouveau une table littérale de dépendances autorisées, de modules, d'adaptateurs, de surfaces ou de types du moteur. La règle SHALL échouer aussi quand le fichier du test est absent ou illisible.

#### Scenario: Table recopiée dans le test
- **GIVEN** une copie du dépôt où le test déclare de nouveau une table littérale `ALLOWED` par `Map.ofEntries`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A17 en nommant le test et la table recopiée

#### Scenario: Test qui ne lit plus la source
- **GIVEN** une copie du dépôt où le test ne nomme plus le fichier de politique
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A17

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** la ligne A17 annonce que le test lit la source unique, et `archgate check` réussit

## MODIFIED Requirements

### Requirement: Les types du moteur utilisés par le bytecode d'une surface le sont par liste fermée
Le test `ModuleArchitectureTest` SHALL appliquer, par surface et par module de contrat, la liste des types de premier niveau de `minos-engine` dont dépendent ses classes compilées, chacun avec le nombre de classes de premier niveau qui en dépendent. Cette liste SHALL être lue dans la source unique de la politique de frontières. Un type hors liste, un compteur différent ou une entrée inutilisée SHALL faire échouer le test.

#### Scenario: Appel chaîné sur un résultat du moteur
- **GIVEN** une classe de `minos-cli` qui appelle une méthode d'un type de `minos-engine` absent de la liste, sans le nommer
- **WHEN** `ModuleArchitectureTest` est exécuté
- **THEN** il échoue en nommant la surface, le type, le compteur attendu et les classes

#### Scenario: Usage retiré sans mettre la table à jour
- **GIVEN** une liste de la source unique qui nomme un type dont plus aucune classe de la surface ne dépend
- **WHEN** le test est exécuté
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le reactor compilé
- **WHEN** `ModuleArchitectureTest` est exécuté dans les jobs `Verify` Linux et Windows
- **THEN** il réussit

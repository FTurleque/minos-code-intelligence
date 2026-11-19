## ADDED Requirements

### Requirement: Un module de contrat reste sous les surfaces
Le garde SHALL tenir une famille de modules de contrat, distincte des surfaces, qui contient `minos-nexus`. Un module de contrat SHALL ne dépendre que de `minos-domain` et de `minos-application` en production, et de `minos-bootstrap` en portée test seulement. Il SHALL NOT dépendre d'une surface, d'un adaptateur, de `minos-engine` ni de `minos-app`. `ModuleArchitectureTest` SHALL appliquer la même règle sur le bytecode.

#### Scenario: Dépôt réel
- **GIVEN** le dépôt, où `minos-nexus` appartient à la famille de contrat et plus à celle des surfaces
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il se termine avec le code 0

#### Scenario: Module de contrat qui dépend d'une surface
- **GIVEN** une copie des POM où `minos-nexus` déclare `minos-cli`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant `minos-nexus`, `minos-cli` et la famille de contrat

#### Scenario: Racine de composition hors portée test
- **GIVEN** une copie des POM où `minos-nexus` déclare `minos-bootstrap` en portée `runtime`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant l'arête et la portée attendue

#### Scenario: Type du moteur réintroduit
- **GIVEN** une copie du dépôt où une classe de `minos-nexus` importe un type de `minos-engine`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A11 en nommant `minos-nexus`, le fichier et le type

### Requirement: Aucune surface ne dépend d'une autre surface
Le garde SHALL refuser toute dépendance interne d'un module de surface vers un autre module de surface, quelle que soit sa portée Maven. Une surface SHALL pouvoir dépendre d'un module de contrat. L'assemblage `minos-app` n'est pas une surface et reste hors de cette règle.

#### Scenario: Arête de la CLI vers le contrat NEXUS
- **GIVEN** le dépôt, où `minos-cli` déclare `minos-nexus`
- **WHEN** le garde est exécuté
- **THEN** il réussit, l'arête allant d'une surface vers un module de contrat

#### Scenario: Surface qui dépend d'une surface
- **GIVEN** une copie des POM où `minos-mcp` déclare `minos-cli` en portée `test`
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il se termine avec le code 1 en nommant les deux surfaces

### Requirement: Le signal sémantique NEXUS est déclaré non qualifié
Tant qu'aucune commande ni aucun client ne consomme `NexusSemanticSignalService`, la classe SHALL rester déclarée dans la liste des types publics sans appelant avec sa raison, et `docs/developer/public-surfaces.md` SHALL la présenter comme non qualifiée par un client. La documentation SHALL NOT la présenter comme une capacité disponible.

#### Scenario: Documentation honnête
- **GIVEN** le dépôt
- **WHEN** on cherche `NexusSemanticSignalService` dans `docs/developer/public-surfaces.md`
- **THEN** la section NEXUS porte la mention « non qualifié » sur la même ligne ou le même paragraphe

#### Scenario: Entrée du cliquet retirée sans appelant
- **GIVEN** une copie du dépôt où l'entrée de `NexusSemanticSignalService` est retirée de la liste des types sans appelant
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en A15 en nommant la classe

## MODIFIED Requirements

### Requirement: Les surfaces n'assemblent pas le cycle d'indexation
Aucune surface (`minos-cli`, `minos-api`, `minos-mcp`) ni aucun module de contrat (`minos-nexus`) SHALL NOT construire `IndexingLifecycleService` ni enchaîner elle-même découverte, négociation, planification et exécution. Ces étapes SHALL être assemblées dans `minos-application`, qui SHALL NOT nommer de classe d'adaptateur pour le faire.

#### Scenario: Construction du cycle hors des surfaces
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `new IndexingLifecycleService` dans les sources de production de ces quatre modules
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Cas d'usage sans adaptateur
- **GIVEN** le cas d'usage d'indexation de `minos-application`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** le garde réussit et ne signale aucun import d'adaptateur depuis `minos-application`

### Requirement: Les types du moteur nommés par une surface le sont par liste fermée
Le garde de frontières SHALL tenir, pour chaque surface (`minos-cli`, `minos-api`, `minos-mcp`) et chaque module de contrat (`minos-nexus`), les types de `minos-engine` qu'elle utilise, chacun avec le nombre de fichiers de production qui l'utilisent. Un type hors liste SHALL faire échouer le garde, ainsi qu'un compteur différent du nombre de fichiers observé, une entrée inutilisée ou une entrée désignant un type inexistant.

#### Scenario: Nouveau type du moteur importé par la CLI
- **GIVEN** une copie du dépôt où un fichier de `minos-cli` importe un type de `minos-engine` absent de la liste
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur cette copie
- **THEN** le garde échoue en nommant la surface, le fichier et le type

#### Scenario: Nouveau fichier qui nomme un type déjà listé
- **GIVEN** une copie du dépôt où un nouveau fichier de `minos-cli` importe `io.PrivateLocalStorage`, déjà listé pour un fichier
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant le type, le compteur listé, le compteur observé et les fichiers qui l'utilisent

#### Scenario: Compteur devenu trop grand
- **GIVEN** une copie du dépôt où l'un des fichiers qui nomment un type listé ne le nomme plus
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en donnant le compteur à écrire

#### Scenario: Entrée devenue inutile
- **GIVEN** une copie du dépôt où le dernier usage d'un type listé a été retiré sans retirer l'entrée
- **WHEN** le garde est exécuté sur cette copie
- **THEN** il échoue en nommant l'entrée à retirer

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le garde est exécuté
- **THEN** il réussit et affiche, par surface et par module de contrat, le nombre de types listés et de fichiers qui les utilisent

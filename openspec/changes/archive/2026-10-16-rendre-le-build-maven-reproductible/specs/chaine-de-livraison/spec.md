## ADDED Requirements

### Requirement: Le jar distribué est reproductible à partir du commit
Deux constructions du jar ombré à partir du même commit, sur la même plateforme et dans des répertoires de travail propres, SHALL produire des fichiers de même SHA-256. L'horodatage des archives SHALL dériver du commit et non de l'heure de construction. Les scripts qui construisent un livrable SHALL transmettre cette date.

#### Scenario: Double construction
- **GIVEN** deux copies propres du dépôt au même commit, sur la même plateforme
- **WHEN** le jar ombré est construit dans chacune puis comparé par le contrôle de reproductibilité
- **THEN** les deux SHA-256 sont égaux

#### Scenario: Écart détecté
- **GIVEN** deux archives dont une entrée diffère
- **WHEN** le contrôle de reproductibilité les compare
- **THEN** il échoue et nomme les entrées qui diffèrent

#### Scenario: Horodatage des entrées
- **GIVEN** le jar ombré construit par le script de distribution
- **WHEN** les dates de ses entrées sont lues
- **THEN** elles sont égales à la date du commit, pas à celle de la construction

#### Scenario: Construction sans dépôt Git
- **GIVEN** une archive source sans métadonnées Git
- **WHEN** le jar est construit
- **THEN** la construction réussit avec l'horodatage par défaut fixe du POM

### Requirement: Tout plugin de build a une version déclarée
Le POM racine SHALL déclarer une version pour chaque plugin Maven utilisé par le reactor, plugins du cycle par défaut compris. Maven Enforcer SHALL échouer quand un plugin est résolu sans version déclarée.

#### Scenario: Plugin sans version
- **GIVEN** une copie du dépôt où la version d'un plugin du cycle par défaut est retirée de la gestion des plugins
- **WHEN** `./mvnw -B -ntp -N validate` est exécuté sur cette copie
- **THEN** Maven Enforcer échoue en nommant le plugin

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** `./mvnw -B -ntp validate` est exécuté sur tout le reactor
- **THEN** la règle réussit pour les quatorze modules

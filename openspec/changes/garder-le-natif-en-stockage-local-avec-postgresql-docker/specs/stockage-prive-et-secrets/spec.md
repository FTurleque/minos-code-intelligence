## ADDED Requirements

### Requirement: Le MINOS_HOME natif d'une installation PostgreSQL gérée par Docker est en stockage local
Quand le script de configuration d'installation reçoit un PostgreSQL géré par Docker, il SHALL écrire `minos.storage.backend=local` dans le `MINOS_HOME` natif et SHALL NOT y écrire de clé `minos.postgres.*`. L'état d'installation SHALL garder le choix PostgreSQL géré.

#### Scenario: Installation avec PostgreSQL géré par Docker
- **GIVEN** `configure-runtime-settings.ps1 -StorageBackend postgresql -McpBackend docker -ManagedDockerPostgres`
- **WHEN** le script s'exécute sur un dossier vide
- **THEN** `config/minos.properties` contient `minos.storage.backend=local` et aucune clé `minos.postgres.`, et l'état porte `storageBackend=postgresql` et `managedDockerPostgres=true`

#### Scenario: PostgreSQL externe inchangé
- **GIVEN** le même script sans `-ManagedDockerPostgres`, avec une URL externe valide
- **WHEN** il s'exécute
- **THEN** `minos.storage.backend=postgresql` et `minos.postgres.managed=false` sont écrits

### Requirement: Un PostgreSQL géré sans URL est refusé hors Docker de façon actionnable
Quand la configuration désigne un PostgreSQL géré sans URL et que l'emplacement d'exécution est natif, la résolution du stockage SHALL échouer par une erreur qui nomme le runtime Docker et `minos.storage.backend=local`, sans chemin absolu ni secret, et SHALL NOT basculer sur le stockage local. En Docker, le comportement SHALL rester inchangé.

#### Scenario: Configuration native résiduelle
- **GIVEN** `minos.storage.backend=postgresql`, `minos.postgres.managed=true`, sans URL, emplacement natif
- **WHEN** la configuration du stockage est résolue
- **THEN** elle échoue et le message nomme `MINOS_RUNTIME_LOCATION=docker` et `minos.storage.backend=local`

#### Scenario: Même configuration en Docker
- **GIVEN** la même configuration avec `MINOS_RUNTIME_LOCATION=docker`
- **WHEN** la configuration est résolue
- **THEN** elle réussit et désigne un PostgreSQL géré

#### Scenario: URL présente en natif
- **GIVEN** un PostgreSQL géré avec une URL de bouclage, emplacement natif
- **WHEN** la configuration est résolue
- **THEN** elle réussit

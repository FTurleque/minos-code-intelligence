## ADDED Requirements

### Requirement: Un run d'indexation en échec donne une erreur actionnable sans chemin absolu
La CLI SHALL rapporter un run d'indexation qui n'a pas réussi par une ligne qui nomme l'identifiant du run et sa phase. Elle SHALL y joindre la cause enregistrée si elle est sûre ; sinon elle SHALL la remplacer par l'emplacement de l'enregistrement du run relatif à `MINOS_HOME`. La ligne MUST NOT contenir de chemin absolu ni se réduire au nom d'une classe d'exception. Le code de sortie reste 1.

#### Scenario: Cause qui cite un chemin absolu
- **GIVEN** un run en échec en phase `PROVIDER_EXECUTION` dont la cause enregistrée cite un chemin absolu
- **WHEN** la CLI rapporte l'échec de l'indexation
- **THEN** la ligne contient l'identifiant du run, `PROVIDER_EXECUTION` et `MINOS_HOME/index-state/runs`
- **AND** elle ne contient ni le chemin absolu ni seulement `IllegalStateException`, et le code de sortie est 1

#### Scenario: Cause sûre
- **GIVEN** un run en échec dont la cause enregistrée est `IllegalStateException: provider exited with code 1; see runs/r/scip-java/provider.stderr.log under MINOS_HOME`
- **WHEN** la CLI rapporte l'échec de l'indexation
- **THEN** la ligne contient cette cause telle quelle

#### Scenario: Cause qui contient un secret
- **GIVEN** un run en échec dont la cause enregistrée contient `password=`
- **WHEN** la CLI rapporte l'échec de l'indexation
- **THEN** la ligne ne contient pas la cause et renvoie à l'enregistrement du run

### Requirement: Un échec de provider nomme ses traces sans chemin absolu
Le message d'un provider sorti avec un code non nul SHALL nommer son journal d'erreur par un chemin relatif à `MINOS_HOME`. Le refus d'une dépendance que le bac à sable Windows ne peut pas accorder SHALL nommer le fichier par son seul nom. Ni l'un ni l'autre MUST NOT contenir de chemin absolu.

#### Scenario: Sortie non nulle
- **GIVEN** un provider qui sort avec le code 7
- **WHEN** l'exécuteur rapporte l'échec
- **THEN** le message contient `exited with code 7` et `provider.stderr.log`, et n'est pas jugé sensible par la règle commune

#### Scenario: Dépendance non accordable
- **GIVEN** sous Windows, un exécutable de provider que le compte courant ne peut pas accorder au conteneur
- **WHEN** le bac à sable refuse le plan
- **THEN** le message contient le nom du fichier et `without administrator privileges`, sans son répertoire

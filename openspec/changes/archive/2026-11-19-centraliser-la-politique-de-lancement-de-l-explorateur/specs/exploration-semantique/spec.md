## ADDED Requirements

### Requirement: Le mode équipe est interprété par une seule règle au lancement de l'explorateur
`minos explorer serve` SHALL décider du mode équipe par la même règle que l'ouverture de l'application : absent ou `disabled` vaut désactivé, `enabled` vaut activé, sans égard à la casse. Toute autre valeur SHALL être refusée par l'erreur d'usage « unsupported hosted mode », code de sortie 2, avant l'ouverture de l'application et avant toute écoute.

#### Scenario: Mode équipe activé
- **GIVEN** un `MINOS_HOME` dont les réglages fixent `MINOS_HOSTED_MODE=Enabled`
- **WHEN** `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ExplorerLaunchRouteProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** la route refuse avec le message du mode équipe et le code 2, sans ouvrir l'application

#### Scenario: Mode équipe désactivé ou absent
- **GIVEN** un `MINOS_HOME` natif sans réglage de mode équipe, puis avec `disabled`
- **WHEN** la route est exécutée avec un ouvreur et une surface de test
- **THEN** l'application est ouverte et la surface servie dans les deux cas

#### Scenario: Valeur non reconnue
- **GIVEN** un `MINOS_HOME` dont les réglages fixent le mode équipe à `maybe`
- **WHEN** la route est exécutée
- **THEN** elle écrit une erreur qui contient `unsupported hosted mode` et rend le code 2
- **AND** l'application n'est pas ouverte et aucun port n'est écouté

#### Scenario: Même règle que l'application
- **GIVEN** la table de valeurs absente, vide, `disabled`, `DISABLED`, `enabled`, `Enabled`, `maybe`
- **WHEN** un test de `minos-application` interroge la fonction d'interprétation puis ouvre l'application avec chaque valeur
- **THEN** la fonction et l'ouverture classent chaque valeur de la même façon

### Requirement: Chaque refus de lancement de l'explorateur a un seul texte
Le message de refus en mode équipe et le message de refus sur un home au backend Docker SHALL être définis chacun une seule fois, dans `minos-explorer`. Un refus décidé avant l'ouverture de l'application et un refus décidé sur l'application ouverte SHALL afficher le même texte.

#### Scenario: Une seule définition
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `is not available when the team mode is enabled` dans les sources de production de tous les modules
- **THEN** une seule ligne de `minos-explorer` le contient

#### Scenario: Même texte avant et après l'ouverture
- **GIVEN** un home en mode équipe, puis une application de test ouverte dont le plan de contrôle d'équipe est présent
- **WHEN** la route, puis `ExplorerSession.serve`, refusent
- **THEN** les deux écrivent le même message et rendent le code 2

#### Scenario: Home au backend Docker sur un poste
- **GIVEN** un poste natif dont le home porte un backend d'exécution `docker`
- **WHEN** la route est exécutée
- **THEN** elle refuse avec le message du backend Docker et le code 2, sans lire le mode équipe ni ouvrir l'application

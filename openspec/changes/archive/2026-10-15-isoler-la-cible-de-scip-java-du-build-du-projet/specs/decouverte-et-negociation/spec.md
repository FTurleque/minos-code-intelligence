## ADDED Requirements

### Requirement: scip-java écrit ses fichiers intermédiaires hors de l'arbre de build du projet
Le plan d'exécution de scip-java SHALL passer un `--targetroot` situé dans le répertoire du run, hors de la copie de travail du projet, pour la commande sur classpath vérifié, pour le lanceur autonome et pour le runner Windows. Un module dont le `clean` vide le répertoire de build d'un autre module SHALL NOT retirer de l'index les fichiers des modules construits avant lui.

#### Scenario: Commande Linux sur classpath vérifié
- **GIVEN** un run dont l'artefact est `<run>/index.scip` et la copie de travail `<run>/workspace`
- **WHEN** la commande scip-java du plan est construite
- **THEN** elle contient `--targetroot <run>/scip-java-targetroot`, chemin absolu hors de `<run>/workspace`

#### Scenario: Reactor dont le dernier module construit dans le target racine
- **GIVEN** un reactor Maven dont le dernier module déclare `<directory>${maven.multiModuleProjectDirectory}/target</directory>`
- **WHEN** MINOS indexe ce reactor avec scip-java
- **THEN** l'index contient les fichiers de tous les modules, pas seulement ceux du dernier

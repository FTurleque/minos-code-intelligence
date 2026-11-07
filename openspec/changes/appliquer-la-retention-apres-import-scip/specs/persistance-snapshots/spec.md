# Spec Delta

## ADDED Requirements

### Requirement: Un import manuel applique la même rétention que l'indexation
Après qu'un import SCIP explicite (`import-scip`, ou `index --scip`) a publié son snapshot, MINOS SHALL appliquer au projet la politique de rétention par défaut appliquée après une indexation, sans attendre la prochaine indexation. Le snapshot actif et celui que désigne l'état du projet SHALL rester. Un import refusé ou en échec SHALL NOT déclencher la rétention.

#### Scenario: Quatre imports distincts sur le store fichier
- **GIVEN** un projet inscrit sur le backend local, sans snapshot
- **WHEN** quatre imports successifs publient quatre snapshots d'identifiants distincts
- **THEN** chaque import réussit et le snapshot actif est le dernier importé
- **AND** il reste trois snapshots de connaissance au projet : l'actif et les deux historiques les plus récents ; le premier importé est supprimé

#### Scenario: Rétention après la publication
- **GIVEN** un import dont la publication du snapshot réussit
- **WHEN** l'import se termine
- **THEN** la rétention du projet a été appliquée une fois, après la publication du snapshot et l'enregistrement de l'état du projet

#### Scenario: Import refusé
- **GIVEN** un artefact SCIP qui n'est pas un fichier régulier
- **WHEN** l'import est demandé
- **THEN** l'import est refusé avec le message existant, qui ne cite que le nom de l'artefact
- **AND** aucune rétention n'est appliquée et aucun snapshot n'est supprimé

### Requirement: Un verrou de rétention occupé ne fait pas échouer un import publié
Si la rétention appliquée après un import publié échoue sur une erreur d'entrée-sortie (verrou occupé ou indisponible), l'import SHALL rester réussi, avec son statut de validation inchangé, et son diagnostic SHALL dire que la rétention n'a pas été appliquée, par la ligne que l'indexation rend dans ce cas. Ce diagnostic SHALL NOT contenir la cause de l'échec ni un chemin.

#### Scenario: Verrou occupé après un import
- **GIVEN** un import dont le snapshot est publié et l'état enregistré
- **AND** une rétention qui échoue avec un message citant un chemin absolu
- **WHEN** l'import se termine
- **THEN** l'import réussit, son statut de validation est `COMMITTED` et le snapshot importé est actif
- **AND** le diagnostic vaut « storage retention did not run (its lock was busy or unavailable); it runs after the next indexing » et ne contient pas le chemin

#### Scenario: Verrou occupé et validation en attente
- **GIVEN** un import dont le snapshot est publié mais dont l'état du projet n'a pas pu être enregistré
- **AND** une rétention qui échoue sur une erreur d'entrée-sortie
- **WHEN** l'import se termine
- **THEN** le statut de validation reste `COMMITTED_METADATA_PENDING`
- **AND** le diagnostic porte la ligne de la validation en attente puis celle de la rétention non appliquée

### Requirement: La rétention après un import s'applique au store PostgreSQL
Sur le backend PostgreSQL, la rétention appliquée après un import SHALL supprimer les snapshots de connaissance historiques au-delà de la politique par défaut, y compris ceux publiés sans run d'indexation, et SHALL conserver le snapshot actif et celui que désigne l'état du projet.

#### Scenario: Snapshots importés sans run
- **GIVEN** un projet PostgreSQL dont quatre snapshots ont été publiés par import, sans aucun run d'indexation
- **AND** un état du projet qui désigne le dernier snapshot, sans dernier run
- **WHEN** la rétention par défaut s'exécute sur le projet
- **THEN** il reste trois snapshots de connaissance : l'actif et les deux historiques les plus récents
- **AND** le snapshot actif et celui de l'état du projet sont conservés

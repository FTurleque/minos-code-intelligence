## ADDED Requirements

### Requirement: La mise en place SCIP passe par des magasins de travail injectés
Le cycle de snapshot SCIP SHALL ouvrir ses magasins de normalisation et de mise en place par le port `StagingSnapshotStores` reçu à la construction, sous `MINOS_HOME/staged-snapshots/<exécution>` seulement. Le snapshot mis en place SHALL rester lisible par une promotion reprise dans un autre processus, et l'arbre écrit SHALL rester celui d'avant le port. Une promotion sans snapshot mis en place SHALL échouer sans toucher au magasin actif.

#### Scenario: Répertoires ouverts par le cycle
- **GIVEN** un cycle construit avec un `StagingSnapshotStores` qui enregistre les répertoires ouverts, et une exécution à deux fournisseurs
- **WHEN** `stage` puis `promote` s'exécutent
- **THEN** chaque répertoire ouvert est sous `staged-snapshots/<exécution>`, et aucun magasin n'est créé autrement

#### Scenario: Promotion reprise par un autre processus
- **GIVEN** un snapshot mis en place par un premier cycle, et un second cycle construit sur le même `MINOS_HOME`
- **WHEN** le second cycle promeut l'exécution
- **THEN** le magasin actif publie le snapshot mis en place, et `staged-snapshots/<exécution>` est supprimé

#### Scenario: Promotion sans snapshot mis en place
- **GIVEN** une exécution dont `staged-snapshots/<exécution>` n'existe pas
- **WHEN** `promote` s'exécute
- **THEN** il échoue par `staged project snapshot is missing for run`, et le magasin actif est inchangé

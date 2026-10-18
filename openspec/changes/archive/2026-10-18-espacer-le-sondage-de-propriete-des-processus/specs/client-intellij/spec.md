## ADDED Requirements

### Requirement: Le superviseur de processus du plugin sonde à cadence bornée
En régime établi, le superviseur de processus du plugin SHALL énumérer les descendants d'une commande MINOS au plus 15 fois par seconde, avec la même période que le suivi du runtime local, sans importer aucun artefact `com.minos:*`. Il SHALL continuer de terminer tout descendant observé, et un descendant encore vivant après l'arrêt forcé SHALL rester signalé comme un échec, jamais ignoré.

#### Scenario: Cadence en régime établi dans le plugin
- **GIVEN** un superviseur attaché à un processus factice vivant dont l'énumération des descendants compte ses appels
- **WHEN** deux secondes s'écoulent après la phase initiale
- **THEN** l'énumération a été appelée au moins une fois et au plus 30 fois

#### Scenario: Orphelin observé puis nettoyé
- **GIVEN** une commande dont le processus lance un enfant détaché puis sort
- **WHEN** le superviseur est fermé
- **THEN** l'enfant observé a été terminé

#### Scenario: Survivant après l'arrêt forcé
- **GIVEN** un descendant observé qui reste vivant après l'arrêt forcé
- **WHEN** le superviseur est fermé
- **THEN** la fermeture rapporte l'échec de nettoyage

#### Scenario: Aucune dépendance au noyau
- **GIVEN** le plugin après ce changement
- **WHEN** la garde de dépendances du plugin est exécutée
- **THEN** aucune dépendance ni import `com.minos:*` n'est trouvé

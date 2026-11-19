## ADDED Requirements

### Requirement: L'historique d'import est lu et écrit par un port
`minos-application` SHALL lire et écrire l'historique d'import d'un projet par un port de `minos-engine`, dont l'implémentation fichier vit dans `minos-storage` et est fournie par `minos-bootstrap`. Aucune source de production de `minos-application` SHALL nommer le dossier `cli-index-history` ni les clés du fichier. `MinosApplication` SHALL NOT exposer ce port par une méthode publique.

#### Scenario: Littéral absent de l'application
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `cli-index-history` dans `minos-application/src/main`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Un seul détenteur du format
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `cli-index-history` dans les sources de production de tous les modules
- **THEN** une seule classe, dans `minos-storage`, le contient

#### Scenario: Import réussi écrit l'historique sans avertissement
- **GIVEN** un projet enregistré et un artefact SCIP valide
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=ImportHistoryFailureTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'import est rapporté `COMMITTED`, le fichier d'historique du projet existe et aucun avertissement n'est journalisé

#### Scenario: Inspection après import
- **GIVEN** une application ouverte par la racine de composition de production, après l'import d'un artefact SCIP de provider et de version connus
- **WHEN** un test de `minos-bootstrap` inspecte le projet
- **THEN** la vue rend l'instantané actif, le provider, sa version et la date de fin de l'import

#### Scenario: Port non exposé par la façade
- **GIVEN** le dépôt après le changement
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** le garde A11 réussit sans entrée nouvelle dans `MINOS_APPLICATION_ENGINE_ACCESSORS`

### Requirement: Le format de l'historique d'import reste lisible
L'implémentation fichier SHALL conserver l'emplacement `MINOS_HOME/cli-index-history/<identifiant du projet>.properties` et les clés `snapshotId`, `providerId`, `providerVersion`, `completedAt`. Un fichier écrit par une version antérieure SHALL être relu à l'identique. Un fichier absent SHALL se lire comme « aucun historique ».

#### Scenario: Fichier d'une version antérieure
- **GIVEN** un fichier d'historique copié tel qu'il est écrit au HEAD, avec `providerVersion` vide
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileIndexImportHistoryStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** les quatre valeurs sont rendues, la version du provider étant absente

#### Scenario: Projet sans historique
- **GIVEN** un `MINOS_HOME` sans dossier `cli-index-history`
- **WHEN** l'historique d'un projet est lu
- **THEN** le résultat est vide et aucun dossier n'est créé

#### Scenario: Écriture atomique et privée
- **GIVEN** un historique existant pour un projet
- **WHEN** un nouvel historique est écrit
- **THEN** le fichier est remplacé de façon atomique, garde des droits privés et aucun fichier temporaire ne subsiste

### Requirement: Un historique d'import illisible est refusé sans fuite
Un fichier d'historique hors bornes ou privé d'une clé obligatoire SHALL être refusé par une erreur qui nomme la clé manquante et la feuille du fichier, jamais son chemin absolu. Un échec d'écriture de l'historique SHALL NOT annuler un import dont l'instantané est actif.

#### Scenario: Clé obligatoire manquante
- **GIVEN** un fichier d'historique sans `snapshotId`
- **WHEN** `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileIndexImportHistoryStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'erreur nomme `snapshotId` et le nom du fichier, sans chemin absolu

#### Scenario: Refus du port relayé par l'inspection
- **GIVEN** un port d'historique de test qui lève l'erreur de clé manquante
- **WHEN** `./mvnw -B -ntp -pl minos-application -am test "-Dtest=ProjectInspectionServiceRefusalMessageTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** l'inspection relaie le message du port sans y ajouter de chemin absolu

#### Scenario: Fichier hors bornes
- **GIVEN** un fichier d'historique plus grand que la borne de lecture
- **WHEN** l'implémentation fichier le lit
- **THEN** la lecture est refusée avant d'avoir chargé le contenu en entier

#### Scenario: Dossier d'historique inutilisable
- **GIVEN** un `MINOS_HOME` où `cli-index-history` est un fichier ordinaire
- **WHEN** un import SCIP réussit (`ImportHistoryFailureTest`)
- **THEN** l'import est rapporté réussi avec un avertissement qui cite le projet et l'instantané actif, sans chemin absolu ni nom du dossier

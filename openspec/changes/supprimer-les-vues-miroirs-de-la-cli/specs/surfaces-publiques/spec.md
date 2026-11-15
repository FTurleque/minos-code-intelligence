## ADDED Requirements

### Requirement: La CLI ne recopie pas à l'identique un résumé applicatif
`minos-cli` SHALL utiliser directement un résumé de `minos-application` quand la vue qu'elle en tirerait aurait les mêmes composantes, les mêmes types et les mêmes contrôles. La matérialisation distante et la preuve d'artefact SHALL être rendues depuis `RemoteMaterializationSummary` et `ArtifactEvidenceSummary`, sans enregistrement intermédiaire.

#### Scenario: Miroirs supprimés
- **GIVEN** le dépôt après le changement
- **WHEN** on recherche `record RemoteMaterializationView` et `record ArtifactEvidence(` dans `minos-cli/src/main`
- **THEN** la recherche ne retourne aucune ligne

#### Scenario: Sortie de la commande distante inchangée
- **GIVEN** le dépôt après le changement
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=RemoteIndexCommandTest,LocalRemoteIndexOperationsIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** les sorties texte et JSON attendues par ces tests sont identiques à celles du HEAD

#### Scenario: Preuve d'artefact invalide refusée
- **GIVEN** une preuve d'artefact dont l'empreinte SHA-256 est vide
- **WHEN** le résumé applicatif est construit
- **THEN** la construction échoue en nommant le champ, comme le faisait la vue supprimée

### Requirement: Une vue conservée de la CLI couvre toutes les composantes de son résumé
Pour chaque vue de la CLI construite depuis un résumé de `minos-application`, toute composante du résumé SHALL avoir une composante dans la vue, par son nom ou par une correspondance déclarée, ou figurer dans une table d'exclusions portant une raison. Un test SHALL échouer quand une composante ajoutée au résumé n'a ni l'une ni l'autre.

#### Scenario: Parité au HEAD du changement
- **GIVEN** les couples plan, exécution, reprise, runtime de provider et indexation distante
- **WHEN** `./mvnw -B -ntp -pl minos-cli -am test "-Dtest=CliViewParityTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est exécuté
- **THEN** le test réussit pour chaque couple

#### Scenario: Composante ajoutée au résumé seulement
- **GIVEN** un couple d'enregistrements de test où le résumé porte une composante absente de la vue et des tables
- **WHEN** la comparaison du test de parité est appliquée à ce couple
- **THEN** elle échoue en nommant le résumé et la composante

#### Scenario: Exclusion sans raison
- **GIVEN** une table d'exclusions de test dont une entrée n'a pas de raison écrite
- **WHEN** la comparaison est appliquée
- **THEN** elle échoue en nommant l'entrée

#### Scenario: Exclusion périmée
- **GIVEN** une table d'exclusions de test qui nomme une composante que le résumé ne déclare plus
- **WHEN** la comparaison est appliquée
- **THEN** elle échoue en demandant de retirer l'entrée

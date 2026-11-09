## MODIFIED Requirements

### Requirement: scip-java sous Windows n'est pas déclaré prêt sans exécution qualifiée sous le bac à sable
Tant qu'aucun test n'exécute la chaîne scip-java Windows sous le bac à sable AppContainer, la disponibilité de scip-java sous Windows SHALL être bloquée, avec un prérequis qui dit que l'exécution native sous le bac à sable n'est pas qualifiée et que l'indexation Java depuis Windows passe par `minos index --via-wsl` ; il MUST NOT renvoyer vers le runtime Docker, qui ne lance aucun provider. L'indexation Java native SHALL alors être refusée avant tout lancement de provider.

#### Scenario: Doctor sous Windows
- **GIVEN** sous Windows, scip-java installé avec tous ses fichiers
- **WHEN** `minos doctor` est lancé
- **THEN** scip-java n'est pas `READY`, son diagnostic nomme `minos index --via-wsl` et ne nomme pas le runtime Docker

#### Scenario: Indexation native refusée
- **GIVEN** sous Windows, un projet Maven Java enregistré
- **WHEN** `minos index <projet>` est lancé sans `--via-wsl`
- **THEN** l'indexation Java est refusée avant tout lancement de provider, et le diagnostic nomme `--via-wsl`

#### Scenario: Linux inchangé
- **GIVEN** sous Linux, scip-java installé avec tous ses fichiers
- **WHEN** la disponibilité est calculée
- **THEN** elle reste `READY`

## ADDED Requirements

### Requirement: Sous Linux, l'installation de scip-java installe Coursier depuis le catalogue
Sous Linux, quand aucun Coursier géré n'est installé, l'installation de scip-java SHALL télécharger le lanceur Coursier `linux-x64` du catalogue, SHALL vérifier son empreinte SHA-256 avant de le rendre exécutable, puis SHALL l'installer sous `MINOS_HOME/tools`. Une empreinte différente MUST faire échouer l'installation sans laisser de fichier exécutable ni partiel.

#### Scenario: Aucun cs dans le PATH
- **GIVEN** sous Linux, aucun `cs` dans le `PATH` et aucun Coursier géré
- **WHEN** `minos tools install scip-java` est lancé avec accès réseau
- **THEN** le lanceur Coursier est installé sous `MINOS_HOME/tools` et son origine est inscrite au registre des outils

#### Scenario: Empreinte non conforme
- **GIVEN** sous Linux, une source qui sert pour Coursier d'autres octets que ceux épinglés au catalogue
- **WHEN** l'installation de scip-java est lancée
- **THEN** elle échoue en nommant Coursier, et aucun fichier Coursier exécutable ou partiel ne reste sous `MINOS_HOME/tools`

#### Scenario: Hors ligne sans Coursier
- **GIVEN** sous Linux, aucun Coursier géré ni dans le `PATH`, et le réseau refusé à l'installation
- **WHEN** l'installation de scip-java est lancée
- **THEN** elle échoue avec un diagnostic actionnable qui nomme Coursier, sans chemin absolu

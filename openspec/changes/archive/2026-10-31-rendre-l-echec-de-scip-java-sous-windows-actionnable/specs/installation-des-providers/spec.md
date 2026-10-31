## ADDED Requirements

### Requirement: scip-java sous Windows n'utilise que le PowerShell du répertoire système
Sous Windows, le plan et la disponibilité de scip-java SHALL utiliser le Windows PowerShell 5.1 du répertoire système, le même que celui du bac à sable. Un `powershell.exe` ou un `pwsh.exe` trouvé dans le `PATH` MUST NOT être retenu, ni par le plan ni par la disponibilité.

#### Scenario: PowerShell 7 seul dans le PATH
- **GIVEN** sous Windows, un `PATH` qui contient un `pwsh.exe` et pas le répertoire de Windows PowerShell
- **WHEN** la disponibilité de scip-java est calculée
- **THEN** elle juge le `powershell.exe` du répertoire système

#### Scenario: PowerShell dans le PATH
- **GIVEN** sous Windows, un `PATH` qui ne contient qu'un `powershell.exe` et un `pwsh.exe` d'un autre répertoire
- **WHEN** le plan scip-java est construit
- **THEN** sa commande commence par le `powershell.exe` du répertoire système et ne nomme pas cet autre répertoire

### Requirement: scip-java sous Windows n'est pas déclaré prêt sans exécution qualifiée sous le bac à sable
Tant qu'aucun test n'exécute la chaîne scip-java Windows sous le bac à sable AppContainer, la disponibilité de scip-java sous Windows SHALL être bloquée, avec un prérequis qui dit que l'exécution sous le bac à sable n'est pas qualifiée et qu'aucune voie n'indexe un projet Java depuis Windows ; il MUST NOT renvoyer vers le runtime Docker, qui ne lance aucun provider. L'indexation Java SHALL alors être refusée avant tout lancement de provider.

#### Scenario: Doctor sous Windows
- **GIVEN** sous Windows, scip-java installé avec tous ses fichiers
- **WHEN** `minos doctor` est lancé
- **THEN** scip-java n'est pas `READY`, son diagnostic dit qu'aucune voie n'indexe un projet Java depuis Windows et ne nomme pas le runtime Docker

#### Scenario: Linux inchangé
- **GIVEN** sous Linux, scip-java installé avec tous ses fichiers
- **WHEN** la disponibilité est calculée
- **THEN** elle reste `READY`

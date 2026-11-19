## ADDED Requirements

### Requirement: Un échec interne du worker WSL transmet un diagnostic structuré et vérifié
Quand le worker échoue par une exception, il SHALL émettre avant sa ligne de statut une ligne de diagnostic du protocole qui porte une référence, les classes de la chaîne de causes et un nombre borné de trames, sans message ni chemin. L'installation Windows SHALL n'accepter que les lignes conformes à cette grammaire, les journaliser comme un échec interne et ignorer toute autre ligne. Le statut public SHALL porter la référence.

#### Scenario: Exception du provider dans la distribution
- **GIVEN** un worker dont l'exécuteur lève un `NullPointerException`
- **WHEN** le worker s'exécute (`./mvnw -B -ntp -pl minos-runtime-local -am test "-Dtest=WslLinuxWorkerDiagnosticTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** sa sortie contient une ligne de diagnostic avec la classe, l'origine et la référence, puis une ligne `FAILED` qui porte la même référence
- **AND** aucune des deux lignes ne contient `/mnt/`, `/home/` ni `C:\`

#### Scenario: Le diagnostic atteint le journal de l'installation Windows
- **GIVEN** une sortie de worker qui contient une ligne de diagnostic conforme puis un statut `FAILED`
- **WHEN** l'installation Windows la lit
- **THEN** l'échec public nomme la distribution et la référence, et la ligne de diagnostic de l'installation porte les classes et l'origine transmises

#### Scenario: Ligne de diagnostic non conforme
- **GIVEN** une sortie de worker dont la ligne de diagnostic contient un séparateur de chemin ou dépasse la longueur admise
- **WHEN** l'installation Windows la lit
- **THEN** la ligne est ignorée, rien de son contenu n'est journalisé ni rendu, et l'échec public reste celui du statut

#### Scenario: Une installation antérieure lit la sortie
- **GIVEN** une sortie qui contient la ligne de diagnostic puis le statut
- **WHEN** elle est lue par la règle « dernière ligne de statut »
- **THEN** le statut retenu est la ligne `FAILED` ou `OK`, jamais la ligne de diagnostic

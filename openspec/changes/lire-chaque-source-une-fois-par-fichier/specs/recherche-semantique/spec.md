## ADDED Requirements

### Requirement: La construction des documents sémantiques ouvre chaque fichier source une fois
Pendant une construction de documents sémantiques, chaque fichier source SHALL être ouvert et décodé au plus une fois, quel que soit le nombre de ses symboles. Les documents produits SHALL être identiques, à l'octet, à ceux produits par une lecture par symbole.

#### Scenario: Fichier à plusieurs symboles
- **GIVEN** un snapshot dont un fichier porte vingt symboles et un lecteur de sources qui compte les ouvertures
- **WHEN** les documents sémantiques sont construits
- **THEN** le compteur d'ouvertures de ce fichier vaut 1

#### Scenario: Ouvertures égales au nombre de fichiers
- **GIVEN** un snapshot de fixture couvrant plusieurs fichiers
- **WHEN** les documents sémantiques sont construits
- **THEN** le nombre total d'ouvertures égale le nombre de fichiers distincts portant au moins un symbole

#### Scenario: Documents identiques
- **GIVEN** les fixtures de qualification, dont des fichiers aux fins de ligne `\n` et `\r\n`
- **WHEN** les documents sont construits
- **THEN** leurs empreintes sont celles relevées avant ce changement

#### Scenario: Fichier illisible
- **GIVEN** un fichier du snapshot supprimé du disque avant la construction
- **WHEN** les documents sont construits
- **THEN** la construction se termine et les documents de ce fichier sont ceux que produisait la lecture par symbole dans le même cas

### Requirement: Aucun contenu de source n'est conservé après la construction
Le contenu d'un fichier lu pour construire des documents SHALL NOT être conservé au-delà du traitement de ce fichier. Un extrait demandé après la construction SHALL refléter le contenu présent sur le disque à ce moment.

#### Scenario: Fichier modifié après la construction
- **GIVEN** une construction terminée, puis un fichier source modifié sur le disque
- **WHEN** un extrait de ce fichier est demandé
- **THEN** l'extrait contient le nouveau contenu

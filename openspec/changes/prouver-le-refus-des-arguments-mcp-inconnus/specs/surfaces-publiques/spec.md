## ADDED Requirements

### Requirement: Un argument MCP absent du schéma publié est refusé
Tout outil MCP SHALL refuser un appel dont un argument n'est pas une propriété de son schéma publié. Le client SHALL recevoir une erreur, et l'opération SHALL NOT être exécutée avec les valeurs par défaut. Ce refus SHALL être prouvé de bout en bout par le transport STDIO, et pour chaque outil du catalogue.

#### Scenario: Argument mal orthographié par STDIO
- **GIVEN** un serveur MCP lancé en STDIO sur un projet indexé
- **WHEN** un client appelle un outil qui accepte `limit` avec l'argument `limt`
- **THEN** la réponse est une erreur et ne contient aucun résultat de l'outil

#### Scenario: Arguments du schéma
- **GIVEN** le même serveur
- **WHEN** le client appelle le même outil avec les seuls arguments de son schéma
- **THEN** l'appel est servi comme avant

#### Scenario: Chaque outil du catalogue
- **GIVEN** le catalogue des outils MCP
- **WHEN** chaque outil reçoit ses arguments requis et une clé supplémentaire inconnue
- **THEN** aucun outil n'exécute son opération

### Requirement: Le refus d'un argument inconnu n'expose aucune valeur d'argument
Lors du refus d'un argument inconnu, ni la réponse au client ni le journal de l'opérateur SHALL contenir la valeur d'un argument de l'appelant. Le nom de l'argument refusé, s'il est rendu ou journalisé, SHALL être borné en longueur et soumis au détecteur de message sensible.

#### Scenario: Valeur absente du journal
- **GIVEN** un appel par STDIO avec un argument inconnu dont la valeur est un chemin absolu
- **WHEN** le serveur refuse l'appel
- **THEN** ni stderr ni la réponse ne contiennent ce chemin

#### Scenario: Nom d'argument démesuré
- **GIVEN** un appel avec une clé inconnue de 10 000 caractères
- **WHEN** le serveur refuse l'appel
- **THEN** la réponse reste bornée et ne reproduit pas la clé entière

#### Scenario: Nom d'argument sensible
- **GIVEN** un appel avec une clé inconnue qui a la forme d'un chemin absolu
- **WHEN** le serveur refuse l'appel
- **THEN** la réponse est l'erreur générique et ne contient pas cette clé

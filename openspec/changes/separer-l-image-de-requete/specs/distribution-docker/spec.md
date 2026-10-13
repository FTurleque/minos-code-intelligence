## ADDED Requirements

### Requirement: L'image du plan de requête ne contient ni compilateur ni outil de provider
Le conteneur du plan de requête MCP SHALL utiliser une image limitée au runtime Java et au jar de MINOS. Cette image SHALL NOT contenir de JDK, de compilateur C, de toolchain Rust, Go ou .NET, de Node ni de gestionnaire de paquets de langage. L'image d'administration SHALL conserver ce qu'exige l'indexation.

#### Scenario: Compilateurs absents de l'image de requête
- **GIVEN** l'image de requête construite par la qualification de release
- **WHEN** `gcc`, `cargo`, `go`, `dotnet`, `node`, `pip3` et `javac` sont recherchés dans le conteneur
- **THEN** aucune de ces commandes n'existe

#### Scenario: Le plan de requête répond sur l'image de requête
- **GIVEN** un index existant et le serveur MCP lancé dans l'image de requête
- **WHEN** la poignée de main MCP puis une requête de symboles sont envoyées
- **THEN** les deux réponses sont valides

#### Scenario: Toolchain réintroduite dans la cible de requête
- **GIVEN** une copie du Dockerfile où la cible de requête copie un stage de toolchain
- **WHEN** le test de contrat Docker de `minos-app` est exécuté sur cette copie
- **THEN** le test échoue en nommant la ligne

### Requirement: Chaque service Docker utilise l'image qui correspond à son rôle
Les fichiers compose SHALL désigner l'image de requête pour tout service qui n'exécute ni provider ni outil embarqué, et l'image d'administration pour les autres. Le conteneur persistant du plan de requête SHALL NOT utiliser l'image d'administration.

#### Scenario: Service de requête sur l'image d'administration
- **GIVEN** une copie d'un fichier compose où `minos-mcp` désigne l'image d'administration
- **WHEN** le test de contrat Docker est exécuté sur cette copie
- **THEN** le test échoue en nommant le service

#### Scenario: Classement des services réels
- **GIVEN** les deux fichiers compose du dépôt
- **WHEN** le test de contrat Docker et `python scripts/quality/check-compose-limits.py` sont exécutés
- **THEN** les deux réussissent et chaque service a une image de l'une des deux variables

### Requirement: Une installation sans image de requête échoue de façon actionnable
Quand la variable de l'image de requête est absente ou vide, le lancement SHALL échouer avant de démarrer un conteneur, avec un message qui nomme la commande de mise à jour. Il SHALL NOT utiliser l'image d'administration à la place. La mise à jour d'une installation à une seule image SHALL produire les deux.

#### Scenario: Variable absente
- **GIVEN** une installation dont la configuration ne définit pas l'image de requête
- **WHEN** le script de cycle de vie démarre le plan de requête
- **THEN** il échoue sans démarrer de conteneur et son message nomme la commande de mise à jour

#### Scenario: Mise à niveau d'une installation à une seule image
- **GIVEN** une installation de la version précédente, avec une seule image
- **WHEN** la qualification de mise à niveau Docker est exécutée
- **THEN** l'installation mise à jour définit les deux images et le plan de requête répond
